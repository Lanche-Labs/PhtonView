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
    private val portScanTimeoutMs = 800

    data class CameraServiceInfo(
        val host: String,
        val port: Int,
        val serviceType: String,
        val name: String,
        val vendorHint: String?
    )

    /**
     * Start discovery. Discovered services are emitted on [discoveredServices].
     */
    fun startDiscovery() {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
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
            stopDiscovery()
        }

        scope.launch {
            fallbackPortScan()
        }
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
                AppLogger.w("mDNS discovery stop failed for $serviceType: $errorCode")
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
    private suspend fun fallbackPortScan() = withContext(Dispatchers.IO) {
        if (nsdManager != null) {
            // Give mDNS a head start before scanning.
            delay(2000L)
        }
        if (!_isDiscovering.value) return@withContext

        val gateway = localGateway() ?: return@withContext
        val base = gateway.substringBeforeLast(".")
        val jobs = mutableListOf<Deferred<Unit>>()
        for (host in 2..254) {
            if (!_isDiscovering.value) break
            val address = "$base.$host"
            jobs += async {
                for (port in knownCommandPorts) {
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
                        AppLogger.report("W", "WifiCameraDiscovery.kt:fallbackPortScan", "Camera found by scan", mapOf(
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
     * Best-effort local gateway detection. Returns something like "192.168.1.1".
     */
    private fun localGateway(): String? {
        return runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.interfaceAddresses) {
                    val address = addr.address ?: continue
                    if (address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (host.contains('.')) {
                        return@runCatching host
                    }
                }
            }
            null
        }.getOrNull()
    }
}
