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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val connectionLock = Mutex()
    // 序列化所有 PTP USB 命令（sendCommand / sendCommandWithData），避免事件循环、
    // 属性轮询、拍摄命令并发访问 bulk 端点导致数据错乱。
    private val ptpLock = Mutex()

    private val _permissionState = MutableStateFlow(false)
    val permissionState: StateFlow<Boolean> = _permissionState

    private val _detectedDevice = MutableStateFlow<String?>(null)
    val detectedDevice: StateFlow<String?> = _detectedDevice

    private val _connectionState = MutableStateFlow<CameraConnection.ConnectionState>(CameraConnection.ConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnection.ConnectionState> = _connectionState

    private var transactionId = 1
    private var sessionOpen = false

    // 连续 USB bulk 传输失败计数，用于检测端点失效并自动恢复。
    private val consecutiveBulkFailures = AtomicInteger(0)

    // 防止 USB attached/permission/connect 广播并发触发多次 openDevice，导致旧连接被中途关闭。
    private val isOpeningDevice = AtomicBoolean(false)

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
        val current = _connectionState.value
        if (current is CameraConnection.ConnectionState.Connected || current is CameraConnection.ConnectionState.Connecting) {
            AppLogger.d("USB already ${current.javaClass.simpleName}, skip duplicate connect")
            return
        }
        _connectionState.value = CameraConnection.ConnectionState.Connecting
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
        // 已经连接或正在连接同一台设备时直接跳过，避免广播/权限回调重复触发导致反复重连
        val state = _connectionState.value
        if (cameraDevice?.deviceId == device.deviceId &&
            (state is CameraConnection.ConnectionState.Connected || state is CameraConnection.ConnectionState.Connecting)
        ) {
            AppLogger.d("Device ${device.deviceId} already connected/connecting, skip duplicate open")
            return
        }
        // 原子方式抢占 openDevice 执行权；有另一个协程正在打开时直接返回，避免中途关闭旧连接。
        if (!isOpeningDevice.compareAndSet(false, true)) {
            AppLogger.d("Another openDevice in progress, skip duplicate open for ${device.deviceId}")
            return
        }

        val newConnection = usbManager.openDevice(device) ?: run {
            isOpeningDevice.set(false)
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
            isOpeningDevice.set(false)
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
                cleanupFailedConnection(newConnection)
                _connectionState.value = CameraConnection.ConnectionState.Error("PTP session failed: ${e.message}")
            } finally {
                isOpeningDevice.set(false)
            }
        }
    }

    override fun disconnect() {
        AppLogger.d("disconnect() called")
        // 同步完成清理，避免连接字段在关闭前被清空导致其他命令看到不一致状态。
        // CameraRepositoryImpl 已在 IO 协程中调用本方法，不会阻塞主线程。
        runBlocking {
            connectionLock.withLock {
                val wasSessionOpen = sessionOpen
                val conn = connection
                val outEp = bulkOutEndpoint
                val inEp = bulkInEndpoint

                sessionOpen = false
                consecutiveBulkFailures.set(0)
                _connectionState.value = CameraConnection.ConnectionState.Disconnected
                _permissionState.value = false
                _detectedDevice.value = null

                if (wasSessionOpen && conn != null && outEp != null && inEp != null) {
                    runCatching { closeSessionOnConnection(conn, outEp, inEp) }
                }
                // ponytail: reuse single cleanup path for failed and normal disconnect.
                cleanupFailedConnection(conn)

                connection = null
                cameraDevice = null
                bulkInEndpoint = null
                bulkOutEndpoint = null
            }
        }
    }

    private fun cleanupFailedConnection(conn: UsbDeviceConnection?) {
        conn ?: return
        try {
            cameraDevice?.getInterface(0)?.let { conn.releaseInterface(it) }
        } catch (_: Exception) { }
        try {
            conn.close()
        } catch (_: Exception) { }
    }

    /**
     * 尝试恢复 USB bulk 端点：释放并重新声明接口、重新打开 PTP 会话。
     * 用于相机进入/退出 PC 模式后端点失效、连续 bulk 传输失败等场景。
     */
    private suspend fun recoverUsbEndpoint(): Boolean {
        val conn = connection ?: return false
        val device = cameraDevice ?: return false
        val iface = device.getInterface(0)
        AppLogger.d("recoverUsbEndpoint: releasing and reclaiming interface")
        return try {
            conn.releaseInterface(iface)
            if (!conn.claimInterface(iface, true)) {
                AppLogger.e("recoverUsbEndpoint: failed to reclaim interface")
                return false
            }
            consecutiveBulkFailures.set(0)
            // 重新打开会话；旧会话可能仍在相机端保持，直接复用即可。
            val sessionOk = openSession(PtpConstants.DEFAULT_SESSION_ID)
            AppLogger.d("recoverUsbEndpoint: session re-opened=$sessionOk")
            sessionOk
        } catch (e: Exception) {
            AppLogger.e("recoverUsbEndpoint failed", e)
            false
        }
    }

    /**
     * 在指定连接/端点上发送 CloseSession，不依赖当前 connection 字段。
     */
    private suspend fun closeSessionOnConnection(conn: UsbDeviceConnection, outEp: UsbEndpoint, inEp: UsbEndpoint) {
        try {
            val command = PtpCommand(PtpConstants.OPERATION_CLOSE_SESSION, nextTransactionId())
            val commandBytes = command.encode()
            val written = conn.bulkTransfer(outEp, commandBytes, commandBytes.size, PtpConstants.DEFAULT_TIMEOUT_MS)
            if (written == commandBytes.size) {
                readAllBulkPackets(conn, inEp, PtpConstants.DEFAULT_TIMEOUT_MS)
            }
        } catch (_: Exception) { }
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
        return sendCommandWithDataInternal(PtpCommand(code, nextTransactionId(), params), PtpConstants.DEFAULT_TIMEOUT_MS, expectRawPayload = false)
    }

    /**
     * Send a command and read the data phase with a custom timeout.
     * Used by live view to avoid long stalls when the loop is cancelled.
     *
     * @param expectRawPayload 设为 true 时跳过 readAllBulkPackets 的 PTP 容器长度
     *  early-exit 检查，用于实时取景等二进制 JPEG payload 读取，避免 JPEG 字节
     *  碰巧被误判为 PTP 容器导致只读到半帧就退出。
     */
    suspend fun sendCommandWithData(code: Short, timeoutMs: Int, expectRawPayload: Boolean = false, vararg params: Int): ByteArray {
        return sendCommandWithDataInternal(PtpCommand(code, nextTransactionId(), params), timeoutMs, expectRawPayload = expectRawPayload)
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

            // 使用完整包读取，避免多包响应被截断（Nikon D5200 等老机身常见）
            val responseBytes = readAllBulkPackets(conn, inEp, PtpConstants.DEFAULT_TIMEOUT_MS)
            AppLogger.d("bulkTransfer IN total returned: ${responseBytes.size}")
            if (responseBytes.size < 12) {
                // #region debug-point B:ptp-response-empty
                AppLogger.report("B", "UsbCameraConnection.kt:sendCommandInternal", "PTP response empty", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "length" to responseBytes.size.toString()))
                // #endregion
                throw IllegalStateException("Failed to read response")
            }
            AppLogger.logUsb("IN", responseBytes, responseBytes.size)
            return extractResponseContainer(responseBytes, command.transactionId)
        }

        val result = ptpLock.withLock {
            runCatching { transferOnce() }.getOrElse { firstError ->
                AppLogger.report("B", "UsbCameraConnection.kt:sendCommandInternal", "First attempt failed, retry", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "error" to (firstError.message ?: "unknown")))
                val failures = consecutiveBulkFailures.incrementAndGet()
                if (failures >= 3) {
                    AppLogger.w("sendCommandInternal: consecutive failures=$failures, attempting USB endpoint recovery")
                    if (recoverUsbEndpoint()) {
                        consecutiveBulkFailures.set(0)
                        return@withLock transferOnce()
                    }
                }
                delay(50)
                runCatching { transferOnce() }.getOrElse { throw firstError }
            }.also { consecutiveBulkFailures.set(0) }
        }
        // #region debug-point B:ptp-response
        AppLogger.report("B", "UsbCameraConnection.kt:sendCommandInternal", "PTP response", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "responseCode" to String.format(Locale.US, "0x%04X", result.first), "responseParams" to result.second.joinToString()))
        // #endregion
        result
    }

    /**
     * 从一次 bulk 读取结果中定位并解码 PTP Response 容器。
     * 某些相机会在响应前附带事件容器或粘包数据，这里遍历所有容器找到类型为 0x0003 的响应。
     *
     * 关键：Nikon 0x90C7 GET_EVENT 等 vendor 扩展命令的响应**只包含 Data 容器
     * 而没有 Response 容器**（Nikon MTP 私有协议）。如果一个命令的响应里没找到
     * Response 容器但找到了 Data 容器，说明这是事件类命令，应返回 0x2001 OK
     * （GET_EVENT 没有 Response 容器，Nikon D5200 实测验证），而不是错误地把
     * Data 容器当成 Response 解析（曾经导致 responseCode=0x90C7 与 6 个乱码 params）。
     *
     * @param expectedTid 当前命令的事务 ID；不匹配 TID 的 Response 容器会被跳过，
     *        防止追读时拾取到下一条命令的响应而造成错乱。
     */
    private fun extractResponseContainer(rawBytes: ByteArray, expectedTid: Int = 0): Pair<Short, IntArray> {
        var offset = 0
        var foundData = false
        var lastContainerType: Short = -1
        while (offset + 12 <= rawBytes.size) {
            // 重要：ByteBuffer.wrap(array, offset, length) 的 arrayOffset() 始终为 0，
            // absolute get 不会自动加上传入的 offset，必须手动在索引上加上 offset。
            val containerLength = readInt32(rawBytes, offset)
            val containerType = readInt16(rawBytes, offset + 4)
            lastContainerType = containerType
            if (containerLength < 12 || offset + containerLength > rawBytes.size) {
                //  malformed container, decode the first valid-looking response at offset
                return PtpCommand.decodeResponse(rawBytes.copyOfRange(offset, rawBytes.size.coerceAtMost(offset + 512)))
            }
            when (containerType) {
                PtpConstants.CONTAINER_TYPE_RESPONSE -> {
                    if (expectedTid != 0) {
                        val containerTid = readInt32(rawBytes, offset + 8)
                        if (containerTid != 0 && containerTid != expectedTid) {
                            AppLogger.d("extractResponseContainer: skip TID mismatch response tid=$containerTid expected=$expectedTid at offset=$offset")
                            offset += containerLength
                            continue
                        }
                    }
                    return PtpCommand.decodeResponse(rawBytes.copyOfRange(offset, offset + containerLength))
                }
                PtpConstants.CONTAINER_TYPE_DATA -> {
                    // Data 容器（特别是 GET_EVENT 的响应）单独存在时不需要解码为 Response，
                    // 仅记录已发现 Data 用于兜底判断
                    foundData = true
                    offset += containerLength
                }
                else -> {
                    // 事件/Command 容器等跳过
                    offset += containerLength
                }
            }
        }
        // 兜底：仅含 Data 容器（无 Response）的情况——Nikon GET_EVENT 等命令就是这种格式。
        // 视为成功 0x2001 OK，避免把 Data 容器误解码为 Response（曾导致 responseCode=0x90C7 与 6 个乱码 params）
        if (foundData && lastContainerType == PtpConstants.CONTAINER_TYPE_DATA) {
            AppLogger.d("extractResponseContainer: only Data container found (event-like command), assume OK")
            return 0x2001.toShort() to intArrayOf()
        }
        // 兜底：按原始数据解码
        return PtpCommand.decodeResponse(rawBytes)
    }

    /**
     * 从一次或多次 bulk 读取结果中解析 Data 容器与 Response 容器。
     * 返回剥离 Response 后的 dataBytes（保留 Data 容器头部供 decodeDataPayload 处理）
     * 以及 Response code；若未找到 Response 则返回 -1。
     *
     * @param expectedTid 当前命令的事务 ID，用于丢弃不匹配 TID 的容器（防止追读
     *        200ms 时拾取到下一条命令的 Response 导致数据串台）。传 0 时跳过校验。
     */
    private fun extractDataAndResponse(rawBytes: ByteArray, expectedTid: Int = 0): Pair<ByteArray, Short> {
        var responseCode: Short = -1
        val result = ByteArrayOutputStream(rawBytes.size)
        var offset = 0
        var containerCount = 0
        var discardedTidMismatch = 0
        while (offset + 12 <= rawBytes.size) {
            // 重要：ByteBuffer.wrap(array, offset, length) 的 arrayOffset() 始终为 0，
            // absolute get 不会自动加上传入的 offset，必须手动在索引上加上 offset。
            val containerLength = readInt32(rawBytes, offset)
            val containerType = readInt16(rawBytes, offset + 4)
            containerCount++
            if (containerLength < 12 || offset + containerLength > rawBytes.size) {
                // 容器长度异常，剩余数据作为原始 payload 保留
                AppLogger.d("extractDataAndResponse: malformed container at offset=$offset length=$containerLength total=${rawBytes.size}")
                result.write(rawBytes, offset, rawBytes.size - offset)
                break
            }
            // TID 校验：仅对 Data/Response 容器进行匹配。事件容器 0x0004 不带 TID
            // （保留 0 或 0xFFFFFFFF），不应校验；其他厂商特定容器若带 TID 不匹配，
            // 也应丢弃，防止追读 200ms 时拾取到下个命令的响应而造成数据错乱。
            val containerTid = if (containerType == PtpConstants.CONTAINER_TYPE_DATA ||
                containerType == PtpConstants.CONTAINER_TYPE_RESPONSE
            ) readInt32(rawBytes, offset + 8) else 0
            if (expectedTid != 0 && containerTid != 0 && containerTid != expectedTid) {
                discardedTidMismatch++
                AppLogger.d("extractDataAndResponse: skip TID mismatch container type=${String.format(Locale.US, "0x%04X", containerType)} tid=$containerTid expected=$expectedTid length=$containerLength")
                offset += containerLength
                continue
            }
            when (containerType) {
                PtpConstants.CONTAINER_TYPE_RESPONSE -> {
                    responseCode = readInt16(rawBytes, offset + 6)
                    AppLogger.d("extractDataAndResponse: found Response container #$containerCount code=${String.format(Locale.US, "0x%04X", responseCode)} offset=$offset")
                    offset += containerLength
                    break
                }
                PtpConstants.CONTAINER_TYPE_DATA -> {
                    result.write(rawBytes, offset, containerLength)
                    offset += containerLength
                }
                else -> {
                    // 事件或其他容器直接跳过
                    AppLogger.d("extractDataAndResponse: skip container type=${String.format(Locale.US, "0x%04X", containerType)} length=$containerLength")
                    offset += containerLength
                }
            }
        }

        var dataBytes = result.toByteArray()
        // 兼容：响应容器可能粘在 dataBytes 尾部
        if (responseCode == (-1).toShort() && dataBytes.size >= 12) {
            val tailOffset = dataBytes.size - 12
            if (readInt16(dataBytes, tailOffset + 4) == PtpConstants.CONTAINER_TYPE_RESPONSE) {
                val tailLength = readInt32(dataBytes, tailOffset)
                if (tailLength == 12 && tailOffset + tailLength == dataBytes.size) {
                    val tailTid = readInt32(dataBytes, tailOffset + 8)
                    if (expectedTid == 0 || tailTid == 0 || tailTid == expectedTid) {
                        responseCode = readInt16(dataBytes, tailOffset + 6)
                        AppLogger.d("extractDataAndResponse: found Response at tail code=${String.format(Locale.US, "0x%04X", responseCode)}")
                        dataBytes = dataBytes.copyOf(tailOffset)
                    } else {
                        AppLogger.d("extractDataAndResponse: tail Response TID mismatch tid=$tailTid expected=$expectedTid, keep dataBytes as-is")
                    }
                }
            }
        }
        if (responseCode == (-1).toShort()) {
            AppLogger.d("extractDataAndResponse: no Response found in ${rawBytes.size} bytes, containers=$containerCount discardedTid=$discardedTidMismatch")
        }
        return dataBytes to responseCode
    }

    /**
     * Little-endian 32 位整数读取（不再依赖 ByteBuffer.wrap 的 arrayOffset 行为）。
     */
    private fun readInt32(buf: ByteArray, index: Int): Int =
        ((buf[index].toInt() and 0xFF)) or
            ((buf[index + 1].toInt() and 0xFF) shl 8) or
            ((buf[index + 2].toInt() and 0xFF) shl 16) or
            ((buf[index + 3].toInt() and 0xFF) shl 24)

    /**
     * Little-endian 16 位整数读取（不再依赖 ByteBuffer.wrap 的 arrayOffset 行为）。
     */
    private fun readInt16(buf: ByteArray, index: Int): Short =
        (((buf[index].toInt() and 0xFF)) or
            ((buf[index + 1].toInt() and 0xFF) shl 8)).toShort()

    /**
     * 按 512 字节包读取一次 USB bulk 传输，直到遇到短包或零长度包。
     * 只读取一个传输阶段（Data 或 Response），不跨阶段阻塞，避免 D5200 等
     * 老机身在 Data 阶段结束后长时间等待 Response 而触发 USB 断开。
     */
    private suspend fun readAllBulkPackets(conn: UsbDeviceConnection, inEp: UsbEndpoint, timeoutMs: Int, expectRawPayload: Boolean = false): ByteArray = withContext(Dispatchers.IO) {
        val packetBuffer = ByteArray(USB_BULK_MAX_PACKET_SIZE)
        val result = ByteArrayOutputStream(16384)
        while (true) {
            val length = conn.bulkTransfer(inEp, packetBuffer, packetBuffer.size, timeoutMs)
            if (length < 0) {
                // 超时或端点错误，结束读取
                break
            }
            if (length > 0) {
                result.write(packetBuffer, 0, length)
            }
            // 短包（<512）或零长度包（ZLP）表示本次传输结束
            if (length < USB_BULK_MAX_PACKET_SIZE) {
                break
            }
            // 关键修复：实时取景的 JPEG 长度常常正好是 512 的整数倍，
            // 相机不会发送 short packet 作为结束标记，循环会一直等到 timeoutMs
            // （默认 300ms）才退出，导致每帧都额外等 300ms，实时取景卡顿。
            //
            // **但要避免 liveview 误判**——JPEG 字节内容碰巧（FFD8 开头、第 4-5 字节
            // 是 0x0001~0x0004 容器类型、第 0-3 字节是合理 length）会被错误地当成合法
            // PTP 容器提前退出，导致只读到半帧 JPEG 后 BitmapFactory 解码失败、累计
            // consecutiveFrameFailures 触发 liveview 退出。
            //
            // 解决：仅当调用方明确知道这是 PTP 命令响应（expectRawPayload = false）
            // 时才使用容器长度 early-exit；liveview frame pulling 等二进制 payload
            // 读取时传 expectRawPayload = true 跳过此检查。
            if (expectRawPayload) {
                continue
            }
            val cur = result.toByteArray()
            if (cur.size >= 12) {
                val containerLength = readInt32(cur, 0)
                val containerType = readInt16(cur, 4)
                val isKnownType = containerType == PtpConstants.CONTAINER_TYPE_DATA ||
                    containerType == PtpConstants.CONTAINER_TYPE_RESPONSE ||
                    containerType == PtpConstants.CONTAINER_TYPE_EVENT ||
                    containerType == PtpConstants.CONTAINER_TYPE_COMMAND
                if (isKnownType && containerLength in 12..(cur.size + USB_BULK_MAX_PACKET_SIZE)) {
                    if (cur.size >= containerLength) {
                        // 已收集到完整容器，相机可能因 JPEG 凑齐 512 倍数而不再发 short packet
                        break
                    }
                }
            }
        }
        result.toByteArray()
    }

    private suspend fun sendCommandWithDataInternal(
        command: PtpCommand,
        timeoutMs: Int = PtpConstants.DEFAULT_TIMEOUT_MS,
        expectRawPayload: Boolean = false
    ): ByteArray = withContext(Dispatchers.IO) {
        val conn = connection ?: throw IllegalStateException("USB not connected")
        val outEp = bulkOutEndpoint ?: throw IllegalStateException("Output endpoint not found")
        val inEp = bulkInEndpoint ?: throw IllegalStateException("Input endpoint not found")

        val commandBytes = command.encode()
        val expectedTid = command.transactionId
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

            // 先读取 Data 阶段（短包结束），避免长时间阻塞导致老机身断开。
            // 实时取景读取 JPEG 时传 expectRawPayload=true，跳过 PTP 容器长度
            // early-exit 检查，避免 JPEG 字节碰巧被误判为容器导致半截退出。
            val dataPhase = readAllBulkPackets(conn, inEp, timeoutMs, expectRawPayload)
            // 仅当 dataPhase 末尾为 Data 容器（无 Response 紧跟）时再追读一次 Response，
            // 避免误读下个命令的响应导致 TID 错乱。
            val needMoreResponse = dataPhase.isNotEmpty() && !endsWithResponse(dataPhase)
            val responsePhase = if (needMoreResponse) {
                runCatching { readAllBulkPackets(conn, inEp, 200) }.getOrDefault(ByteArray(0))
            } else ByteArray(0)
            val rawBytes = dataPhase + responsePhase

            AppLogger.d("bulkTransfer IN total returned: ${rawBytes.size}")
            AppLogger.logUsb("IN", rawBytes, rawBytes.size)

            val (dataBytes, responseCode) = extractDataAndResponse(rawBytes, expectedTid)
            // #region debug-point B:ptp-data-response
            AppLogger.report("B", "UsbCameraConnection.kt:sendCommandWithDataInternal", "PTP data response", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "responseCode" to String.format(Locale.US, "0x%04X", responseCode), "bytes" to dataBytes.size.toString()))
            // #endregion
            return dataBytes to responseCode
        }

        val (data, responseCode) = ptpLock.withLock {
            runCatching { transferOnce() }.getOrElse { firstError ->
                val failures = consecutiveBulkFailures.incrementAndGet()
                if (failures >= 3) {
                    AppLogger.w("sendCommandWithDataInternal: consecutive failures=$failures, attempting USB endpoint recovery")
                    if (recoverUsbEndpoint()) {
                        consecutiveBulkFailures.set(0)
                        return@withLock transferOnce()
                    }
                }
                delay(50)
                runCatching { transferOnce() }.getOrElse { throw firstError }
            }.also { consecutiveBulkFailures.set(0) }
        }
        if (responseCode != PtpConstants.RESPONSE_OK && responseCode.toInt() != -1) {
            AppLogger.report("B", "UsbCameraConnection.kt:sendCommandWithDataInternal", "Non-OK response", mapOf("op" to String.format(Locale.US, "0x%04X", command.operationCode), "responseCode" to String.format(Locale.US, "0x%04X", responseCode)))
        }
        data
    }

    /**
     * 检查 dataPhase 末尾是否已经包含一个 Response 容器（Data+Response 粘包）。
     * 仅看最后 12 字节，避免被前置 Data 容器干扰。
     */
    private fun endsWithResponse(dataPhase: ByteArray): Boolean {
        if (dataPhase.size < 12) return false
        val offset = dataPhase.size - 12
        val length = readInt32(dataPhase, offset)
        // 长度必须覆盖到 dataPhase 末尾，且容器类型为 Response
        if (offset + length != dataPhase.size) return false
        val type = readInt16(dataPhase, offset + 4)
        return type == PtpConstants.CONTAINER_TYPE_RESPONSE
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

    /**
     * Read a PTP device property.
     *
     * 关键：必须按属性的**声明数据宽度和符号性**解析，而不是按 payload 实际长度。
     * 历史 bug：0x5010 ExposureCompensation 是 INT16 1/1000 EV，相机在 -3..+3 范围内
     * 回读 -1（0xFFFF）时，原来的 `buffer.getShort().toInt() and 0xFFFF` 错误地
     * 把 0xFFFF 当作 UINT16 解析成 65535，再 / 1000f = +65.5 EV，UI 显示"+60 多"溢出。
     * 修复：根据属性码的固定表（与 encodePropertyValue 对称）决定按 INT8/UINT8/INT16/UINT16/INT32/UINT32 解析。
     */
    override suspend fun getDeviceProperty(code: Short): Int? {
        // #region debug-point C:property-get
        AppLogger.report("C", "UsbCameraConnection.kt:getDeviceProperty", "Getting property", mapOf("code" to String.format(Locale.US, "0x%04X", code)))
        // #endregion
        val data = sendCommandWithDataInternal(
            PtpCommand(PtpConstants.OPERATION_DEVICE_PROP_VALUE_GET, nextTransactionId(), intArrayOf(code.toInt()))
        )
        var payload = PtpCommand.decodeDataPayload(data)
        // 部分老机身（如 Nikon D5200）GetDevicePropValue 只返回 Response 而不带 Data，
        // 此时回退到 GetDevicePropDesc，从描述符中读取 Current Value。
        if (payload.isEmpty()) {
            AppLogger.report("C", "UsbCameraConnection.kt:getDeviceProperty", "Empty data, fallback to descriptor", mapOf("code" to String.format(Locale.US, "0x%04X", code)))
            val descPayload = getDevicePropertyDesc(code)
            val descValue = parseCurrentValueFromDescriptor(descPayload)
            if (descValue != null) {
                // #region debug-point C:property-get-result
                AppLogger.report("C", "UsbCameraConnection.kt:getDeviceProperty", "Property value from descriptor", mapOf("code" to String.format(Locale.US, "0x%04X", code), "value" to descValue.toString()))
                // #endregion
                return descValue
            }
        }
        val value = if (payload.isEmpty()) null else {
            // 按属性码的固定宽度+符号表解析（与 encodePropertyValue 对称），
            // 而非按 payload.size，避免 Nikon 等老机身返回的 payload 长度与
            // 实际属性数据宽度不一致时（如 padding/对齐）被错读。
            parseTypedPropertyValue(code, payload)
        }
        // #region debug-point C:property-get-result
        AppLogger.report("C", "UsbCameraConnection.kt:getDeviceProperty", "Property value", mapOf("code" to String.format(Locale.US, "0x%04X", code), "value" to (value?.toString() ?: "null"), "payloadSize" to payload.size.toString()))
        // #endregion
        return value
    }

    /**
     * 按属性码声明的数据类型（宽度 + 符号）解析 payload。
     * 关键：把已知 INT16 属性的 0xFFFF 解析为 -1 而不是 65535，
     * 避免 0x5010 ExposureCompensation 把负值错算成 +65535 / 1000f = +65.5 EV。
     */
    private fun parseTypedPropertyValue(code: Short, payload: ByteArray): Int? {
        if (payload.isEmpty()) return null
        val spec = propertyTypeSpecs[code.toInt() and 0xFFFF]
        val width: Int
        val signed: Boolean
        if (spec != null) {
            width = spec.first
            signed = spec.second
        } else {
            // 未知属性：回退到按 payload 长度推断（保留历史行为），尽量用 INT 类型以减少溢出误读
            width = when {
                payload.size == 1 -> 1
                payload.size == 2 -> 2
                payload.size == 4 -> 4
                else -> 4
            }
            signed = when (width) {
                1, 2, 4 -> true
                else -> true
            }
        }
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            when (width) {
                1 -> if (signed) bb.get().toInt() else (bb.get().toInt() and 0xFF)
                2 -> if (signed) bb.getShort().toInt() else (bb.getShort().toInt() and 0xFFFF)
                4 -> if (signed) bb.getInt() else bb.getInt()
                8 -> bb.getInt()
                else -> bb.getInt()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 属性码 → (字节宽度, 是否有符号) 的固定映射表。
     * 与 [encodePropertyValue] 保持一致；新增属性时务必同时更新两个映射。
     * 规则参考 digiCamControl 的 NikonPtpCodec 与 PIMA15740 PTP 规范：
     *  - 标准 PTP 属性多为 INT16/UINT16，少数 INT32/UINT32（如 ExposureTime）
     *  - Nikon 私有 0xD124 FlashExposureCompensation 为 INT16 1/8 EV
     */
    private val propertyTypeSpecs: Map<Int, Pair<Int, Boolean>> = mapOf(
        0x5001 to (1 to false), // BatteryLevel        UINT8
        0x5002 to (1 to false), // FunctionalMode      UINT8
        0x5003 to (4 to false), // ImageSize           UINT32
        0x5004 to (4 to false), // CompressionSetting  UINT32
        0x5005 to (2 to false), // WhiteBalance        UINT16
        0x5007 to (2 to false), // FNumber             UINT16 (raw 1/100 f-stop)
        0x500B to (2 to false), // ExposureMeteringMode UINT16
        0x500D to (4 to false), // ExposureTime        UINT32 (1/10000 s 或 1/10 s)
        0x500E to (2 to false), // ExposureProgramMode UINT16
        0x500F to (2 to false), // ExposureISO         UINT16
        0x5010 to (2 to true),  // ExposureCompensation **INT16 1/1000 EV** （修复点：有符号）
        0x5011 to (1 to false), // ExposureBiasCompensation UINT8
        0x5013 to (1 to false), // FocusMode           UINT8
        0x5014 to (4 to false), // FocusMeteringMode   UINT32
        0x501A to (2 to false), // FocusMode2          UINT16
        0x501C to (2 to false), // FlashMode           UINT16
        0xD024 to (2 to true),  // Nikon ExposureCompensation (alt)  INT16
        0xD100 to (4 to false), // Nikon ShutterSpeed   UINT32
        0xD10B to (1 to false), // Nikon RecordingMedia UINT8
        0xD124 to (2 to true),  // Nikon FlashExposureCompensation **INT16 1/8 EV** （有符号）
        0xD138 to (2 to false), // Nikon ShootingMode   UINT16
        0xD1A2 to (1 to false), // Nikon LiveViewStatus UINT8
        0xD1A4 to (4 to false), // Nikon LiveViewProhibitCondition UINT32
        0xD400 to (4 to false), // Nikon ShotsRemaining UINT32
        0xD064 to (2 to false), // Nikon FlashMode      UINT16
        0xD065 to (2 to true)   // Nikon FlashExposureCompensation (Nikon MTP 私有) INT16
    )

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

    /**
     * 从 GetDevicePropDesc 返回的描述符 payload（已去掉 12 字节 Data 头）中解析当前值。
     * 描述符结构：DevicePropCode(2) + DataType(2) + GetSet(1) + FactoryDefault + CurrentValue + ...
     *
     * 关键：必须按 DataType 决定符号性（INT* vs UINT*）来解析。
     * 历史 bug：line 982 之前固定 `bb.getShort().toInt() and 0xFFFF` 把 INT16 负值
     * 错算成正 UINT16（例如 0xFFFF 解析成 65535），导致 0x5010 ExposureCompensation
     * 回读 -1 EV 时被显示成 +65.5 EV（用户报告"+60 多"溢出的根因之一）。
     */
    private fun parseCurrentValueFromDescriptor(desc: ByteArray): Int? {
        if (desc.size < 5) return null
        return try {
            val bb = ByteBuffer.wrap(desc).order(ByteOrder.LITTLE_ENDIAN)
            val propCode = bb.getShort().toInt() and 0xFFFF
            val dataType = bb.getShort().toInt() and 0xFFFF
            bb.get() // GetSet
            val (valueSize, signed) = when (dataType) {
                0x0001 -> 1 to true   // INT8
                0x0002 -> 1 to false  // UINT8
                0x0003 -> 2 to true   // INT16
                0x0004 -> 2 to false  // UINT16
                0x0005 -> 4 to true   // INT32
                0x0006 -> 4 to false  // UINT32
                0x0007 -> 8 to true   // INT64
                0x0008 -> 8 to false  // UINT64
                else -> {
                    AppLogger.d("parseCurrentValueFromDescriptor: unsupported data type 0x${dataType.toString(16)} for 0x${propCode.toString(16)}")
                    return null
                }
            }
            if (bb.remaining() < valueSize * 2) return null
            bb.position(bb.position() + valueSize) // skip factory default
            when (valueSize) {
                1 -> if (signed) bb.get().toInt() else (bb.get().toInt() and 0xFF)
                2 -> if (signed) bb.getShort().toInt() else (bb.getShort().toInt() and 0xFFFF)
                4 -> if (signed) bb.getInt() else bb.getInt() // 32 位无符号与 Int 等价
                8 -> if (signed) bb.getLong().toInt() else (bb.getLong() and 0x7FFFFFFFFFFFFFFFL).toInt()
                else -> null
            }
        } catch (e: Exception) {
            AppLogger.d("parseCurrentValueFromDescriptor failed: ${e.message}")
            null
        }
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

        // Response phase: read complete response container(s)
        val responseBytes = readAllBulkPackets(conn, inEp, PtpConstants.DEFAULT_TIMEOUT_MS)
        if (responseBytes.size < 12) throw IllegalStateException("Failed to read response")
        extractResponseContainer(responseBytes)
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

    private companion object {
        const val USB_BULK_MAX_PACKET_SIZE = 512

        private const val ACTION_USB_PERMISSION = "com.phtontools.phtonview.USB_PERMISSION"

        // Known PTP / MTP camera vendor IDs
        private val SUPPORTED_VENDOR_IDS = intArrayOf(
            0x04B0, // Nikon
            0x04A9, // Canon
            0x054C, // Sony
            0x04CB, // Fuji
            0x04DA, // Panasonic
            0x040A, // Kodak
            0x0E8D, // Some Chinese brands
            0x07B4, // Olympus / OM System
            0x05CA, // Ricoh / Pentax
            0x1A98, // Leica
            0x1005, // Sigma
            0x1E57  // Tamron
        )
    }
}
