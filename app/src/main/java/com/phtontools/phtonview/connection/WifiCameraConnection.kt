package com.phtontools.phtonview.connection

import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.data.model.CameraBrand
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.data.model.PhotoItem
import com.phtontools.phtonview.data.model.StorageTarget
import com.phtontools.phtonview.data.model.WifiBrandPreset
import com.phtontools.phtonview.usb.ptp.ObjectInfo
import com.phtontools.phtonview.usb.ptp.PtpCommand
import com.phtontools.phtonview.usb.ptp.PtpConstants
import com.phtontools.phtonview.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi camera connection using PTP-IP protocol.
 *
 * 支持命令/事件/数据三通道，能够完成实时取景取图、照片列表、下载等
 * 标准 PTP 数据阶段操作。命令端口由应用主动遍历品牌候选端口。
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
    private var eventSocket: Socket? = null
    private var sessionId: Int = 0
    private var transactionIdCounter = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventReaderJob: Job? = null

    companion object {
        private val DEFAULT_COMMAND_PORTS = listOf(15740, 15739, 15741)
        private const val CONNECTION_RETRIES = 3
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val DATA_SOCKET_TIMEOUT_MS = 10000

        // PTP-IP packet types
        private const val PKT_INIT_COMMAND_REQUEST = 1
        private const val PKT_INIT_COMMAND_ACK = 2
        private const val PKT_INIT_EVENT_REQUEST = 3
        private const val PKT_INIT_EVENT_ACK = 4
        private const val PKT_OPERATION_REQUEST = 6
        private const val PKT_RESPONSE = 7
        private const val PKT_EVENT = 8
        private const val PKT_START_DATA = 9
        private const val PKT_DATA = 10
        private const val PKT_END_DATA = 12
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
        commandSocket = Socket().apply {
            connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            soTimeout = SOCKET_TIMEOUT_MS
        }

        val guidBytes = uuidToBytes(UUID.randomUUID())
        val nameBytes = "PhtonView".toByteArray(Charsets.UTF_16LE)
        val version = byteArrayOf(1, 0) // PTP-IP version 1.0

        val request = ByteArrayOutputStream()
        val payloadLength = 4 + guidBytes.size + 4 + nameBytes.size + version.size
        request.write(intToBytes(payloadLength + 4)) // total length
        request.write(intToBytes(PKT_INIT_COMMAND_REQUEST))
        request.write(guidBytes)
        request.write(intToBytes(nameBytes.size))
        request.write(nameBytes)
        request.write(version)

        commandSocket!!.getOutputStream().write(request.toByteArray())
        commandSocket!!.getOutputStream().flush()

        val ackHeader = readAtLeast(commandSocket!!.getInputStream(), 8)
        val ackLength = bytesToInt(ackHeader, 0)
        val ackType = bytesToInt(ackHeader, 4)
        if (ackType != PKT_INIT_COMMAND_ACK) {
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
                eventSocket = Socket().apply {
                    connect(InetSocketAddress(host, eventPort), SOCKET_TIMEOUT_MS)
                    soTimeout = SOCKET_TIMEOUT_MS
                }
                val eventRequest = ByteArrayOutputStream()
                val eventPayload = 4 + guidBytes.size + 4 + nameBytes.size + 4
                eventRequest.write(intToBytes(eventPayload + 4))
                eventRequest.write(intToBytes(PKT_INIT_EVENT_REQUEST))
                eventRequest.write(guidBytes)
                eventRequest.write(intToBytes(nameBytes.size))
                eventRequest.write(nameBytes)
                eventRequest.write(intToBytes(sessionId))
                eventSocket!!.getOutputStream().write(eventRequest.toByteArray())
                eventSocket!!.getOutputStream().flush()

                // Read InitEventAck (type 4)
                val eventAck = readPtpIpPacket(eventSocket!!)
                if (eventAck.first == PKT_INIT_EVENT_ACK) {
                    AppLogger.d("PTP-IP event connection established on port $eventPort")
                    eventConnected = true
                    connectedEventPort = eventPort
                    startEventReader()
                    break
                }
            } catch (e: Exception) {
                AppLogger.w("PTP-IP event port $eventPort failed: ${e.message}")
                try { eventSocket?.close() } catch (_: Exception) { }
                eventSocket = null
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

        // 打开 PTP 会话
        if (!openSession(PtpConstants.DEFAULT_SESSION_ID)) {
            throw IllegalStateException("PTP-IP OpenSession failed")
        }
    }

    override fun disconnect() {
        AppLogger.d("WiFi disconnect() called")
        eventReaderJob?.cancel()
        eventReaderJob = null
        runBlocking {
            runCatching { closeSession() }
        }
        releaseSockets()
        _connectionState.value = CameraConnection.ConnectionState.Disconnected
    }

    override fun release() {
        disconnect()
        scope.cancel()
    }

    override suspend fun openSession(sessionId: Int): Boolean {
        val (code, _) = sendPtpCommand(PtpConstants.OPERATION_OPEN_SESSION.toInt(), intArrayOf(sessionId))
        return code == PtpConstants.RESPONSE_OK || code == PtpConstants.RESPONSE_SESSION_NOT_OPEN
    }

    override suspend fun closeSession(): Boolean {
        val (code, _) = sendPtpCommand(PtpConstants.OPERATION_CLOSE_SESSION.toInt())
        return code == PtpConstants.RESPONSE_OK
    }

    override suspend fun sendCommand(code: Short, vararg params: Int): Pair<Short, IntArray> {
        return sendPtpCommand(code.toInt(), params)
    }

    override suspend fun sendCommandWithData(code: Short, vararg params: Int): ByteArray {
        return sendPtpCommandWithData(code.toInt(), params)
    }

    override fun nextTransactionId(): Int = transactionIdCounter++

    override suspend fun listPhotos(folder: String): List<PhotoItem> {
        if (!ensureSession()) {
            AppLogger.report("C", "WifiCameraConnection.kt:listPhotos", "No session", emptyMap())
            return emptyList()
        }

        val items = mutableListOf<PhotoItem>()
        val storageIds = runCatching {
            val data = sendCommandWithData(PtpConstants.OPERATION_GET_STORAGE_IDS)
            PtpCommand.decodeIntArray(data)
        }.getOrDefault(IntArray(0))

        val idsToTry = if (storageIds.isEmpty()) {
            intArrayOf(0x00010001, 0x00020001, 0x00010002, 0xFFFFFFFF.toInt())
        } else storageIds

        for (storageId in idsToTry) {
            val handles = runCatching {
                sendCommandWithData(
                    PtpConstants.OPERATION_GET_OBJECT_HANDLES,
                    storageId, 0, 0xFFFFFFFF.toInt()
                ).let { PtpCommand.decodeIntArray(it) }
            }.getOrDefault(IntArray(0))

            for (handle in handles) {
                val info = runCatching { getObjectInfo(handle) }.getOrNull() ?: continue
                if (info.objectFormat == PtpConstants.OBJECT_FORMAT_ASSOCIATION || info.filename.isBlank()) continue
                if (!isImageOrVideoFormat(info.objectFormat)) continue
                items.add(
                    PhotoItem(
                        id = handle.toString(),
                        name = info.filename,
                        path = "$folder/${info.filename}",
                        sizeBytes = info.compressedSize,
                        width = info.imagePixWidth,
                        height = info.imagePixHeight,
                        isInCamera = true
                    )
                )
            }
            if (items.isNotEmpty()) break
        }

        items.sortByDescending { unsignedInt(it.id.toIntOrNull() ?: 0) }
        AppLogger.report("C", "WifiCameraConnection.kt:listPhotos", "Result", mapOf("count" to items.size.toString()))
        return items
    }

    override suspend fun downloadPhoto(handle: Int): ByteArray? {
        if (!ensureSession()) return null
        return try {
            commandSocket?.soTimeout = DATA_SOCKET_TIMEOUT_MS
            val data = sendCommandWithData(
                PtpConstants.OPERATION_GET_OBJECT,
                handle
            )
            commandSocket?.soTimeout = SOCKET_TIMEOUT_MS
            val payload = PtpCommand.decodeDataPayload(data)
            AppLogger.report("C", "WifiCameraConnection.kt:downloadPhoto", "Downloaded", mapOf("handle" to String.format(Locale.US, "0x%08X", handle), "bytes" to payload.size.toString()))
            payload.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            AppLogger.report("C", "WifiCameraConnection.kt:downloadPhoto", "Download failed", mapOf("handle" to String.format(Locale.US, "0x%08X", handle), "error" to (e.message ?: "unknown")))
            null
        }
    }

    override suspend fun deletePhoto(handle: Int): Boolean {
        if (!ensureSession()) return false
        val (code, _) = sendCommand(PtpConstants.OPERATION_DELETE_OBJECT, handle)
        return code == PtpConstants.RESPONSE_OK
    }

    override suspend fun formatStorage(target: StorageTarget): Boolean {
        // Not implemented for WiFi
        return false
    }

    override suspend fun getDeviceInfo(): String {
        return try {
            val data = sendCommandWithData(PtpConstants.OPERATION_GET_DEVICE_INFO)
            PtpCommand.decodeDeviceInfoModel(data)
        } catch (e: Exception) {
            "WiFi Camera"
        }
    }

    override suspend fun getDeviceInfoRaw(): ByteArray {
        return try {
            sendCommandWithData(PtpConstants.OPERATION_GET_DEVICE_INFO)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    override suspend fun getDeviceProperty(code: Short): Int? {
        return try {
            val data = sendCommandWithData(PtpConstants.OPERATION_DEVICE_PROP_VALUE_GET, code.toInt())
            val payload = PtpCommand.decodeDataPayload(data)
            if (payload.isEmpty()) return null
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            when (payload.size) {
                1 -> buffer.get().toInt() and 0xFF
                2 -> buffer.getShort().toInt() and 0xFFFF
                4 -> buffer.getInt()
                else -> buffer.getInt()
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun setDeviceProperty(code: Short, value: Int): Boolean {
        val width = when (code.toInt() and 0xFFFF) {
            0x5001 -> 1
            0x5005, 0x5007, 0x500B, 0x500E, 0x500F, 0x5010, 0x501A, 0x501C -> 2
            else -> 4
        }
        val bb = ByteBuffer.allocate(width).order(ByteOrder.LITTLE_ENDIAN)
        when (width) {
            1 -> bb.put(value.toByte())
            2 -> bb.putShort(value.toShort())
            4 -> bb.putInt(value)
        }
        return setDevicePropertyValue(code, bb.array())
    }

    override suspend fun setDevicePropertyValue(code: Short, data: ByteArray): Boolean {
        if (!ensureSession()) return false
        return try {
            val (responseCode, _) = sendSetPropertyCommand(code, data)
            responseCode == PtpConstants.RESPONSE_OK
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getDevicePropertyDesc(code: Short): ByteArray {
        return try {
            val data = sendCommandWithData(PtpConstants.OPERATION_DEVICE_PROP_DESC, code.toInt())
            PtpCommand.decodeDataPayload(data)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private suspend fun sendSetPropertyCommand(code: Short, data: ByteArray): Pair<Short, IntArray> = withContext(Dispatchers.IO) {
        val socket = commandSocket ?: throw IllegalStateException("WiFi not connected")
        val tid = nextTransactionId()

        // Command phase
        val commandBytes = buildOperationRequest(PtpConstants.OPERATION_DEVICE_PROP_VALUE_SET, tid, intArrayOf(code.toInt()))
        socket.getOutputStream().write(commandBytes)
        socket.getOutputStream().flush()

        // Data phase container
        val dataContainer = ByteArrayOutputStream()
        dataContainer.write(intToBytes(12 + data.size))
        dataContainer.write(intToBytes(PKT_DATA))
        dataContainer.write(intToBytes(tid))
        dataContainer.write(data)
        socket.getOutputStream().write(dataContainer.toByteArray())
        socket.getOutputStream().flush()

        // EndData
        val endData = ByteArrayOutputStream()
        endData.write(intToBytes(12))
        endData.write(intToBytes(PKT_END_DATA))
        endData.write(intToBytes(tid))
        socket.getOutputStream().write(endData.toByteArray())
        socket.getOutputStream().flush()

        val (type, payload) = readPtpIpPacket(socket)
        if (type != PKT_RESPONSE) {
            throw IllegalStateException("Expected response after set property, got type $type")
        }
        decodeResponsePayload(payload)
    }

    private suspend fun getObjectInfo(handle: Int): ObjectInfo? {
        val data = sendCommandWithData(PtpConstants.OPERATION_GET_OBJECT_INFO, handle)
        return PtpCommand.decodeObjectInfo(data)
    }

    private fun isImageOrVideoFormat(format: Short): Boolean {
        val code = format.toInt() and 0xFFFF
        return code in 0x3800..0x38FF || code in 0x3000..0x30FF
    }

    private fun unsignedInt(value: Int): Long = value.toLong() and 0xFFFFFFFFL

    private suspend fun ensureSession(): Boolean {
        if (sessionId != 0) return true
        return openSession(PtpConstants.DEFAULT_SESSION_ID)
    }

    private suspend fun sendPtpCommand(code: Int, params: IntArray = intArrayOf()): Pair<Short, IntArray> = withContext(Dispatchers.IO) {
        val socket = commandSocket ?: return@withContext PtpConstants.RESPONSE_GENERAL_ERROR to IntArray(0)
        val tid = nextTransactionId()
        val packet = buildOperationRequest(code.toShort(), tid, params)
        socket.getOutputStream().write(packet)
        socket.getOutputStream().flush()

        val (type, payload) = readPtpIpPacket(socket)
        if (type != PKT_RESPONSE) {
            AppLogger.w("PTP-IP unexpected response type $type for op 0x${String.format(Locale.US, "%04X", code)}")
            return@withContext PtpConstants.RESPONSE_GENERAL_ERROR to IntArray(0)
        }
        decodeResponsePayload(payload)
    }

    private suspend fun sendPtpCommandWithData(code: Int, params: IntArray = intArrayOf()): ByteArray = withContext(Dispatchers.IO) {
        val socket = commandSocket ?: throw IllegalStateException("WiFi not connected")
        val tid = nextTransactionId()
        val packet = buildOperationRequest(code.toShort(), tid, params)
        socket.getOutputStream().write(packet)
        socket.getOutputStream().flush()

        val result = ByteArrayOutputStream()
        var responseCode: Short
        while (true) {
            val (type, payload) = readPtpIpPacket(socket)
            when (type) {
                PKT_RESPONSE -> {
                    responseCode = decodeResponsePayload(payload).first
                    break
                }
                PKT_START_DATA -> {
                    // payload: tid(4) + total data length(8); consumed to keep socket in sync
                }
                PKT_DATA, PKT_END_DATA -> {
                    // payload: tid(4) + data
                    if (payload.size > 4) {
                        result.write(payload, 4, payload.size - 4)
                    }
                    if (type == PKT_END_DATA) {
                        // Response follows EndData in most implementations
                    }
                }
                else -> {
                    AppLogger.w("PTP-IP unexpected packet type $type during data phase")
                }
            }
        }

        if (responseCode != PtpConstants.RESPONSE_OK && responseCode.toInt() != -1) {
            AppLogger.report("W", "WifiCameraConnection.kt:sendPtpCommandWithData", "Non-OK response", mapOf(
                "op" to String.format(Locale.US, "0x%04X", code),
                "responseCode" to String.format(Locale.US, "0x%04X", responseCode)
            ))
        }
        result.toByteArray()
    }

    private fun buildOperationRequest(code: Short, tid: Int, params: IntArray): ByteArray {
        val packet = ByteArrayOutputStream()
        val payloadLength = 4 + 2 + 4 + params.size * 4
        packet.write(intToBytes(payloadLength + 4))
        packet.write(intToBytes(PKT_OPERATION_REQUEST))
        packet.write(intToBytes(tid))
        packet.write(shortToBytes(code))
        params.forEach { packet.write(intToBytes(it)) }
        return packet.toByteArray()
    }

    private fun decodeResponsePayload(payload: ByteArray): Pair<Short, IntArray> {
        if (payload.size < 2) return PtpConstants.RESPONSE_GENERAL_ERROR to IntArray(0)
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val responseCode = bb.getShort()
        bb.getInt() // transaction id, already matched by socket order
        val params = mutableListOf<Int>()
        while (bb.remaining() >= 4) {
            params.add(bb.getInt())
        }
        return responseCode to params.toIntArray()
    }

    /**
     * 读取一个完整 PTP-IP 包，返回 (packetType, payload)。
     * payload 不包含 8 字节头部。
     */
    private fun readPtpIpPacket(socket: Socket): Pair<Int, ByteArray> {
        val stream = socket.getInputStream()
        val header = readAtLeast(stream, 8)
        val length = bytesToInt(header, 0)
        val type = bytesToInt(header, 4)
        val payloadSize = (length - 8).coerceAtLeast(0)
        val payload = if (payloadSize > 0) readAtLeast(stream, payloadSize) else ByteArray(0)
        return type to payload
    }

    private fun startEventReader() {
        eventReaderJob?.cancel()
        val socket = eventSocket ?: return
        eventReaderJob = scope.launch {
            while (isActive) {
                try {
                    val (type, payload) = readPtpIpPacket(socket)
                    if (type == PKT_EVENT) {
                        AppLogger.report("W", "WifiCameraConnection.kt:startEventReader", "PTP-IP event", mapOf("bytes" to payload.size.toString()))
                    }
                } catch (e: Exception) {
                    if (isActive) AppLogger.w("PTP-IP event reader error: ${e.message}")
                    break
                }
            }
        }
    }

    private fun releaseSockets() {
        eventReaderJob?.cancel()
        eventReaderJob = null
        try { commandSocket?.close() } catch (_: Exception) { }
        try { eventSocket?.close() } catch (_: Exception) { }
        commandSocket = null
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
        val buffer = ByteArray(8192)
        while (result.size() < minBytes) {
            val read = stream.read(buffer, 0, minOf(buffer.size, minBytes - result.size()))
            if (read < 0) break
            result.write(buffer, 0, read)
        }
        return result.toByteArray()
    }
}
