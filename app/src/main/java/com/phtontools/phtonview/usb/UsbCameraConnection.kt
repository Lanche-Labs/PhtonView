package com.phtontools.phtonview.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import com.phtontools.phtonview.connection.CameraConnection
import com.phtontools.phtonview.data.model.CameraBrand
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.data.model.PhotoItem
import com.phtontools.phtonview.data.model.StorageTarget
import com.phtontools.phtonview.usb.ptp.ObjectInfo
import com.phtontools.phtonview.usb.ptp.PtpCommand
import com.phtontools.phtonview.usb.ptp.PtpConstants
import com.phtontools.phtonview.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * USB camera connection using standard PTP / MTP protocol.
 * Supports multiple vendors via vendor ID list.
 */
@Singleton
class UsbCameraConnection @Inject constructor(
    @ApplicationContext private val context: Context
) : CameraConnection {

    override val brand: CameraBrand = CameraBrand.Generic
    override val connectionType: ConnectionType = ConnectionType.USB

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var cameraDevice: UsbDevice? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _permissionState = MutableStateFlow(false)
    val permissionState: StateFlow<Boolean> = _permissionState

    private val _detectedDevice = MutableStateFlow<String?>(null)
    val detectedDevice: StateFlow<String?> = _detectedDevice

    private val _connectionState = MutableStateFlow<CameraConnection.ConnectionState>(CameraConnection.ConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnection.ConnectionState> = _connectionState

    private var transactionId = 1
    private var sessionOpen = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        AppLogger.d("USB permission result: granted=$granted, device=$device")
                        if (device != null && granted) {
                            _permissionState.value = true
                            openDevice(device)
                        } else {
                            _permissionState.value = false
                            _connectionState.value = CameraConnection.ConnectionState.Error("USB permission denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    AppLogger.d("USB device attached: $device")
                    device?.let {
                        if (isKnownCamera(it)) {
                            _detectedDevice.value = it.productName ?: "Camera"
                            if (usbManager.hasPermission(it)) {
                                _permissionState.value = true
                                openDevice(it)
                            } else {
                                requestPermission(it)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    AppLogger.d("USB device detached: $device")
                    if (device != null && isKnownCamera(device)) {
                        _detectedDevice.value = null
                    }
                    if (device?.deviceId == cameraDevice?.deviceId) {
                        disconnect()
                    }
                }
            }
        }
    }

    init {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            findAndOpenExistingCamera()
        } catch (e: Exception) {
            AppLogger.e("UsbCameraConnection init failed", e)
        }
    }

    /**
     * At startup, look for an already-attached camera that we already have
     * permission for and open it immediately. If no permission yet, fall back
     * to requesting it so the permission dialog can appear.
     */
    private fun findAndOpenExistingCamera() {
        val deviceList = usbManager.deviceList.values
        AppLogger.d("Startup USB scan, found: ${deviceList.map { "${it.vendorId}/${it.productId}" }}")
        val camera = deviceList.find { isKnownCamera(it) }
        if (camera != null) {
            AppLogger.d("Startup found camera: ${camera.productName} (${camera.vendorId}/${camera.productId})")
            _detectedDevice.value = camera.productName ?: "Camera"
            if (usbManager.hasPermission(camera)) {
                _permissionState.value = true
                openDevice(camera)
            } else {
                requestPermission(camera)
            }
        } else {
            AppLogger.d("No supported camera at startup")
            _detectedDevice.value = null
        }
    }

    private fun isKnownCamera(device: UsbDevice): Boolean {
        return SUPPORTED_VENDOR_IDS.contains(device.vendorId)
    }

    /**
     * Find any supported camera and request permission.
     */
    fun findAndRequestCamera(): UsbDevice? {
        val deviceList = usbManager.deviceList.values
        AppLogger.d("Scanning USB devices, found: ${deviceList.map { "${it.vendorId}/${it.productId}" }}")
        val camera = deviceList.find { isKnownCamera(it) }
        if (camera != null) {
            AppLogger.d("Found camera: ${camera.productName} (${camera.vendorId}/${camera.productId})")
            _detectedDevice.value = camera.productName ?: "Camera"
            requestPermission(camera)
        } else {
            AppLogger.d("No supported camera detected")
            _detectedDevice.value = null
        }
        return camera
    }

    /**
     * Re-scan USB devices with retries. Useful when the device was already
     * connected before the app started and the first enumeration missed it.
     */
    suspend fun rescanUsbDevices(): UsbDevice? {
        return findAndRequestCameraWithRetry()
    }

    private suspend fun findAndRequestCameraWithRetry(retryCount: Int = 12, delayMs: Long = 500): UsbDevice? {
        repeat(retryCount) { attempt ->
            val deviceList = usbManager.deviceList.values
            AppLogger.d("Scanning USB devices (attempt ${attempt + 1}/$retryCount), found: ${deviceList.map { "${it.vendorId}/${it.productId}" }}")
            val camera = deviceList.find { isKnownCamera(it) }
            if (camera != null) {
                AppLogger.d("Found camera: ${camera.productName} (${camera.vendorId}/${camera.productId})")
                _detectedDevice.value = camera.productName ?: "Camera"
                if (usbManager.hasPermission(camera)) {
                    _permissionState.value = true
                    openDevice(camera)
                } else {
                    requestPermission(camera)
                }
                return camera
            }
            if (attempt < retryCount - 1) delay(delayMs)
        }
        AppLogger.d("No supported camera detected after $retryCount attempts")
        return null
    }

    /**
     * Request USB permission.
     */
    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            AppLogger.d("Already has USB permission for ${device.productName}")
            _permissionState.value = true
            openDevice(device)
            return
        }
        AppLogger.d("Requesting USB permission for ${device.productName}")
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    override suspend fun connect() {
        AppLogger.d("connect() called")
        // Avoid resetting back to Connecting if we are already connected.
        if (_connectionState.value !is CameraConnection.ConnectionState.Connected) {
            _connectionState.value = CameraConnection.ConnectionState.Connecting
        }
        try {
            val device = findAndRequestCameraWithRetry()
            if (device == null) {
                // The camera might not be enumerated yet (common when the app
                // starts while the camera is already plugged in). Keep the state
                // as Connecting so that the USB attached/permission broadcasts
                // can finish the connection instead of showing an error.
                AppLogger.d("No camera found during connect(); waiting for USB broadcast")
            }
        } catch (e: Exception) {
            AppLogger.e("USB connect failed", e)
            _connectionState.value = CameraConnection.ConnectionState.Error("USB connect failed: ${e.message}")
        }
    }

    /**
     * Open the USB device and initialize bulk endpoints.
     */
    private fun openDevice(device: UsbDevice) {
        AppLogger.d("Opening USB device: ${device.productName}")
        if (connection != null && cameraDevice?.deviceId == device.deviceId) return

        val newConnection = usbManager.openDevice(device) ?: run {
            AppLogger.e("Failed to open USB device")
            _connectionState.value = CameraConnection.ConnectionState.Error("Failed to open USB device")
            return
        }
        val iface = device.getInterface(0)
        newConnection.claimInterface(iface, true)

        var inputEndpoint: UsbEndpoint? = null
        var outputEndpoint: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            AppLogger.d("Endpoint $i: type=${ep.type}, direction=${ep.direction}, address=${ep.address}")
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    inputEndpoint = ep
                } else {
                    outputEndpoint = ep
                }
            }
        }

        if (inputEndpoint == null || outputEndpoint == null) {
            newConnection.close()
            _connectionState.value = CameraConnection.ConnectionState.Error("USB endpoints not found")
            return
        }

        disconnect()
        connection = newConnection
        cameraDevice = device
        bulkInEndpoint = inputEndpoint
        bulkOutEndpoint = outputEndpoint

        scope.launch {
            try {
                // #region debug-point A:open-session
                AppLogger.report("A", "UsbCameraConnection.kt:openDevice", "Opening PTP session", mapOf("device" to (device.productName ?: "unknown")))
                // #endregion
                openSession(PtpConstants.DEFAULT_SESSION_ID)
                // #region debug-point A:device-info
                val model = getDeviceInfo()
                AppLogger.report("A", "UsbCameraConnection.kt:openDevice", "Device info received", mapOf("model" to model))
                // #endregion
                _connectionState.value = CameraConnection.ConnectionState.Connected(model)
                AppLogger.d("USB device connected: $model")
            } catch (e: Exception) {
                // #region debug-point A:open-error
                AppLogger.report("A", "UsbCameraConnection.kt:openDevice", "PTP session failed", mapOf("error" to (e.message ?: "unknown")))
                // #endregion
                AppLogger.e("Failed to open PTP session", e)
                _connectionState.value = CameraConnection.ConnectionState.Connected(device.productName ?: "Camera")
            }
        }
    }

    override fun disconnect() {
        AppLogger.d("disconnect() called")
        if (sessionOpen) {
            runBlocking { closeSession() }
        }
        connection?.let {
            try {
                cameraDevice?.getInterface(0)?.let { iface -> it.releaseInterface(iface) }
                it.close()
            } catch (_: Exception) { }
        }
        connection = null
        cameraDevice = null
        bulkInEndpoint = null
        bulkOutEndpoint = null
        sessionOpen = false
        _connectionState.value = CameraConnection.ConnectionState.Disconnected
        _permissionState.value = false
        _detectedDevice.value = null
    }

    override suspend fun openSession(sessionId: Int): Boolean {
        if (sessionOpen) return true
        val (code, _) = sendCommandInternal(
            PtpCommand(PtpConstants.OPERATION_OPEN_SESSION, nextTransactionId(), intArrayOf(sessionId))
        )
        if (code == PtpConstants.RESPONSE_OK || code == PtpConstants.RESPONSE_SESSION_NOT_OPEN) {
            sessionOpen = true
            return true
        }
        return false
    }

    override suspend fun closeSession(): Boolean {
        if (!sessionOpen) return true
        val (code, _) = sendCommandInternal(
            PtpCommand(PtpConstants.OPERATION_CLOSE_SESSION, nextTransactionId())
        )
        sessionOpen = false
        return code == PtpConstants.RESPONSE_OK
    }

    override suspend fun sendCommand(code: Short, vararg params: Int): Pair<Short, IntArray> {
        return sendCommandInternal(PtpCommand(code, nextTransactionId(), params))
    }

    override suspend fun sendCommandWithData(code: Short, vararg params: Int): ByteArray {
        return sendCommandWithDataInternal(PtpCommand(code, nextTransactionId(), params))
    }

    override fun nextTransactionId(): Int = transactionId++

    private suspend fun sendCommandInternal(command: PtpCommand): Pair<Short, IntArray> = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IllegalStateException("USB not connected")
        val outEp = bulkOutEndpoint ?: throw IllegalStateException("Output endpoint not found")
        val inEp = bulkInEndpoint ?: throw IllegalStateException("Input endpoint not found")

        val commandBytes = command.encode()
        AppLogger.logUsb("OUT", commandBytes)
        // #region debug-point B:ptp-command
        AppLogger.report("B", "UsbCameraConnection.kt:sendCommandInternal", "PTP command", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "tid" to command.transactionId.toString(), "params" to command.parameters.joinToString()))
        // #endregion
        val written = conn.bulkTransfer(outEp, commandBytes, commandBytes.size, PtpConstants.DEFAULT_TIMEOUT_MS)
        AppLogger.d("bulkTransfer OUT returned: $written")

        val responseBuffer = ByteArray(512)
        val length = conn.bulkTransfer(inEp, responseBuffer, responseBuffer.size, PtpConstants.DEFAULT_TIMEOUT_MS)
        AppLogger.d("bulkTransfer IN returned: $length")
        if (length <= 0) {
            // #region debug-point B:ptp-response-empty
            AppLogger.report("B", "UsbCameraConnection.kt:sendCommandInternal", "PTP response empty", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "length" to length.toString()))
            // #endregion
            throw IllegalStateException("Failed to read response")
        }
        AppLogger.logUsb("IN", responseBuffer, length)
        val (responseCode, responseParams) = PtpCommand.decodeResponse(responseBuffer.copyOf(length))
        // #region debug-point B:ptp-response
        AppLogger.report("B", "UsbCameraConnection.kt:sendCommandInternal", "PTP response", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "responseCode" to String.format(Locale.US, "0x%04X", responseCode), "responseParams" to responseParams.joinToString()))
        // #endregion
        responseCode to responseParams
    }

    private suspend fun sendCommandWithDataInternal(command: PtpCommand): ByteArray = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IllegalStateException("USB not connected")
        val outEp = bulkOutEndpoint ?: throw IllegalStateException("Output endpoint not found")
        val inEp = bulkInEndpoint ?: throw IllegalStateException("Input endpoint not found")

        val commandBytes = command.encode()
        AppLogger.logUsb("OUT", commandBytes)
        // #region debug-point B:ptp-data-command
        AppLogger.report("B", "UsbCameraConnection.kt:sendCommandWithDataInternal", "PTP data command", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "tid" to command.transactionId.toString(), "params" to command.parameters.joinToString()))
        // #endregion
        val written = conn.bulkTransfer(outEp, commandBytes, commandBytes.size, PtpConstants.DEFAULT_TIMEOUT_MS)
        AppLogger.d("bulkTransfer OUT returned: $written")

        val result = mutableListOf<Byte>()
        var responseCode: Short = -1
        while (true) {
            val buffer = ByteArray(4096)
            val length = conn.bulkTransfer(inEp, buffer, buffer.size, PtpConstants.DEFAULT_TIMEOUT_MS)
            AppLogger.d("bulkTransfer IN chunk returned: $length")
            if (length <= 0) break
            AppLogger.logUsb("IN", buffer, length)

            val bb = ByteBuffer.wrap(buffer, 0, length)
            bb.order(ByteOrder.LITTLE_ENDIAN)
            if (length >= 12) {
                val containerType = bb.getShort(4)
                if (containerType == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                    // Response arrived as its own packet
                    responseCode = bb.getShort(6)
                    break
                }
            }
            // Data container: accumulate and keep reading for the following response container
            result.addAll(buffer.copyOf(length).toList())
        }
        // Response container may also be appended to the last data packet; extract it from the tail.
        if (responseCode == (-1).toShort() && result.size >= 12) {
            val tailOffset = result.size - 12
            val bb = ByteBuffer.wrap(result.toByteArray(), tailOffset, 12)
            bb.order(ByteOrder.LITTLE_ENDIAN)
            val containerType = bb.getShort(4)
            if (containerType == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                responseCode = bb.getShort(6)
                repeat(12) { result.removeAt(result.size - 1) }
            }
        }
        // #region debug-point B:ptp-data-response
        AppLogger.report("B", "UsbCameraConnection.kt:sendCommandWithDataInternal", "PTP data response", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "responseCode" to String.format(Locale.US, "0x%04X", responseCode), "bytes" to result.size.toString()))
        // #endregion
        result.toByteArray()
    }

    override suspend fun getDeviceInfo(): String {
        val data = sendCommandWithDataInternal(
            PtpCommand(PtpConstants.OPERATION_GET_DEVICE_INFO, nextTransactionId())
        )
        return PtpCommand.decodeDeviceInfoModel(data)
    }

    override suspend fun getDeviceProperty(code: Short): Int? {
        // #region debug-point C:property-get
        AppLogger.report("C", "UsbCameraConnection.kt:getDeviceProperty", "Getting property", mapOf("code" to String.format(Locale.US, "0x%04X", code)))
        // #endregion
        val data = sendCommandWithDataInternal(
            PtpCommand(PtpConstants.OPERATION_DEVICE_PROP_VALUE_GET, nextTransactionId(), intArrayOf(code.toInt()))
        )
        val payload = PtpCommand.decodeDataPayload(data)
        val value = if (payload.size < 2) null else {
            val buffer = ByteBuffer.wrap(payload)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            when (payload.size) {
                1 -> buffer.get().toInt()
                2 -> buffer.getShort().toInt()
                4 -> buffer.getInt()
                else -> buffer.getInt()
            }
        }
        // #region debug-point C:property-get-result
        AppLogger.report("C", "UsbCameraConnection.kt:getDeviceProperty", "Property value", mapOf("code" to String.format(Locale.US, "0x%04X", code), "value" to value.toString(), "payloadSize" to payload.size.toString()))
        // #endregion
        return value
    }

    override suspend fun setDeviceProperty(code: Short, value: Int): Boolean {
        return setDevicePropertyValue(code, encodeInt32(value))
    }

    override suspend fun getDevicePropertyDesc(code: Short): ByteArray {
        return try {
            val data = sendCommandWithDataInternal(
                PtpCommand(PtpConstants.OPERATION_DEVICE_PROP_DESC, nextTransactionId(), intArrayOf(code.toInt()))
            )
            PtpCommand.decodeDataPayload(data)
        } catch (e: Exception) {
            AppLogger.e("getDevicePropertyDesc failed for 0x${String.format(Locale.US, "%04X", code)}", e)
            ByteArray(0)
        }
    }

    suspend fun setDevicePropertyValue(code: Short, data: ByteArray): Boolean {
        // #region debug-point C:property-set
        AppLogger.report("C", "UsbCameraConnection.kt:setDevicePropertyValue", "Setting property", mapOf("code" to String.format(Locale.US, "0x%04X", code), "hex" to data.joinToString(" ") { String.format(Locale.US, "%02X", it) }))
        // #endregion
        return try {
            val (responseCode, _) = sendSetPropertyCommand(code, data)
            // #region debug-point C:property-set-result
            AppLogger.report("C", "UsbCameraConnection.kt:setDevicePropertyValue", "Property set result", mapOf("code" to String.format(Locale.US, "0x%04X", code), "responseCode" to String.format(Locale.US, "0x%04X", responseCode)))
            // #endregion
            responseCode == PtpConstants.RESPONSE_OK
        } catch (e: Exception) {
            // #region debug-point C:property-set-error
            AppLogger.report("C", "UsbCameraConnection.kt:setDevicePropertyValue", "Property set error", mapOf("code" to String.format(Locale.US, "0x%04X", code), "error" to (e.message ?: "unknown")))
            // #endregion
            false
        }
    }

    private suspend fun sendSetPropertyCommand(code: Short, data: ByteArray): Pair<Short, IntArray> = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IllegalStateException("USB not connected")
        val outEp = bulkOutEndpoint ?: throw IllegalStateException("Output endpoint not found")
        val inEp = bulkInEndpoint ?: throw IllegalStateException("Input endpoint not found")
        val tid = nextTransactionId()

        // Command phase
        val commandBytes = PtpCommand(PtpConstants.OPERATION_DEVICE_PROP_VALUE_SET, tid, intArrayOf(code.toInt())).encode()
        conn.bulkTransfer(outEp, commandBytes, commandBytes.size, PtpConstants.DEFAULT_TIMEOUT_MS)

        // Data phase
        val dataContainer = ByteArray(12 + data.size)
        val bb = ByteBuffer.wrap(dataContainer)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(12 + data.size)
        bb.putShort(PtpConstants.CONTAINER_TYPE_DATA)
        bb.putShort(PtpConstants.OPERATION_DEVICE_PROP_VALUE_SET)
        bb.putInt(tid)
        data.copyInto(dataContainer, 12)
        conn.bulkTransfer(outEp, dataContainer, dataContainer.size, PtpConstants.DEFAULT_TIMEOUT_MS)

        // Response phase
        val responseBuffer = ByteArray(512)
        val length = conn.bulkTransfer(inEp, responseBuffer, responseBuffer.size, PtpConstants.DEFAULT_TIMEOUT_MS)
        if (length <= 0) throw IllegalStateException("Failed to read response")
        PtpCommand.decodeResponse(responseBuffer.copyOf(length))
    }

    private fun encodeInt32(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun encodePtpString(text: String): ByteArray {
        val chars = text.toCharArray()
        val bytes = ByteArray(1 + chars.size * 2 + 2)
        bytes[0] = (chars.size + 1).toByte()
        val bb = ByteBuffer.wrap(bytes, 1, chars.size * 2)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        chars.forEach { bb.putShort(it.code.toShort()) }
        return bytes
    }

    override suspend fun listPhotos(folder: String): List<PhotoItem> {
        if (!ensureSession()) return emptyList()

        val storageIds = getStorageIds()
        if (storageIds.isEmpty()) return emptyList()

        val items = mutableListOf<PhotoItem>()
        for (storageId in storageIds) {
            val handles = getObjectHandles(storageId, PtpConstants.OBJECT_FORMAT_JPEG.toInt(), 0)
            for (handle in handles) {
                val info = getObjectInfo(handle) ?: continue
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
        }
        return items
    }

    private suspend fun getStorageIds(): IntArray {
        val data = sendCommandWithDataInternal(
            PtpCommand(PtpConstants.OPERATION_GET_STORAGE_IDS, nextTransactionId())
        )
        return PtpCommand.decodeIntArray(data)
    }

    private suspend fun getObjectHandles(storageId: Int, format: Int, parent: Int): IntArray {
        val data = sendCommandWithDataInternal(
            PtpCommand(PtpConstants.OPERATION_GET_OBJECT_HANDLES, nextTransactionId(), intArrayOf(storageId, format, parent))
        )
        return PtpCommand.decodeIntArray(data)
    }

    private suspend fun getObjectInfo(handle: Int): ObjectInfo? {
        val data = sendCommandWithDataInternal(
            PtpCommand(PtpConstants.OPERATION_GET_OBJECT_INFO, nextTransactionId(), intArrayOf(handle))
        )
        return PtpCommand.decodeObjectInfo(data)
    }

    override suspend fun downloadPhoto(handle: Int): ByteArray? {
        if (!ensureSession()) return null
        return try {
            val data = sendCommandWithDataInternal(
                PtpCommand(PtpConstants.OPERATION_GET_OBJECT, nextTransactionId(), intArrayOf(handle))
            )
            PtpCommand.decodeDataPayload(data).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            AppLogger.e("downloadPhoto failed", e)
            null
        }
    }

    override suspend fun deletePhoto(handle: Int): Boolean {
        if (!ensureSession()) return false
        return try {
            val (code, _) = sendCommandInternal(
                PtpCommand(PtpConstants.OPERATION_DELETE_OBJECT, nextTransactionId(), intArrayOf(handle))
            )
            code == PtpConstants.RESPONSE_OK
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun formatStorage(target: StorageTarget): Boolean {
        if (!ensureSession()) return false
        val storageIds = getStorageIds()
        if (storageIds.isEmpty()) return false
        val storageId = storageIds.getOrNull(
            when (target) {
                StorageTarget.Camera -> 0
                StorageTarget.Card1 -> 0
                StorageTarget.Card2 -> 1
            }
        ) ?: storageIds.first()
        return try {
            val (code, _) = sendCommandInternal(
                PtpCommand(0x100F, nextTransactionId(), intArrayOf(storageId)) // FormatStore
            )
            code == PtpConstants.RESPONSE_OK
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun ensureSession(): Boolean {
        if (!sessionOpen) {
            openSession(PtpConstants.DEFAULT_SESSION_ID)
        }
        return sessionOpen
    }

    override fun release() {
        AppLogger.d("release() called")
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) { }
        scope.cancel()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.phtontools.phtonview.USB_PERMISSION"

        // Known PTP / MTP camera vendor IDs
        private val SUPPORTED_VENDOR_IDS = intArrayOf(
            0x04B0, // Nikon
            0x04A9, // Canon
            0x054C, // Sony
            0x04CB, // Fuji
            0x04DA, // Panasonic
            0x040A, // Kodak
            0x0E8D  // Some Chinese brands
        )
    }
}
