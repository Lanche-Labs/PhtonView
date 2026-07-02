package com.phtontools.phtonview.connection

import com.phtontools.phtonview.data.model.CameraBrand
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.data.model.PhotoItem
import com.phtontools.phtonview.data.model.StorageTarget
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstract camera connection interface, supports USB / WiFi and multi-brand extension.
 */
interface CameraConnection {

    val brand: CameraBrand
    val connectionType: ConnectionType

    /**
     * Current connection state flow.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Try to connect to the camera.
     */
    suspend fun connect()

    /**
     * Disconnect from the camera.
     */
    fun disconnect()

    /**
     * Release resources.
     */
    fun release()

    /**
     * Open PTP session. Default no-op.
     */
    suspend fun openSession(sessionId: Int = 1): Boolean = false

    /**
     * Close PTP session. Default no-op.
     */
    suspend fun closeSession(): Boolean = false

    /**
     * Send a command with no data phase. Returns response code and parameters.
     */
    suspend fun sendCommand(code: Short, vararg params: Int): Pair<Short, IntArray> {
        return Pair(0.toShort(), IntArray(0))
    }

    /**
     * Send a command and read the data phase. Default returns empty array.
     */
    suspend fun sendCommandWithData(code: Short, vararg params: Int): ByteArray {
        return ByteArray(0)
    }

    /**
     * List photos on the camera storage. Default returns empty list.
     */
    suspend fun listPhotos(folder: String = "/store_00010001"): List<PhotoItem> = emptyList()

    /**
     * Download a photo object. Default returns null.
     */
    suspend fun downloadPhoto(handle: Int): ByteArray? = null

    /**
     * Delete a photo object. Default returns false.
     */
    suspend fun deletePhoto(handle: Int): Boolean = false

    /**
     * Format the specified storage. Default returns false.
     */
    suspend fun formatStorage(target: StorageTarget): Boolean = false

    /**
     * Get device property value. Default returns null.
     */
    suspend fun getDeviceProperty(code: Short): Int? = null

    /**
     * Set device property value. Default returns false.
     */
    suspend fun setDeviceProperty(code: Short, value: Int): Boolean = false

    /**
     * Set device property raw dataset. Default returns false.
     */
    suspend fun setDevicePropertyValue(code: Short, data: ByteArray): Boolean = false

    /**
     * Get device property descriptor raw bytes. Default returns empty array.
     */
    suspend fun getDevicePropertyDesc(code: Short): ByteArray = ByteArray(0)

    /**
     * Get device info string (model / manufacturer). Default returns empty string.
     */
    suspend fun getDeviceInfo(): String = ""

    /**
     * Get the next transaction ID.
     */
    fun nextTransactionId(): Int = 0

    /**
     * Internal connection state representation.
     */
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val model: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
}
