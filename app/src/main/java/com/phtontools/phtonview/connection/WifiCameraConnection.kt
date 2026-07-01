package com.phtontools.phtonview.connection

import com.phtontools.phtonview.data.model.CameraBrand
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.data.model.PhotoItem
import com.phtontools.phtonview.data.model.StorageTarget
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
class WifiCameraConnection @Inject constructor() : CameraConnection {

    override val brand: CameraBrand = CameraBrand.Generic
    override val connectionType: ConnectionType = ConnectionType.WiFi

    private val _connectionState = MutableStateFlow<CameraConnection.ConnectionState>(CameraConnection.ConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnection.ConnectionState> = _connectionState

    private var pairedAddress: String? = null
    private var commandSocket: Socket? = null
    private var dataSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var sessionId: Int = 0
    private var transactionIdCounter = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pair(address: String) {
        pairedAddress = address
        AppLogger.d("WiFi paired with $address")
    }

    override suspend fun connect() {
        AppLogger.d("WiFi connect() called, pairedAddress=$pairedAddress")
        _connectionState.value = CameraConnection.ConnectionState.Connecting
        if (pairedAddress.isNullOrBlank()) {
            _connectionState.value = CameraConnection.ConnectionState.Error("Please pair a WiFi camera first")
            return
        }

        try {
            val address = pairedAddress!!
            val host = address.substringBeforeLast(":", address)
            val port = address.substringAfterLast(":", "15740").toIntOrNull() ?: 15740

            commandSocket = Socket().apply { connect(InetSocketAddress(host, port), 5000) }

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

            // Open event connection
            eventSocket = Socket().apply { connect(InetSocketAddress(host, 15741), 5000) }
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

            _connectionState.value = CameraConnection.ConnectionState.Connected("WiFi Camera")
        } catch (e: Exception) {
            AppLogger.e("WiFi connection failed", e)
            releaseSockets()
            _connectionState.value = CameraConnection.ConnectionState.Error("WiFi connection failed: ${e.message}")
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
