package com.phtontools.phtonview.connection

import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.data.model.CameraBrand
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.data.model.PhotoItem
import com.phtontools.phtonview.data.model.StorageTarget
import com.phtontools.phtonview.data.model.WifiBrandPreset
import com.phtontools.phtonview.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi camera connection using PTP-IP protocol (experimental).
 *
 * Supports connecting to a paired camera over TCP port 15740 (command)
 * and 15741 (event). Data port is negotiated during handshake.
 */
@Singleton
class WifiCameraConnection @Inject constructor(
    private val settingsManager: SettingsManager
) : CameraConnection {

    override val brand: CameraBrand = CameraBrand.Generic
    override val connectionType: ConnectionType = ConnectionType.WiFi

    private val _connectionState = MutableStateFlow<CameraConnection.ConnectionState>(CameraConnection.ConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnection.ConnectionState> = _connectionState

    private var pairedAddress: String? = null
    private var pairedBrand: WifiBrandPreset = WifiBrandPreset.Custom
    private var pairedCommandPort: Int? = null
    private var pairedEventPort: Int? = null
    private var commandSocket: Socket? = null
    private var dataSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var sessionId: Int = 0
    private var transactionIdCounter = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val DEFAULT_COMMAND_PORTS = listOf(15740, 15739, 15741)
        private const val CONNECTION_RETRIES = 3
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    fun pair(address: String, brandPreset: WifiBrandPreset = WifiBrandPreset.Custom) {
        pairedAddress = address
        pairedBrand = brandPreset
        pairedCommandPort = settingsManager.wifiPairedPort
        pairedEventPort = settingsManager.wifiPairedEventPort
        AppLogger.d("WiFi paired with $address (brand=${brandPreset.name}, savedCmdPort=$pairedCommandPort, savedEventPort=$pairedEventPort)")
    }

    override suspend fun connect() {
        AppLogger.d("WiFi connect() called, pairedAddress=$pairedAddress")
        _connectionState.value = CameraConnection.ConnectionState.Connecting
        if (pairedAddress.isNullOrBlank()) {
            _connectionState.value = CameraConnection.ConnectionState.Error("Please pair a WiFi camera first")
            return
        }

        val rawAddress = pairedAddress!!
        // 兼容旧版保存的 "ip:port" 格式，优先拆出 IP 和旧端口。
        val host = rawAddress.substringBeforeLast(":", rawAddress)
        val legacyPort = rawAddress.substringAfterLast(":", "").toIntOrNull()

        // 端口候选列表：上次成功端口 > 旧地址中的端口 > 品牌候选端口。
        val candidatePorts = buildSet {
            settingsManager.wifiPairedPort?.let { add(it) }
            legacyPort?.let { add(it) }
            addAll(pairedBrand.candidatePorts)
            addAll(DEFAULT_COMMAND_PORTS)
        }.toList()

        var lastError: Throwable? = null
        var connectedPort: Int? = null
        val triedPorts = mutableListOf<Int>()
        for (attempt in 1..CONNECTION_RETRIES) {
            val delayMs = (200L * (attempt - 1)).coerceAtMost(1500L)
            if (delayMs > 0) delay(delayMs)
            for (port in candidatePorts) {
                if (port in triedPorts) continue
                triedPorts.add(port)
                try {
                    AppLogger.d("WiFi connection attempt $attempt/$CONNECTION_RETRIES to $host:$port")
                    doConnect(host, port)
                    connectedPort = port
                    _connectionState.value = CameraConnection.ConnectionState.Connected("WiFi Camera")
                    break
                } catch (e: Exception) {
                    lastError = e
                    AppLogger.w("WiFi connection failed to $host:$port on attempt $attempt: ${e.message}")
                    releaseSockets()
                }
            }
            if (connectedPort != null) break
        }

        if (connectedPort != null) {
            pairedCommandPort = connectedPort
            // 保存本次成功连接的命令端口，下次优先使用。
            if (settingsManager.wifiPairedPort != connectedPort) {
                settingsManager.wifiPairedPort = connectedPort
                AppLogger.d("WiFi connected on command port $connectedPort, port saved")
            }
            // 若旧地址包含端口，迁移为纯 IP 格式。
            if (rawAddress.contains(':')) {
                pairedAddress = host
                settingsManager.wifiPairedAddress = host
            }
            AppLogger.report("W", "WifiCameraConnection.kt:connect", "WiFi connected", mapOf(
                "host" to host,
                "commandPort" to connectedPort.toString(),
                "eventPort" to (pairedEventPort?.toString() ?: "none"),
                "triedPorts" to triedPorts.joinToString(",")
            ))
            return
        }

        AppLogger.e("WiFi connection failed after $CONNECTION_RETRIES retries", lastError)
        val errorDetail = lastError?.toString() ?: "unknown error"
        _connectionState.value = CameraConnection.ConnectionState.Error(
            "WiFi connection failed (tried ports: ${triedPorts.joinToString(", ")}): $errorDetail"
        )
    }

    private suspend fun doConnect(host: String, port: Int) {
        commandSocket = Socket().apply { connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS) }

        val guidBytes = uuidToBytes(UUID.randomUUID())
        val nameBytes = "PhtonView".toByteArray(Charsets.UTF_16LE)
        val version = byteArrayOf(1, 0) // PTP-IP version 1.0

        val request = ByteArrayOutputStream()
        val payloadLength = 4 + guidBytes.size + 4 + nameBytes.size + version.size
        request.write(intToBytes(payloadLength + 4)) // total length
        request.write(intToBytes(1)) // InitCommandRequest packet type
        request.write(guidBytes)
        request.write(intToBytes(nameBytes.size))
        request.write(nameBytes)
        request.write(version)

        commandSocket!!.getOutputStream().write(request.toByteArray())
        commandSocket!!.getOutputStream().flush()

        val ackHeader = readAtLeast(commandSocket!!.getInputStream(), 8)
        val ackLength = bytesToInt(ackHeader, 0)
        val ackType = bytesToInt(ackHeader, 4)
        if (ackType != 2) { // InitCommandAck
            throw IllegalStateException("Unexpected PTP-IP handshake response type: $ackType")
        }
        val ackPayload = readAtLeast(commandSocket!!.getInputStream(), ackLength - 8)
        sessionId = bytesToInt(ackPayload, 0)
        AppLogger.d("PTP-IP session ID: $sessionId")

        // Open event connection, try common event ports. Prefer the previously
        // saved event port, then commandPort+1, then the common fallback list.
        val savedEventPort = pairedEventPort ?: settingsManager.wifiPairedEventPort
        val eventPortsToTry = buildSet {
            savedEventPort?.let { add(it) }
            add(port + 1)
            add(15741)
            add(15740)
        }.toList()
        var eventConnected = false
        var connectedEventPort: Int? = null
        for (eventPort in eventPortsToTry) {
            try {
                eventSocket = Socket().apply { connect(InetSocketAddress(host, eventPort), SOCKET_TIMEOUT_MS) }
                val eventRequest = ByteArrayOutputStream()
                val eventPayload = 4 + guidBytes.size + 4 + nameBytes.size + 4
                eventRequest.write(intToBytes(eventPayload + 4))
                eventRequest.write(intToBytes(3)) // InitEventRequest
                eventRequest.write(guidBytes)
                eventRequest.write(intToBytes(nameBytes.size))
                eventRequest.write(nameBytes)
                eventRequest.write(intToBytes(sessionId))
                eventSocket!!.getOutputStream().write(eventRequest.toByteArray())
                eventSocket!!.getOutputStream().flush()
                AppLogger.d("PTP-IP event connection established on port $eventPort")
                eventConnected = true
                connectedEventPort = eventPort
                break
            } catch (e: Exception) {
                AppLogger.w("PTP-IP event port $eventPort failed: ${e.message}")
            }
        }
        if (eventConnected && connectedEventPort != null) {
            pairedEventPort = connectedEventPort
            if (settingsManager.wifiPairedEventPort != connectedEventPort) {
                settingsManager.wifiPairedEventPort = connectedEventPort
                AppLogger.d("WiFi event port $connectedEventPort saved")
            }
        } else {
            AppLogger.w("PTP-IP event connection could not be established, continuing without events")
        }
    }

    override fun disconnect() {
        AppLogger.d("WiFi disconnect() called")
        releaseSockets()
        _connectionState.value = CameraConnection.ConnectionState.Disconnected
    }

    override fun release() {
        disconnect()
        scope.cancel()
    }

    override suspend fun openSession(sessionId: Int): Boolean {
        return sendPtpCommand(0x1002, intArrayOf(sessionId))
    }

    override suspend fun closeSession(): Boolean {
        return sendPtpCommand(0x1003)
    }

    override suspend fun sendCommand(code: Short, vararg params: Int): Pair<Short, IntArray> {
        val success = sendPtpCommand(code.toInt(), params)
        return Pair(if (success) 0x2001.toShort() else 0x2002.toShort(), IntArray(0))
    }

    override suspend fun sendCommandWithData(code: Short, vararg params: Int): ByteArray {
        // Simplified: open data connection on demand is not implemented in this basic bridge.
        AppLogger.d("sendCommandWithData not fully implemented for WiFi")
        return ByteArray(0)
    }

    override fun nextTransactionId(): Int = transactionIdCounter++

    override suspend fun listPhotos(folder: String): List<PhotoItem> {
        AppLogger.d("listPhotos not fully implemented for WiFi")
        return emptyList()
    }

    override suspend fun downloadPhoto(handle: Int): ByteArray? {
        AppLogger.d("downloadPhoto not fully implemented for WiFi")
        return null
    }

    override suspend fun deletePhoto(handle: Int): Boolean {
        AppLogger.d("deletePhoto not fully implemented for WiFi")
        return false
    }

    override suspend fun formatStorage(target: StorageTarget): Boolean {
        AppLogger.d("formatStorage not fully implemented for WiFi")
        return false
    }

    private suspend fun sendPtpCommand(code: Int, params: IntArray = intArrayOf()): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = commandSocket ?: return@withContext false
            val tid = nextTransactionId()
            val packet = ByteArrayOutputStream()
            val payloadLength = 4 + 2 + 4 + params.size * 4
            packet.write(intToBytes(payloadLength + 4))
            packet.write(intToBytes(6)) // OperationRequest packet type
            packet.write(intToBytes(tid))
            packet.write(shortToBytes(code.toShort()))
            params.forEach { packet.write(intToBytes(it)) }
            socket.getOutputStream().write(packet.toByteArray())
            socket.getOutputStream().flush()
            true
        } catch (e: Exception) {
            AppLogger.e("WiFi PTP command failed", e)
            false
        }
    }

    private fun releaseSockets() {
        try { commandSocket?.close() } catch (_: Exception) { }
        try { dataSocket?.close() } catch (_: Exception) { }
        try { eventSocket?.close() } catch (_: Exception) { }
        commandSocket = null
        dataSocket = null
        eventSocket = null
        sessionId = 0
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.wrap(ByteArray(16))
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToBytes(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    private fun bytesToInt(bytes: ByteArray, offset: Int): Int {
        val buffer = ByteBuffer.wrap(bytes, offset, 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return buffer.int
    }

    private fun readAtLeast(stream: java.io.InputStream, minBytes: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (result.size() < minBytes) {
            val read = stream.read(buffer, 0, minOf(buffer.size, minBytes - result.size()))
            if (read < 0) break
            result.write(buffer, 0, read)
        }
        return result.toByteArray()
    }
}
