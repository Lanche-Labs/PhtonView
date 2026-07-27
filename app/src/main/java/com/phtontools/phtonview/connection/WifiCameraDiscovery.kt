package com.phtontools.phtonview.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.phtontools.phtonview.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _discoveredServices = MutableStateFlow<List<CameraServiceInfo>>(emptyList())
    val discoveredServices: StateFlow<List<CameraServiceInfo>> = _discoveredServices

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private val _scanProgress = MutableStateFlow(ScanProgress.IDLE)
    val scanProgress: StateFlow<ScanProgress> = _scanProgress

    private val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()

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
     */
    private val knownCommandPorts = listOf(
        15740, // Generic PTP-IP, Nikon, Sony
        15741, // Event port (some cameras also listen here)
        15742, // Canon WFT / some Fuji
        15743,
        15744,
        15745,
        4759,  // Canon Camera Connect / Image Transfer Utility
        4760,
        4761,
        4757,  // Sony imaging
        4758,
        4755,
        4756,
        49152, // Panasonic / Lumix Sync
        49153,
        80,    // HTTP config fallback
        8080
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
     */
    fun startFullScan(maxRounds: Int = 3) {
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
            repeat(maxRounds) { round ->
                if (!_isDiscovering.value) return@launch
                AppLogger.d("WiFi scan round $round/${maxRounds - 1}")
                _scanProgress.value = if (round == 0) ScanProgress.SCANNING_MDNS else ScanProgress.SCANNING_PORTS
                runRoundScan(round)
                // 一旦发现相机，立刻结束后续轮询
                if (_discoveredServices.value.isNotEmpty()) return@launch
            }
            _scanProgress.value = if (_discoveredServices.value.isNotEmpty()) ScanProgress.DONE else ScanProgress.FAILED
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
     */
    fun stopDiscovery() {
        if (!_isDiscovering.value) return
        _isDiscovering.value = false

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
     */
    private suspend fun runFallbackPortScan() = withContext(Dispatchers.IO) {
        val subnetBase = localSubnetBase() ?: return@withContext
        if (!_isDiscovering.value) return@withContext

        val jobs = mutableListOf<Deferred<Unit>>()
        for (host in 1..254) {
            if (!_isDiscovering.value) break
            val address = "$subnetBase.$host"
            jobs += async {
                for (port in knownCommandPorts) {
                    if (!_isDiscovering.value) return@async
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
                        break
                    }
                }
            }
        }
        jobs.awaitAll()
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
