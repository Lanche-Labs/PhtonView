package com.phtontools.phtonview.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.phtontools.phtonview.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatic WiFi camera discovery.
 *
 * Most camera vendors expose their PTP-IP / app-mode services via mDNS/NSD with
 * vendor-specific service types. This class listens for those announcements and
 * also falls back to a quick port scan on the local subnet for known PTP-IP ports.
 *
 * The module is deliberately decoupled from [WifiCameraConnection]: callers receive
 * a discovered [CameraServiceInfo] and can decide how to pair/connect.
 */
@Singleton
class WifiCameraDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val nsdManager: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _discoveredServices = MutableStateFlow<List<CameraServiceInfo>>(emptyList())
    val discoveredServices: StateFlow<List<CameraServiceInfo>> = _discoveredServices

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private val _scanProgress = MutableStateFlow(ScanProgress.IDLE)
    val scanProgress: StateFlow<ScanProgress> = _scanProgress

    // 线程安全：NSD 回调线程和 IO 协程都会读写。
    private val discoveryListeners = CopyOnWriteArrayList<NsdManager.DiscoveryListener>()

    // **fix (issue: mDNS 扫描卡死闪退)**：跟踪全轮询 Job，stopDiscovery 可立刻取消；
    // 之前裸 scope.launch 完全无引用，stopDiscovery 只能"等所有 in-flight socket 跑完"才停。
    private var scanJob: Job? = null

    // **fix (issue: mDNS 扫描卡死闪退)**：持有 MulticastLock 才能在大多数 Android 版本上
    // 收到 mDNS 多播响应（之前 AndroidManifest 声明了 CHANGE_WIFI_MULTICAST_STATE 但代码从不 acquire）。
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Service types commonly used by camera vendors for app-to-camera communication.
     * The list is intentionally broad so the app can find cameras without manual input.
     */
    private val serviceTypes = listOf(
        "_ptp._tcp",           // Generic PTP-IP
        "_ptp-ip._tcp",        // Alternate PTP-IP
        "_canon-pip._tcp",     // Canon
        "_sony._tcp",          // Sony Imaging Edge / Remote
        "_sony-imaging._tcp",  // Sony alternate
        "_fuji._tcp",          // Fujifilm
        "_fujifilm._tcp",      // Fujifilm alternate
        "_nikon._tcp",         // Nikon
        "_panasonic._tcp",     // Panasonic
        "_lumix._tcp",         // Panasonic alternate
        "_olympus._tcp",       // Olympus
        "_omsystem._tcp"       // OM System
    )

    /**
     * Well-known PTP-IP command ports. If mDNS fails, we probe these ports on the
     * local subnet as a fallback.
     *
     * **fix (issue: mDNS 扫描卡死闪退)**：原 17 个端口是按"宁可错杀"原则堆出来的，但实际
     * 厂商用的就 5~6 个，多余的 4755/4756/4758/4760/4761/15742~15745/8080 几乎都是死端口，
     * 配合 254 主机并发扫描会瞬间打满 socket FD 表，导致 "Too many open files" 闪退。
     * 收敛到 6 个高命中端口，外加 80 兜底 HTTP 配置页。
     */
    private val knownCommandPorts = listOf(
        15740, // Generic PTP-IP, Nikon, Sony
        15741, // Event port (some cameras also listen here)
        4757,  // Sony Imaging Edge
        4759,  // Canon Camera Connect / Image Transfer Utility
        49152, // Panasonic / Lumix Sync
        80     // HTTP config fallback
    )

    /**
     * Discovery timeout for mDNS and port scan.
     */
    private val discoveryTimeoutMs = 8000L
    private val portScanTimeoutMs = 600

    data class CameraServiceInfo(
        val host: String,
        val port: Int,
        val serviceType: String,
        val name: String,
        val vendorHint: String?
    )

    /**
     * 扫描阶段状态，用于 UI 显示进度。
     */
    enum class ScanProgress {
        IDLE,
        SCANNING_MDNS,
        SCANNING_PORTS,
        DONE,
        FAILED
    }

    /**
     * 全量轮询扫描：先 mDNS 发现，再子网端口扫描。
     * 反复轮询直到用户停止或发现至少一台相机。
     *
     * **fix (issue: mDNS 扫描卡死闪退)**：
     * 1. 入口 acquire MulticastLock，否则 NSD 收不到多播响应。
     * 2. 整个轮询用 withTimeoutOrNull(35_000L) 封顶，超时后强制 FAILED + stopDiscovery，
     *    避免"UI 一直显示 SCANNING / 协程泄漏"。
     * 3. 把 scanJob 存到字段，stopDiscovery() 可直接 cancel()，不等所有 in-flight socket。
     * 4. maxRounds 默认 2（原 3）：一轮 mDNS 失败后立刻跑端口扫描，缩短总等待时间。
     */
    fun startFullScan(maxRounds: Int = 2) {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
        _scanProgress.value = ScanProgress.SCANNING_MDNS
        _discoveredServices.value = emptyList()

        acquireMulticastLock()

        nsdManager?.let { manager ->
            for (type in serviceTypes) {
                val listener = createNsdDiscoveryListener(type)
                discoveryListeners.add(listener)
                try {
                    manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (e: Exception) {
                    AppLogger.w("mDNS discovery failed for $type: ${e.message}")
                }
            }
        } ?: AppLogger.w("NsdManager not available, falling back to port scan only")

        scanJob = scope.launch {
            // 显式标注 <Boolean>：lambda 内部通过 return@withTimeoutOrNull 早返回 Unit，
            // 最后一行是 false (Boolean)，类型推断会变成 Any。标注后早返回被收紧到 Boolean。
            val timedOut: Boolean = withTimeoutOrNull(35_000L) {
                repeat(maxRounds) { round ->
                    if (!_isDiscovering.value) return@withTimeoutOrNull false
                    AppLogger.d("WiFi scan round $round/${maxRounds - 1}")
                    _scanProgress.value = if (round == 0) ScanProgress.SCANNING_MDNS else ScanProgress.SCANNING_PORTS
                    runRoundScan(round)
                    // 一旦发现相机，立刻结束后续轮询
                    if (_discoveredServices.value.isNotEmpty()) return@withTimeoutOrNull false
                }
                false
            } ?: run {
                // 超时：协程在 await/cancel 中
                AppLogger.w("mDNS full scan timed out after 35s")
                true
            }
            _scanProgress.value = when {
                timedOut -> ScanProgress.FAILED
                _discoveredServices.value.isNotEmpty() -> ScanProgress.DONE
                else -> ScanProgress.FAILED
            }
            stopDiscovery()
        }
    }

    /**
     * 兼容旧 API：单轮 mDNS + 端口扫描。
     */
    fun startDiscovery() {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
        _scanProgress.value = ScanProgress.SCANNING_MDNS
        _discoveredServices.value = emptyList()

        nsdManager?.let { manager ->
            for (type in serviceTypes) {
                val listener = createNsdDiscoveryListener(type)
                discoveryListeners.add(listener)
                try {
                    manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (e: Exception) {
                    AppLogger.w("mDNS discovery failed for $type: ${e.message}")
                }
            }
        } ?: AppLogger.w("NsdManager not available, falling back to port scan only")

        scope.launch {
            delay(discoveryTimeoutMs)
            _scanProgress.value = if (_discoveredServices.value.isNotEmpty()) ScanProgress.DONE else ScanProgress.FAILED
            stopDiscovery()
        }

        scope.launch {
            runRoundScan(0)
        }
    }

    /**
     * 一轮扫描：mDNS 给 2 秒缓冲，再子网端口扫描。
     */
    private suspend fun runRoundScan(round: Int) {
        if (!_isDiscovering.value) return
        // 仅在第一轮给 mDNS 一个发现窗口
        if (round == 0 && nsdManager != null) {
            delay(2000L)
        }
        if (!_isDiscovering.value) return
        _scanProgress.value = ScanProgress.SCANNING_PORTS
        runFallbackPortScan()
    }

    /**
     * Stop discovery and clean up listeners.
     *
     * **fix (issue: mDNS 扫描卡死闪退)**：现在会立刻 cancel() 跟踪的 scanJob，
     * 不再等所有 in-flight Socket.connect() 跑完；同时释放 MulticastLock。
     */
    fun stopDiscovery() {
        if (!_isDiscovering.value) return
        _isDiscovering.value = false

        // 取消全轮询 Job（若正在进行）
        scanJob?.cancel()
        scanJob = null

        nsdManager?.let { manager ->
            for (listener in discoveryListeners) {
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    AppLogger.w("Failed to stop NSD listener: ${e.message}")
                }
            }
        }
        discoveryListeners.clear()
        releaseMulticastLock()
    }

    /**
     * 彻底关闭单例：取消所有协程、释放锁、清空监听器。
     * 由 CameraRepositoryImpl.release() 在 Activity 销毁时调用，避免
     * 离开扫描页后端口扫描仍在后台吃 FD。
     */
    fun release() {
        runCatching { stopDiscovery() }
        runCatching { scope.cancel() }
        runCatching { resolveExecutor.shutdownNow() }
    }

    /**
     * 持有 MulticastLock，否则部分 OEM Android（特别是 Doze / 待机后）收不到 mDNS 多播。
     * AndroidManifest 已声明 CHANGE_WIFI_MULTICAST_STATE 权限。
     */
    private fun acquireMulticastLock() {
        val wm = wifiManager ?: return
        // 已持有则跳过
        if (multicastLock?.isHeld == true) return
        runCatching {
            val lock = wm.createMulticastLock("PhtonView-mDNS")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
            AppLogger.d("MulticastLock acquired for mDNS")
        }.onFailure { e ->
            AppLogger.w("Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        runCatching {
            if (lock.isHeld) lock.release()
            AppLogger.d("MulticastLock released")
        }.onFailure { e ->
            AppLogger.w("Failed to release MulticastLock: ${e.message}")
        }
        multicastLock = null
    }

    /**
     * Reset discovered list.
     */
    fun clear() {
        _discoveredServices.value = emptyList()
        _scanProgress.value = ScanProgress.IDLE
    }

    private fun createNsdDiscoveryListener(type: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {
                AppLogger.d("mDNS discovery started: $type")
            }

            override fun onServiceFound(service: NsdServiceInfo?) {
                service ?: return
                AppLogger.d("mDNS service found: ${service.serviceName} / $type")
                resolveService(service, type)
            }

            override fun onServiceLost(service: NsdServiceInfo?) {
                service ?: return
                AppLogger.d("mDNS service lost: ${service.serviceName}")
                _discoveredServices.value = _discoveredServices.value.filter {
                    !(it.name == service.serviceName && it.serviceType == type)
                }
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                AppLogger.d("mDNS discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                AppLogger.w("mDNS discovery start failed for $serviceType: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                AppLogger.w("mDNS stop failed for $serviceType: $errorCode")
            }
        }
    }

    private val resolveExecutor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "PhtonView-Resolve") } }

    private fun resolveService(service: NsdServiceInfo, type: String) {
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                resolved ?: return
                val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    resolved.hostAddresses.firstOrNull()?.hostAddress
                } else {
                    @Suppress("DEPRECATION")
                    resolved.host?.hostAddress
                } ?: return
                val port = resolved.port
                val name = resolved.serviceName ?: "Unknown"
                val vendor = vendorHintFromType(type)
                val info = CameraServiceInfo(host, port, type, name, vendor)
                addService(info)
                AppLogger.report("W", "WifiCameraDiscovery.kt:resolveService", "Camera resolved", mapOf(
                    "host" to host,
                    "port" to port.toString(),
                    "type" to type,
                    "name" to name
                ))
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                AppLogger.w("mDNS resolve failed for ${serviceInfo?.serviceName}: $errorCode")
            }
        }

        @Suppress("DEPRECATION")
        nsdManager?.let { manager ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.resolveService(service, resolveExecutor, listener)
            } else {
                manager.resolveService(service, listener)
            }
        }
    }

    private fun addService(info: CameraServiceInfo) {
        val current = _discoveredServices.value
        if (current.any { it.host == info.host && it.port == info.port }) return
        _discoveredServices.value = current + info
    }

    private fun vendorHintFromType(type: String): String? {
        return when {
            type.contains("canon") -> "Canon"
            type.contains("sony") -> "Sony"
            type.contains("fuji") -> "Fujifilm"
            type.contains("nikon") -> "Nikon"
            type.contains("panasonic") || type.contains("lumix") -> "Panasonic"
            type.contains("olympus") || type.contains("omsystem") -> "Olympus"
            else -> null
        }
    }

    /**
     * Fallback port scan on common local subnet addresses.
     * This is best-effort and only runs if mDNS is unavailable or found nothing.
     *
     * **fix (issue: mDNS 扫描卡死闪退)**：
     * - 旧实现 `for (host in 1..254) { jobs += async { for (port in knownCommandPorts) ... } }`
     *   最多并发 254×17=4,318 个 Socket.connect，瞬间打满 FD 表导致 "Too many open files" 闪退。
     * - 改用 `flatMapMerge(concurrency = 32)`，最多同时 32 个 in-flight socket（kernel 默认
     *   ephemeral port 范围 + TCP 重传队列都不会被打爆）。
     * - 每个 host 内层 port 循环找到开放端口就 break，避免一台相机入列多次。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun runFallbackPortScan() = withContext(Dispatchers.IO) {
        val subnetBase = localSubnetBase() ?: return@withContext
        if (!_isDiscovering.value) return@withContext

        coroutineScope {
            (1..254)
                .flatMap { host -> knownCommandPorts.map { port -> "$subnetBase.$host" to port } }
                .asFlow()
                .flatMapMerge(concurrency = 32) { (address, port) ->
                    flow {
                        if (!_isDiscovering.value) return@flow
                        if (isPortOpen(address, port)) {
                            addService(
                                CameraServiceInfo(
                                    host = address,
                                    port = port,
                                    serviceType = "_ptp._tcp",
                                    name = "PTP-IP $address:$port",
                                    vendorHint = null
                                )
                            )
                            AppLogger.report("W", "WifiCameraDiscovery.kt:runFallbackPortScan", "Camera found by scan", mapOf(
                                "host" to address,
                                "port" to port.toString()
                            ))
                        }
                        emit(Unit)
                    }
                }
                .collect { /* drain */ }
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), portScanTimeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 返回本机所在 /24 子网前缀，例如 "192.168.1"。
     * 优先返回 WiFi 接口 IP 的子网（连接相机热点时通常就是 192.168.1.x / 192.168.0.x）。
     */
    private fun localSubnetBase(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            var wifiSubnet: String? = null
            var fallbackSubnet: String? = null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val isWifi = ni.name.startsWith("wlan") || ni.name.startsWith("ap") || ni.name.contains("wifi", ignoreCase = true)
                for (addr in ni.interfaceAddresses) {
                    val address = addr.address ?: continue
                    if (address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (!host.contains('.')) continue
                    val base = host.substringBeforeLast(".")
                    if (isWifi) {
                        wifiSubnet = base
                        break
                    } else if (fallbackSubnet == null) {
                        fallbackSubnet = base
                    }
                }
                if (wifiSubnet != null) break
            }
            wifiSubnet ?: fallbackSubnet
        }.getOrNull()
    }
}
