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
import java.io.ByteArrayOutputStream
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
        // 已经连接同一台设备时直接跳过，避免广播/权限回调重复触发导致反复重连
        if (cameraDevice?.deviceId == device.deviceId &&
            _connectionState.value is CameraConnection.ConnectionState.Connected
        ) {
            AppLogger.d("Device ${device.deviceId} already connected, skip duplicate open")
            return
        }
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
                var sessionOk = false
                repeat(3) { attempt ->
                    sessionOk = openSession(PtpConstants.DEFAULT_SESSION_ID)
                    if (sessionOk) return@repeat
                    AppLogger.report("A", "UsbCameraConnection.kt:openDevice", "Session retry", mapOf("attempt" to (attempt + 2).toString()))
                    delay(100)
                }
                // #region debug-point A:device-info
                val model = runCatching { getDeviceInfo() }.getOrDefault(device.productName ?: "Camera")
                AppLogger.report("A", "UsbCameraConnection.kt:openDevice", "Device info received", mapOf("model" to model, "sessionOk" to sessionOk.toString()))
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
        val wasSessionOpen = sessionOpen
        val conn = connection
        val iface = cameraDevice?.getInterface(0)

        connection = null
        cameraDevice = null
        bulkInEndpoint = null
        bulkOutEndpoint = null
        sessionOpen = false
        _connectionState.value = CameraConnection.ConnectionState.Disconnected
        _permissionState.value = false
        _detectedDevice.value = null

        // 在独立协程中完成关闭会话与释放 USB，避免在主线程/BroadcastReceiver 中阻塞。
        scope.launch {
            if (wasSessionOpen) {
                runCatching { closeSession() }
            }
            conn?.let {
                try {
                    iface?.let { i -> it.releaseInterface(i) }
                    it.close()
                } catch (_: Exception) { }
            }
        }
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
        return sendCommandWithDataInternal(PtpCommand(code, nextTransactionId(), params), PtpConstants.DEFAULT_TIMEOUT_MS)
    }

    /**
     * Send a command and read the data phase with a custom timeout.
     * Used by live view to avoid long stalls when the loop is cancelled.
     */
    suspend fun sendCommandWithData(code: Short, timeoutMs: Int, vararg params: Int): ByteArray {
        return sendCommandWithDataInternal(PtpCommand(code, nextTransactionId(), params), timeoutMs)
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

        suspend fun transferOnce(): Pair<Short, IntArray> {
            val written = conn.bulkTransfer(outEp, commandBytes, commandBytes.size, PtpConstants.DEFAULT_TIMEOUT_MS)
            AppLogger.d("bulkTransfer OUT returned: $written")
            if (written != commandBytes.size) {
                throw IllegalStateException("bulkTransfer OUT failed: $written")
            }

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
            return PtpCommand.decodeResponse(responseBuffer.copyOf(length))
        }

        val result = runCatching { transferOnce() }.getOrElse { firstError ->
            AppLogger.report("B", "UsbCameraConnection.kt:sendCommandInternal", "First attempt failed, retry", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "error" to (firstError.message ?: "unknown")))
            delay(50)
            runCatching { transferOnce() }.getOrElse { throw firstError }
        }
        // #region debug-point B:ptp-response
        AppLogger.report("B", "UsbCameraConnection.kt:sendCommandInternal", "PTP response", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "responseCode" to String.format(Locale.US, "0x%04X", result.first), "responseParams" to result.second.joinToString()))
        // #endregion
        result
    }

    private suspend fun sendCommandWithDataInternal(command: PtpCommand, timeoutMs: Int = PtpConstants.DEFAULT_TIMEOUT_MS): ByteArray = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IllegalStateException("USB not connected")
        val outEp = bulkOutEndpoint ?: throw IllegalStateException("Output endpoint not found")
        val inEp = bulkInEndpoint ?: throw IllegalStateException("Input endpoint not found")

        val commandBytes = command.encode()
        AppLogger.logUsb("OUT", commandBytes)
        // #region debug-point B:ptp-data-command
        AppLogger.report("B", "UsbCameraConnection.kt:sendCommandWithDataInternal", "PTP data command", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "tid" to command.transactionId.toString(), "params" to command.parameters.joinToString()))
        // #endregion

        suspend fun transferOnce(): Pair<ByteArray, Short> {
            val written = conn.bulkTransfer(outEp, commandBytes, commandBytes.size, timeoutMs)
            AppLogger.d("bulkTransfer OUT returned: $written")
            if (written != commandBytes.size) {
                throw IllegalStateException("bulkTransfer OUT failed: $written")
            }

            // 使用 ByteArrayOutputStream 避免 MutableList<Byte> 装箱和中间数组分配
            val result = ByteArrayOutputStream(65536)
            var responseCode: Short = -1
            var dataLengthRemaining = -1
            var isFirstDataChunk = true

            while (true) {
                // 增大单次读取缓冲，减少 USB 传输往返次数
                val buffer = ByteArray(16384)
                val length = conn.bulkTransfer(inEp, buffer, buffer.size, timeoutMs)
                AppLogger.d("bulkTransfer IN chunk returned: $length")
                if (length <= 0) break
                AppLogger.logUsb("IN", buffer, length)

                if (length >= 12) {
                    val bb = ByteBuffer.wrap(buffer, 0, length)
                    bb.order(ByteOrder.LITTLE_ENDIAN)
                    val containerLength = bb.getInt(0)
                    val containerType = bb.getShort(4)
                    if (containerType == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                        responseCode = bb.getShort(6)
                        break
                    }
                    if (containerType == PtpConstants.CONTAINER_TYPE_DATA && isFirstDataChunk) {
                        // 首个数据容器包：保留完整头部，供上层 decodeDataPayload 自行剥离
                        dataLengthRemaining = containerLength - length
                        result.write(buffer, 0, length)
                        isFirstDataChunk = false
                        if (dataLengthRemaining <= 0) {
                            // 数据已在单包内传完，继续读取响应容器
                            continue
                        }
                        continue
                    }
                }

                // 仍在接收数据容器后续字节（不含头部，直接追加）
                if (dataLengthRemaining > 0) {
                    val take = minOf(length, dataLengthRemaining)
                    result.write(buffer, 0, take)
                    dataLengthRemaining -= take
                    if (dataLengthRemaining <= 0) {
                        // 数据传完，下一轮应该是响应容器
                        continue
                    }
                } else {
                    result.write(buffer, 0, length)
                }
            }

            var dataBytes = result.toByteArray()

            // 兼容：响应容器可能粘在最后一个数据包尾部
            if (responseCode == (-1).toShort() && dataBytes.size >= 12) {
                val tailOffset = dataBytes.size - 12
                val bb = ByteBuffer.wrap(dataBytes, tailOffset, 12)
                bb.order(ByteOrder.LITTLE_ENDIAN)
                val containerType = bb.getShort(4)
                if (containerType == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                    responseCode = bb.getShort(6)
                    dataBytes = dataBytes.copyOf(tailOffset)
                }
            }
            // #region debug-point B:ptp-data-response
            AppLogger.report("B", "UsbCameraConnection.kt:sendCommandWithDataInternal", "PTP data response", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "responseCode" to String.format(Locale.US, "0x%04X", responseCode), "bytes" to dataBytes.size.toString()))
            // #endregion
            return dataBytes to responseCode
        }

        val (data, responseCode) = transferOnce()
        if (responseCode != PtpConstants.RESPONSE_OK && responseCode.toInt() != -1) {
            AppLogger.report("B", "UsbCameraConnection.kt:sendCommandWithDataInternal", "Non-OK response", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "responseCode" to String.format(Locale.US, "0x%04X", responseCode)))
        }
        data
    }

    override suspend fun getDeviceInfo(): String {
        val data = sendCommandWithDataInternal(
            PtpCommand(PtpConstants.OPERATION_GET_DEVICE_INFO, nextTransactionId())
        )
        return PtpCommand.decodeDeviceInfoModel(data)
    }

    override suspend fun getDeviceInfoRaw(): ByteArray {
        return runCatching {
            sendCommandWithDataInternal(
                PtpCommand(PtpConstants.OPERATION_GET_DEVICE_INFO, nextTransactionId())
            )
        }.onFailure {
            AppLogger.report("B", "UsbCameraConnection.kt:getDeviceInfoRaw", "Failed", mapOf("error" to (it.message ?: "unknown")))
        }.getOrDefault(ByteArray(0))
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
        return setDevicePropertyValue(code, encodePropertyValue(code, value))
    }

    /**
     * Encode a PTP device property value with the correct byte width.
     * Sending the wrong size (e.g. 4 bytes for a UINT16 property) causes
     * many cameras to misinterpret ISO / F-number / EV values.
     */
    private fun encodePropertyValue(code: Short, value: Int): ByteArray {
        val width = when (code.toInt() and 0xFFFF) {
            0x5001 -> 1 // BatteryLevel UINT8
            0x5005 -> 2 // WhiteBalance UINT16
            0x5007 -> 2 // FNumber UINT16
            0x500B -> 2 // ExposureMeteringMode UINT16
            0x500D -> 4 // ExposureTime UINT32
            0x500E -> 2 // ExposureProgramMode UINT16
            0x500F -> 2 // ISO UINT16
            0x5010 -> 2 // ExposureCompensation INT16
            0x501A -> 2 // FocusMode UINT16
            0x501C -> 2 // FlashMode UINT16
            0xD100 -> 4 // Nikon ShutterSpeed UINT32
            0xD10B -> 1 // Nikon RecordingMedia UINT8
            0xD124 -> 2 // Nikon FlashCompensation INT16
            0xD138 -> 2 // Nikon ShootingMode UINT16
            0xD1A2 -> 1 // Nikon LiveViewStatus UINT8
            0xD1A4 -> 4 // Nikon LiveViewProhibitCondition UINT32
            0xD400 -> 4 // Nikon ShotsRemaining UINT32
            else -> 4
        }
        val bb = ByteBuffer.allocate(width).order(ByteOrder.LITTLE_ENDIAN)
        when (width) {
            1 -> bb.put(value.toByte())
            2 -> bb.putShort(value.toShort())
            4 -> bb.putInt(value)
        }
        return bb.array()
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

    override suspend fun setDevicePropertyValue(code: Short, data: ByteArray): Boolean {
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
        if (!ensureSession()) {
            AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "No session", emptyMap())
            return emptyList()
        }

        val allTriedStorageIds = mutableSetOf<Int>()
        val items = mutableListOf<PhotoItem>()

        // 辅助：用给定 storage ID 列表枚举照片
        suspend fun enumerateFromStorageIds(storageIds: IntArray): Boolean {
            AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Storage IDs", mapOf("count" to storageIds.size.toString(), "ids" to storageIds.joinToString { "0x${it.toString(16)}" }))
            if (storageIds.isEmpty()) return false

            for (storageId in storageIds) {
                allTriedStorageIds.add(storageId)
                val handles = runCatching { enumeratePhotosWithFallback(storageId) }.getOrElse {
                    AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Enumerate failed", mapOf("storage" to String.format(Locale.US, "0x%08X", storageId), "error" to (it.message ?: "unknown")))
                    emptyList()
                }
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Handles", mapOf("storage" to String.format(Locale.US, "0x%08X", storageId), "count" to handles.size.toString()))

                for (handle in handles) {
                    val info = runCatching { getObjectInfo(handle) }.getOrNull() ?: continue
                    if (info.objectFormat == PtpConstants.OBJECT_FORMAT_ASSOCIATION || info.filename.isBlank()) continue
                    if (!isImageOrVideoFormat(info.objectFormat)) {
                        AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Skip non-media", mapOf("file" to info.filename, "format" to String.format(Locale.US, "0x%04X", info.objectFormat)))
                        continue
                    }
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
                if (items.isNotEmpty()) return true
            }
            return false
        }

        // 第 1 轮：正常枚举
        val storageIds = runCatching { resolveStorageIds() }.getOrDefault(IntArray(0))
        if (enumerateFromStorageIds(storageIds)) {
            items.sortByDescending { unsignedInt(it.id.toIntOrNull() ?: 0) }
            AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Result", mapOf("count" to items.size.toString(), "storagesTried" to allTriedStorageIds.size.toString()))
            return items
        }

        // 第 2 轮：若正常枚举没结果，尝试重置会话后再枚举（部分相机在取景后需要重置才能访问存储）
        if (items.isEmpty()) {
            AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "First attempt empty, reset session", emptyMap())
            resetPtpSession()
            val resetStorageIds = runCatching { resolveStorageIds() }.getOrDefault(IntArray(0))
            if (enumerateFromStorageIds(resetStorageIds)) {
                items.sortByDescending { unsignedInt(it.id.toIntOrNull() ?: 0) }
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Result after reset", mapOf("count" to items.size.toString(), "storagesTried" to allTriedStorageIds.size.toString()))
                return items
            }
        }

        // 第 3 轮：尝试常见默认 storage ID
        if (items.isEmpty()) {
            val fallbackIds = intArrayOf(
                0x00010001, 0x00020001, 0x00010002,
                0x00000001, 0x00000002, 0x00000003,
                0xFFFFFFFF.toInt()
            ).filter { it !in allTriedStorageIds }
            for (storageId in fallbackIds) {
                allTriedStorageIds.add(storageId)
                val handles = runCatching { enumeratePhotosWithFallback(storageId) }.getOrElse { emptyList() }
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
                if (items.isNotEmpty()) {
                    AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Fallback storage ok", mapOf("storage" to String.format(Locale.US, "0x%08X", storageId), "count" to items.size.toString()))
                    break
                }
            }
        }

        // 新照片 handle 通常更大，按无符号长整型降序排在前面
        items.sortByDescending { unsignedInt(it.id.toIntOrNull() ?: 0) }
        AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Result", mapOf("count" to items.size.toString(), "storagesTried" to allTriedStorageIds.size.toString()))
        return items
    }

    /**
     * 多策略链式降级枚举照片句柄，尽量兼容不同厂商的 PTP/MTP 实现。
     */
    private suspend fun enumeratePhotosWithFallback(storageId: Int): List<Int> {
        val formats = listOf(0x3801, 0x3800, 0x380B, 0x380C, 0x380D, 0x3009, 0x300A, 0x300B, 0x300C)
        val commonParents = listOf(0, 0xFFFFFFFF.toInt(), 0x00000001, 0x00000002, 0x00000003)

        // 策略 1：format=0, parent=0xFFFFFFFF（一次性递归，部分相机支持）
        runCatchingWithRetry {
            val handles = getObjectHandles(storageId, 0, 0xFFFFFFFF.toInt())
            if (handles.isNotEmpty()) {
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Strategy 1 ok", mapOf("count" to handles.size.toString()))
                return handles.toList()
            }
        }

        // 策略 2：format=0, parent=0（根目录，非递归）
        runCatchingWithRetry {
            val handles = getObjectHandles(storageId, 0, 0).toList()
            if (handles.isNotEmpty()) {
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Strategy 2 ok", mapOf("count" to handles.size.toString()))
                return handles
            }
        }

        // 策略 3：从 parent=0 开始逐目录递归
        runCatchingWithRetry {
            val handles = enumerateObjectsRecursive(storageId, parent = 0)
            if (handles.isNotEmpty()) {
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Strategy 3 ok", mapOf("count" to handles.size.toString()))
                return handles
            }
        }

        // 策略 4：按常见图像格式，parent=0xFFFFFFFF
        runCatchingWithRetry {
            val result = formats.flatMap { format ->
                runCatching { getObjectHandles(storageId, format, 0xFFFFFFFF.toInt()).toList() }.getOrDefault(emptyList())
            }
            if (result.isNotEmpty()) {
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Strategy 4 ok", mapOf("count" to result.size.toString()))
                return result
            }
        }

        // 策略 5：按常见图像格式，parent=0
        runCatchingWithRetry {
            val result = formats.flatMap { format ->
                runCatching { getObjectHandles(storageId, format, 0).toList() }.getOrDefault(emptyList())
            }
            if (result.isNotEmpty()) {
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Strategy 5 ok", mapOf("count" to result.size.toString()))
                return result
            }
        }

        // 策略 6：尝试常见子目录 parent + format=0
        runCatchingWithRetry {
            val result = commonParents.drop(2).flatMap { parent ->
                runCatching { getObjectHandles(storageId, 0, parent).toList() }.getOrDefault(emptyList())
            }
            if (result.isNotEmpty()) {
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Strategy 6 ok", mapOf("count" to result.size.toString()))
                return result
            }
        }

        // 策略 7：对根目录下每个 association 对象递归枚举
        runCatchingWithRetry {
            val rootHandles = getObjectHandles(storageId, 0, 0)
            val result = mutableListOf<Int>()
            for (handle in rootHandles) {
                val info = try { getObjectInfo(handle) } catch (_: Exception) { null } ?: continue
                if (info.objectFormat == PtpConstants.OBJECT_FORMAT_ASSOCIATION) {
                    try {
                        result.addAll(enumerateObjectsRecursive(storageId, handle))
                    } catch (_: Exception) { }
                }
            }
            if (result.isNotEmpty()) {
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Strategy 7 ok", mapOf("count" to result.size.toString()))
                return result
            }
        }

        // 策略 8：storageId=0xFFFFFFFF 表示所有存储，部分相机只接受这个值
        if (storageId != 0xFFFFFFFF.toInt()) {
            runCatchingWithRetry {
                val handles = getObjectHandles(0xFFFFFFFF.toInt(), 0, 0xFFFFFFFF.toInt())
                if (handles.isNotEmpty()) {
                    AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Strategy 8 ok", mapOf("count" to handles.size.toString()))
                    return handles.toList()
                }
            }
        }

        // 策略 9：尝试 storageId 忽略，直接按格式枚举 parent=0
        runCatchingWithRetry {
            val result = formats.flatMap { format ->
                runCatching { getObjectHandles(0, format, 0xFFFFFFFF.toInt()).toList() }.getOrDefault(emptyList())
            }
            if (result.isNotEmpty()) {
                AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "Strategy 9 ok", mapOf("count" to result.size.toString()))
                return result
            }
        }

        AppLogger.report("C", "UsbCameraConnection.kt:listPhotos", "All strategies empty", mapOf("storage" to String.format(Locale.US, "0x%08X", storageId)))
        return emptyList()
    }

    /**
     * 对指定代码块执行一次重试，用于兼容偶发 USB 传输失败。
     */
    private suspend inline fun <T> runCatchingWithRetry(block: () -> T): Result<T> {
        val first = runCatching { block() }
        if (first.isSuccess) return first
        delay(50)
        return runCatching { block() }
    }

    /**
     * 获取存储 ID；若相机未返回，则回退到常见默认值。
     * 常见 PTP 存储 ID：0x00010001（卡1）、0x00020001（卡2）、0x00010002（内部存储）等。
     */
    private suspend fun resolveStorageIds(): IntArray {
        val ids = runCatching { getStorageIds() }.getOrDefault(IntArray(0))
        if (ids.isNotEmpty()) {
            AppLogger.report("C", "UsbCameraConnection.kt:resolveStorageIds", "Got storage IDs", mapOf("ids" to ids.joinToString { "0x${it.toString(16)}" }))
            return ids
        }
        AppLogger.report("C", "UsbCameraConnection.kt:resolveStorageIds", "Fallback storage IDs", emptyMap())
        return intArrayOf(0x00010001, 0x00020001, 0x00010002, 0x00000001, 0x00000002)
    }

    /**
     * 递归枚举指定存储下的所有对象句柄。
     */
    private suspend fun enumerateObjectsRecursive(storageId: Int, parent: Int): List<Int> {
        val result = mutableListOf<Int>()
        val handles = runCatching { getObjectHandles(storageId, 0, parent) }.getOrDefault(IntArray(0))
        for (handle in handles) {
            val info = runCatching { getObjectInfo(handle) }.getOrNull() ?: continue
            if (info.objectFormat == PtpConstants.OBJECT_FORMAT_ASSOCIATION) {
                result.addAll(enumerateObjectsRecursive(storageId, handle))
            } else {
                result.add(handle)
            }
        }
        return result
    }

    private fun unsignedInt(value: Int): Long = value.toLong() and 0xFFFFFFFFL

    private fun isImageOrVideoFormat(format: Short): Boolean {
        val code = format.toInt() and 0xFFFF
        // PTP 图像格式范围 0x3800-0x38FF；常见视频格式在 0x3009-0x300D 等
        return code in 0x3800..0x38FF || code in 0x3000..0x30FF
    }

    private suspend fun getStorageIds(): IntArray {
        val data = sendCommandWithDataInternal(
            PtpCommand(PtpConstants.OPERATION_GET_STORAGE_IDS, nextTransactionId())
        )
        return PtpCommand.decodeIntArray(data)
    }

    override suspend fun getStorageInfo(storageId: Int): Pair<Long, Long>? {
        return try {
            val data = sendCommandWithDataInternal(
                PtpCommand(PtpConstants.OPERATION_GET_STORAGE_INFO, nextTransactionId(), intArrayOf(storageId))
            )
            val payload = PtpCommand.decodeDataPayload(data)
            if (payload.size < 26) return null
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            buffer.getShort() // StorageType
            buffer.getShort() // FilesystemType
            buffer.getShort() // AccessCapability
            val maxCapacity = buffer.getLong()
            val freeSpace = buffer.getLong()
            maxCapacity to freeSpace
        } catch (e: Exception) {
            AppLogger.e("getStorageInfo failed", e)
            null
        }
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
            // 照片可能较大，放宽读取超时到 30 秒
            val data = sendCommandWithData(
                PtpConstants.OPERATION_GET_OBJECT,
                timeoutMs = 30000,
                params = intArrayOf(handle)
            )
            val payload = PtpCommand.decodeDataPayload(data)
            AppLogger.report("C", "UsbCameraConnection.kt:downloadPhoto", "Downloaded", mapOf("handle" to String.format(Locale.US, "0x%08X", handle), "bytes" to payload.size.toString()))
            payload.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            AppLogger.report("C", "UsbCameraConnection.kt:downloadPhoto", "Download failed", mapOf("handle" to String.format(Locale.US, "0x%08X", handle), "error" to (e.message ?: "unknown")))
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
        if (sessionOpen) return true
        repeat(3) { attempt ->
            if (openSession(PtpConstants.DEFAULT_SESSION_ID)) return true
            AppLogger.report("A", "UsbCameraConnection.kt:ensureSession", "Retry open session", mapOf("attempt" to (attempt + 1).toString()))
            delay(50)
        }
        return sessionOpen
    }

    /**
     * 重置 PTP 会话：先尝试关闭再重新打开，用于兼容某些相机在长时间
     * 通信或取景后拒绝存储访问的情况。
     */
    private suspend fun resetPtpSession() {
        AppLogger.report("A", "UsbCameraConnection.kt:resetPtpSession", "Resetting PTP session", emptyMap())
        val wasOpen = sessionOpen
        sessionOpen = false
        if (wasOpen) {
            runCatching { closeSession() }
            delay(100)
        }
        repeat(3) { attempt ->
            if (openSession(PtpConstants.DEFAULT_SESSION_ID)) return
            AppLogger.report("A", "UsbCameraConnection.kt:resetPtpSession", "Retry open", mapOf("attempt" to (attempt + 1).toString()))
            delay(100)
        }
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
