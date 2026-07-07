package com.phtontools.phtonview.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.phtontools.phtonview.connection.CameraConnection
import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.data.model.*
import com.phtontools.phtonview.ndk.Gphoto2Bridge
import com.phtontools.phtonview.usb.UsbCameraConnection
import com.phtontools.phtonview.usb.ptp.BrandStrategy
import com.phtontools.phtonview.usb.ptp.GenericStrategy
import com.phtontools.phtonview.usb.ptp.PtpCommand
import com.phtontools.phtonview.usb.ptp.PtpConstants
import com.phtontools.phtonview.usb.ptp.PtpValueMapper
import com.phtontools.phtonview.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CameraRepository 实现，封装相机通信与状态管理。
 */
@Singleton
class CameraRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val connections: Set<@JvmSuppressWildcards CameraConnection>
) : CameraRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val liveViewDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhtonView-LiveView")
    }.asCoroutineDispatcher()
    private val connectionLock = Mutex()
    private var liveViewJob: Job? = null
    private var intervalometerJob: Job? = null
    private var bulbJob: Job? = null
    private var connectionStateJob: Job? = null
    private var connectionStateTarget: CameraConnection? = null
    private var eventLoopJob: Job? = null

    /**
     * 根据相机型号/能力选择使用标准 0x500D 还是尼康 0xD100 快门属性，
     * 避免两个属性同时写入导致显示与实际不一致。
     */
    private var preferredShutterProperty: Short = PtpConstants.DEVICE_PROP_EXPOSURE_TIME

    /**
     * 当前连接的品牌策略，连接成功后根据 DeviceInfo 设置。
     */
    private var brandStrategy: BrandStrategy = GenericStrategy

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _liveViewFrame = MutableStateFlow<Bitmap?>(null)
    override val liveViewFrame: StateFlow<Bitmap?> = _liveViewFrame

    private val _exposureSettings = MutableStateFlow(ExposureSettings())
    override val exposureSettings: StateFlow<ExposureSettings> = _exposureSettings

    private val _cameraSettings = MutableStateFlow(CameraSettings())
    override val cameraSettings: StateFlow<CameraSettings> = _cameraSettings

    private val _meteringResult = MutableStateFlow(MeteringResult())
    override val meteringResult: StateFlow<MeteringResult> = _meteringResult

    private val _focusMode = MutableStateFlow(FocusMode.AF)
    override val focusMode: StateFlow<FocusMode> = _focusMode

    private val _afMode = MutableStateFlow(AfMode.AF_S)
    override val afMode: StateFlow<AfMode> = _afMode

    private val _afAreaMode = MutableStateFlow(AfAreaMode.SinglePoint)
    override val afAreaMode: StateFlow<AfAreaMode> = _afAreaMode

    private val _focusMagnification = MutableStateFlow(1f)
    override val focusMagnification: StateFlow<Float> = _focusMagnification

    private val _focusPeakingEnabled = MutableStateFlow(false)
    override val focusPeakingEnabled: StateFlow<Boolean> = _focusPeakingEnabled

    private val _detectedUsbDevice = MutableStateFlow<String?>(null)
    override val detectedUsbDevice: StateFlow<String?> = _detectedUsbDevice

    private val _cameraStatus = MutableStateFlow(CameraStatus())
    override val cameraStatus: StateFlow<CameraStatus> = _cameraStatus

    private val _intervalometer = MutableStateFlow(IntervalometerSettings())
    override val intervalometer: StateFlow<IntervalometerSettings> = _intervalometer

    private val _bulbSettings = MutableStateFlow(BulbSettings())
    override val bulbSettings: StateFlow<BulbSettings> = _bulbSettings

    private val _timerSettings = MutableStateFlow(TimerSettings())
    override val timerSettings: StateFlow<TimerSettings> = _timerSettings

    private val _aebSettings = MutableStateFlow(AebSettings())
    override val aebSettings: StateFlow<AebSettings> = _aebSettings

    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    override val photos: StateFlow<List<PhotoItem>> = _photos

    private val _liveViewEnabled = MutableStateFlow(false)
    override val liveViewEnabled: StateFlow<Boolean> = _liveViewEnabled

    private val _burstRunning = MutableStateFlow(false)
    override val burstRunning: StateFlow<Boolean> = _burstRunning

    private var currentConnection: CameraConnection? = null
    private var currentModelName: String = ""
    private var preBulbShutter: String = "1/125"

    init {
        // 始终使用通用品牌，不再按品牌选择
        _cameraSettings.value = _cameraSettings.value.copy(
            brand = CameraBrand.Generic,
            connectionType = settingsManager.connectionType
        )
        scope.launch {
            switchConnection(resolveConnection(settingsManager.connectionType), autoConnect = false)
            // 若上次使用 Wi-Fi 且保存过配对地址，启动时自动恢复配对
            if (settingsManager.connectionType == ConnectionType.WiFi) {
                settingsManager.wifiPairedAddress?.takeIf { it.isNotBlank() }?.let { address ->
                    pairWifi(address, WifiBrandPreset.forAddress(address, settingsManager.cameraBrand))
                }
            }
        }

        scope.launch {
            val usbConnection = connections.filterIsInstance<UsbCameraConnection>().firstOrNull()
            usbConnection?.detectedDevice?.collect { device ->
                _detectedUsbDevice.value = device
            }
        }

        // 初始化 libgphoto2 桥接与模块
        scope.launch {
            try {
                Gphoto2Bridge.init(context)
            } catch (e: Exception) {
                AppLogger.e("Failed to initialize gphoto2 bridge", e)
            }
        }
    }

    private fun resolveConnection(type: ConnectionType): CameraConnection? {
        // 优先精确匹配；否则使用通用连接或第一个可用连接
        return connections.firstOrNull { it.brand == CameraBrand.Generic && it.connectionType == type }
            ?: connections.firstOrNull { it.connectionType == type }
            ?: connections.firstOrNull()
    }

    private fun collectConnectionState(connection: CameraConnection): Job {
        return scope.launch {
            connection.connectionState.collect { state ->
                _connectionState.value = when (state) {
                    is CameraConnection.ConnectionState.Disconnected -> ConnectionState.Disconnected
                    is CameraConnection.ConnectionState.Connecting -> ConnectionState.Connecting
                    is CameraConnection.ConnectionState.Connected -> {
                        val cleanModel = cleanModelName(state.model)
                        currentModelName = cleanModel
                        // 先发布 Connected 状态，确保后续品牌初始化、实时取景等逻辑能读到已连接。
                        _connectionState.value = ConnectionState.Connected(cleanModel)

                        // 综合 DeviceInfo VendorExtensionID 和型号名识别品牌，任一命中即采用对应策略
                        val vendorIdBrand = detectBrandFromDeviceInfo(connection)
                        val modelBrand = detectBrand(state.model)
                        val detectedBrand = if (modelBrand != CameraBrand.Generic) modelBrand else vendorIdBrand
                        brandStrategy = BrandStrategy.forBrand(detectedBrand)
                        _cameraSettings.value = _cameraSettings.value.copy(brand = detectedBrand)
                        AppLogger.report("J", "CameraRepositoryImpl.kt:collectConnectionState", "Brand detected", mapOf("brand" to detectedBrand.name, "rawModel" to state.model, "cleanModel" to cleanModel, "vendorIdBrand" to vendorIdBrand.name))

                        // 根据品牌策略初始化：尼康额外探测属性，其他品牌直接采用策略中的快门码
                        runCatching {
                            if (detectedBrand == CameraBrand.Nikon) {
                                inspectNikonProperties()
                                selectShutterProperty(connection)
                            } else {
                                preferredShutterProperty = brandStrategy.primaryShutterProperty
                                AppLogger.report("J", "CameraRepositoryImpl.kt:collectConnectionState", "Use brand shutter", mapOf("property" to String.format(Locale.US, "0x%04X", preferredShutterProperty)))
                            }
                        }.onFailure {
                            AppLogger.report("J", "CameraRepositoryImpl.kt:collectConnectionState", "Brand init failed, fallback", mapOf("error" to (it.message ?: "unknown")))
                            preferredShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
                        }
                        // 不再连接成功后自动开启实时取景，避免尼康等机身在 PC 模式下
                        // 被强制切到取景状态而导致功能不可用；由用户手动开启。
                        _liveViewEnabled.value = false

                        // 对需要主动轮询事件的品牌启动事件循环，防止事件积压导致后续命令无响应
                        if (brandStrategy.eventPollOperation != null) {
                            startEventLoop()
                        }
                        _connectionState.value
                    }
                    is CameraConnection.ConnectionState.Error -> ConnectionState.Error(state.message)
                }
            }
        }
    }

    private suspend fun detectBrandFromDeviceInfo(connection: CameraConnection): CameraBrand {
        val raw = runCatching { connection.getDeviceInfoRaw() }.getOrDefault(ByteArray(0))
        val vendorId = if (raw.size >= 12) PtpCommand.decodeVendorExtensionId(raw) else null
        AppLogger.report("J", "CameraRepositoryImpl.kt:detectBrandFromDeviceInfo", "VendorExtensionID", mapOf("id" to String.format(Locale.US, "0x%08X", vendorId ?: 0), "rawSize" to raw.size.toString()))
        // 0x06 是 Microsoft/MTP 通用扩展 ID，不能唯一代表 Olympus；
        // 品牌回退优先依赖型号名识别，避免 Nikon 等 MTP 设备被误判。
        return when (vendorId) {
            PtpConstants.VENDOR_EXTENSION_NIKON -> CameraBrand.Nikon
            PtpConstants.VENDOR_EXTENSION_CANON -> CameraBrand.Canon
            PtpConstants.VENDOR_EXTENSION_SONY -> CameraBrand.Sony
            PtpConstants.VENDOR_EXTENSION_FUJI -> CameraBrand.Fuji
            PtpConstants.VENDOR_EXTENSION_PANASONIC -> CameraBrand.Panasonic
            PtpConstants.VENDOR_EXTENSION_OLYMPUS,
            PtpConstants.VENDOR_EXTENSION_OLYMPUS_OMD -> CameraBrand.Olympus
            PtpConstants.VENDOR_EXTENSION_KODAK -> CameraBrand.Kodak
            else -> CameraBrand.Generic
        }
    }

    /**
     * 清理 PTP DeviceInfo 返回的型号名，去掉常见厂商前缀，便于 UI 展示。
     * 例如 "NIKON DSC D5200" -> "D5200"，"Canon EOS R5" -> "EOS R5"。
     * 清洗逻辑保守，仅处理已知前缀，避免误伤自定义名称。
     */
    private fun cleanModelName(model: String): String {
        if (model.isBlank()) return model
        val trimmed = model.trim()
        val upper = trimmed.uppercase()
        val prefixes = listOf(
            "NIKON DSC ", "NIKON ",
            "CANON ",
            "SONY ",
            "FUJIFILM ", "FUJI ",
            "PANASONIC ", "LUMIX ",
            "OLYMPUS ",
            "PENTAX ",
            "RICOH ",
            "SIGMA ",
            "TAMRON ",
            "LEICA ",
            "HASSELBLAD "
        )
        for (prefix in prefixes) {
            if (upper.startsWith(prefix)) {
                return trimmed.substring(prefix.length).trim().takeIf { it.isNotBlank() } ?: trimmed
            }
        }
        return trimmed
    }

    private fun detectBrand(model: String): CameraBrand {
        val upper = model.uppercase()
        return when {
            upper.contains("NIKON") -> CameraBrand.Nikon
            upper.startsWith("D") && upper.length >= 3 && upper.substring(1, 2).toIntOrNull() != null -> CameraBrand.Nikon
            upper.matches(Regex("^Z\\d.*")) -> CameraBrand.Nikon
            upper.startsWith("COOLPIX") -> CameraBrand.Nikon
            upper.contains("CANON") -> CameraBrand.Canon
            upper.contains("SONY") -> CameraBrand.Sony
            upper.contains("FUJI") || upper.contains("FUJIFILM") -> CameraBrand.Fuji
            upper.contains("LUMIX") || upper.contains("PANASONIC") -> CameraBrand.Panasonic
            upper.contains("OLYMPUS") || upper.startsWith("E-") || upper.startsWith("EM") -> CameraBrand.Olympus
            upper.contains("PENTAX") || upper.startsWith("K-") || upper.startsWith("KP") || upper.startsWith("K3") || upper.startsWith("K5") || upper.startsWith("K7") || upper.startsWith("KS") -> CameraBrand.Pentax
            upper.contains("RICOH") || upper.startsWith("GR") -> CameraBrand.Ricoh
            upper.contains("LEICA") || upper.startsWith("M") && upper.length >= 2 && upper[1].isDigit() -> CameraBrand.Leica
            upper.contains("SIGMA") || upper.startsWith("FP") -> CameraBrand.Sigma
            upper.contains("TAMRON") -> CameraBrand.Tamron
            upper.contains("KODAK") -> CameraBrand.Kodak
            else -> CameraBrand.Generic
        }
    }

    /**
     * 根据属性描述符判断该属性是否可写。
     * PTP DevicePropDesc 格式：Code(2) + Type(2) + GetSet(1) + ...
     */
    private fun isPropertyWritable(desc: ByteArray): Boolean {
        return desc.size >= 5 && (desc[4].toInt() and 0xFF) == 1
    }

    /**
     * 尼康机身有两种快门属性：标准 0x500D 和厂商 0xD100。
     * 连接后根据属性描述符的可写标志选择实际使用哪一个，避免两个都写造成冲突。
     */
    private suspend fun selectShutterProperty(conn: CameraConnection) {
        val standardDesc = conn.getDevicePropertyDesc(PtpConstants.DEVICE_PROP_EXPOSURE_TIME)
        val nikonDesc = conn.getDevicePropertyDesc(PtpConstants.DEVICE_PROP_NIKON_EXPOSURE_TIME)
        val standardWritable = isPropertyWritable(standardDesc)
        val nikonWritable = isPropertyWritable(nikonDesc)
        preferredShutterProperty = when {
            !standardWritable && nikonWritable -> PtpConstants.DEVICE_PROP_NIKON_EXPOSURE_TIME
            else -> PtpConstants.DEVICE_PROP_EXPOSURE_TIME
        }
        AppLogger.report("J", "CameraRepositoryImpl.kt:selectShutterProperty", "Selected shutter property", mapOf(
            "property" to String.format(Locale.US, "0x%04X", preferredShutterProperty),
            "standardWritable" to standardWritable.toString(),
            "nikonWritable" to nikonWritable.toString()
        ))
    }

    private fun inspectNikonProperties() {
        scope.launch {
            val conn = currentConnection ?: return@launch
            val codes = listOf(
                PtpConstants.DEVICE_PROP_ISO,
                PtpConstants.DEVICE_PROP_F_NUMBER,
                PtpConstants.DEVICE_PROP_EXPOSURE_TIME,
                PtpConstants.DEVICE_PROP_NIKON_EXPOSURE_TIME,
                PtpConstants.DEVICE_PROP_EXPOSURE_COMPENSATION,
                PtpConstants.DEVICE_PROP_METERING_MODE,
                PtpConstants.DEVICE_PROP_NIKON_LIVE_VIEW_STATUS,
                PtpConstants.DEVICE_PROP_NIKON_LIVE_VIEW_PROHIBIT_CONDITION
            )
            for (code in codes) {
                runCatching {
                    val desc = conn.getDevicePropertyDesc(code)
                    val current = conn.getDeviceProperty(code)
                    AppLogger.report("N", "CameraRepositoryImpl.kt:inspectNikonProperties", "Property desc", mapOf(
                        "code" to String.format(Locale.US, "0x%04X", code),
                        "current" to current.toString(),
                        "descSize" to desc.size.toString(),
                        "descHex" to desc.joinToString(" ") { String.format(Locale.US, "%02X", it) }
                    ))
                }.onFailure {
                    AppLogger.report("N", "CameraRepositoryImpl.kt:inspectNikonProperties", "Property desc error", mapOf(
                        "code" to String.format(Locale.US, "0x%04X", code),
                        "error" to (it.message ?: "unknown")
                    ))
                }
            }
        }
    }

    /**
     * Read the current exposure parameters from the camera and mirror them in the
     * UI state. This prevents the app display from drifting away from the actual
     * camera settings (e.g. when the camera dial or another app changed them).
     */
    private fun readNikonExposureFromCamera(delayMs: Long = 300) {
        scope.launch {
            delay(delayMs)
            if (!ensureConnected()) return@launch
            val conn = currentConnection ?: return@launch
            val brand = _cameraSettings.value.brand
            runCatching {
                val iso = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_ISO) ?: _exposureSettings.value.iso
                val fNumber = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_F_NUMBER)
                    ?: PtpValueMapper.apertureToPtp(_exposureSettings.value.aperture)
                val ev = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_EXPOSURE_COMPENSATION)

                // 以连接时选定的快门属性为准，避免 0x500D 和 0xD100 混读导致显示错乱
                val selectedShutterCode = preferredShutterProperty
                val shutterRaw = conn.getDeviceProperty(selectedShutterCode)
                val shutter = if (shutterRaw != null && shutterRaw != 0) {
                    PtpValueMapper.ptpToShutter(shutterRaw, brand, selectedShutterCode)
                } else {
                    _exposureSettings.value.shutter
                }

                _exposureSettings.value = _exposureSettings.value.copy(
                    iso = iso.coerceIn(50, 204800),
                    aperture = PtpValueMapper.ptpToAperture(fNumber),
                    shutter = shutter,
                    ev = ev?.let { it / 1000f } ?: _exposureSettings.value.ev
                )
                AppLogger.report("N", "CameraRepositoryImpl.kt:readNikonExposureFromCamera", "Exposure synced", mapOf(
                    "iso" to iso.toString(),
                    "fNumber" to String.format(Locale.US, "0x%04X", fNumber),
                    "shutterCode" to String.format(Locale.US, "0x%04X", selectedShutterCode),
                    "shutterRaw" to shutterRaw.toString(),
                    "shutter" to shutter,
                    "ev" to _exposureSettings.value.ev.toString()
                ))
            }.onFailure {
                AppLogger.report("N", "CameraRepositoryImpl.kt:readNikonExposureFromCamera", "Exposure sync error", mapOf("error" to (it.message ?: "unknown")))
            }
        }
    }

    override fun setConnectionType(type: ConnectionType) {
        _cameraSettings.value = _cameraSettings.value.copy(connectionType = type)
        settingsManager.connectionType = type
        scope.launch {
            switchConnection(resolveConnection(type), autoConnect = false)
        }
    }

    /**
     * 安全切换连接实现：在 Mutex 保护下停止取景、取消旧状态流、断开旧连接、
     * 再启动新连接状态流，避免 USB 切 WiFi 时并发访问导致闪退。
     */
    private suspend fun switchConnection(target: CameraConnection?, autoConnect: Boolean) {
        connectionLock.withLock {
            if (target == currentConnection && connectionStateTarget == target && connectionStateJob?.isActive == true) {
                // 已经在收集该连接的状态，无需重复
                return@withLock
            }
            // 先停止可能占用旧连接的操作，避免切换时命令交错
            stopLiveViewInternal()
            intervalometerJob?.cancel()
            bulbJob?.cancel()

            val oldConnection = currentConnection
            val oldJob = connectionStateJob
            currentConnection = null
            connectionStateTarget = null
            preferredShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME

            oldJob?.cancel()
            oldConnection?.disconnect()
            // 给 USB 层一点时间释放接口，避免新旧连接并发访问底层资源
            delay(300)

            currentConnection = target
            connectionStateTarget = target

            if (target != null) {
                connectionStateJob = collectConnectionState(target)
                _connectionState.value = ConnectionState.Disconnected
                if (autoConnect) {
                    target.connect()
                }
            } else {
                _connectionState.value = ConnectionState.Error("No connection implementation available")
            }
        }
    }

    override fun clearError() {
        if (_connectionState.value is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override suspend fun connect() {
        // #region debug-point E:connect-start
        AppLogger.report("E", "CameraRepositoryImpl.kt:connect", "Repository connect start", mapOf("type" to _cameraSettings.value.connectionType.name))
        // #endregion
        try {
            val type = _cameraSettings.value.connectionType
            // WiFi 模式下若尚未配对，优先从设置中恢复已保存地址，避免用户忘记点击配对按钮。
            if (type == ConnectionType.WiFi) {
                settingsManager.wifiPairedAddress?.takeIf { it.isNotBlank() }?.let { address ->
                    AppLogger.d("connect: auto-pairing saved WiFi address $address")
                    pairWifi(address, WifiBrandPreset.forAddress(address, settingsManager.cameraBrand))
                }
            }
            val target = resolveConnection(type)
            if (target == null) {
                _connectionState.value = ConnectionState.Error("No connection implementation available")
                return
            }
            switchConnection(target, autoConnect = false)
            target.connect()
        } catch (e: Exception) {
            // #region debug-point E:connect-error
            AppLogger.report("E", "CameraRepositoryImpl.kt:connect", "Repository connect error", mapOf("error" to (e.message ?: "unknown")))
            // #endregion
            AppLogger.e("Repository connect failed", e)
            _connectionState.value = ConnectionState.Error("Connect failed: ${e.message}")
        }
    }

    override fun disconnect() {
        stopLiveViewInternal()
        stopEventLoop()
        intervalometerJob?.cancel()
        bulbJob?.cancel()
        connectionStateJob?.cancel()
        connectionStateJob = null
        val conn = currentConnection
        currentConnection = null
        connectionStateTarget = null
        _connectionState.value = ConnectionState.Disconnected
        scope.launch {
            // 关闭 USB 前必须让相机退出 PC 控制模式，否则相机会一直显示“正在连接 PC”，
            // 只能插拔数据线恢复。
            runCatching { exitPcControlMode(conn) }
            conn?.disconnect()
        }
    }

    override suspend fun startLiveView() {
        // #region debug-point F:liveview-start
        AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Start live view", mapOf("connected" to ensureConnected().toString()))
        // #endregion

        // 等待连接真正就绪：状态为 Connected 且 DeviceInfo 可读。很多相机_CONNECTED_状态
        // 发布得比 PTP 会话实际可用要早，直接发取景命令会得到 session-not-open。
        if (!waitForConnectionReady()) {
            AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Connection not ready", emptyMap())
            return
        }

        _liveViewEnabled.value = true
        val conn = currentConnection ?: return
        if (conn.connectionType == ConnectionType.USB) {
            var started = false
            val candidates = liveViewStartCandidates()
            if (candidates.isEmpty()) {
                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Live view not supported for brand", mapOf("brand" to brandStrategy.brand.name))
                _liveViewEnabled.value = false
                return
            }

            // 品牌特定前置：Olympus OMD 需要先设置 LiveViewModeOM 属性
            if (brandStrategy.brand == CameraBrand.Olympus) {
                runCatching {
                    conn.setDeviceProperty(PtpConstants.DEVICE_PROP_OLYMPUS_LiveViewModeOM, 0x04000300)
                    AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Olympus live view mode set", emptyMap())
                }
            }

            for ((startOp, startParams) in candidates) {
                runCatching {
                    // 尼康/佳能需先切 PC 模式并尝试设置录制介质
                    brandStrategy.changeCameraModeOperation?.let { op ->
                        val (code, _) = conn.sendCommand(op, 1)
                        // libgphoto2：ChangeCameraModeFailed 不一定致命，继续尝试
                        if (code != PtpConstants.RESPONSE_OK &&
                            code != PtpConstants.NIKON_RESPONSE_CHANGE_CAMERA_MODE_FAILED
                        ) {
                            AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Change camera mode rejected", mapOf("code" to String.format(Locale.US, "0x%04X", code)))
                            return@runCatching
                        }
                    }
                    waitForDeviceReady()

                    brandStrategy.liveViewStatusProperty?.let { prop ->
                        val lvStatus = conn.getDeviceProperty(prop)
                        AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Live view status", mapOf("status" to (lvStatus?.toString() ?: "null")))
                    }
                    brandStrategy.liveViewProhibitProperty?.let { prop ->
                        val prohibit = conn.getDeviceProperty(prop)
                        if (prohibit != null && prohibit != 0) {
                            AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Live view prohibited", mapOf("condition" to String.format(Locale.US, "0x%08X", prohibit)))
                        }
                    }
                    brandStrategy.recordingMediaProperty?.let { prop ->
                        runCatching { conn.setDeviceProperty(prop, 1) }
                    }

                    // 同一个启动 opcode 重复尝试 3 次，给相机足够准备时间
                    repeat(3) { attempt ->
                        val params = startParams.toList().toIntArray()
                        val (code, _) = conn.sendCommand(startOp, *params)
                        AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Live view start attempt", mapOf("attempt" to (attempt + 1).toString(), "op" to String.format(Locale.US, "0x%04X", startOp), "responseCode" to String.format(Locale.US, "0x%04X", code)))
                        if (code == PtpConstants.RESPONSE_OK) {
                            started = true
                            return@repeat
                        }
                        delay(100)
                    }
                    waitForDeviceReady()
                }.onFailure {
                    // #region debug-point F:liveview-start-error
                    AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Live view start error", mapOf("op" to String.format(Locale.US, "0x%04X", startOp), "error" to (it.message ?: "unknown")))
                    // #endregion
                }
                if (started) break
            }

            if (!started) {
                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "All live view start methods failed", emptyMap())
                _liveViewEnabled.value = false
                runCatching {
                    brandStrategy.changeCameraModeOperation?.let { conn.sendCommand(it, 0) }
                    waitForDeviceReady()
                }
                return
            }
        }
        startLiveViewLoop()
    }

    override suspend fun stopLiveView() {
        _liveViewEnabled.value = false
        // Stop the frame loop first and wait for it to finish so we don't try to pull
        // frames while sending the stop command.
        stopLiveViewInternal()
        if (ensureConnected()) {
            val conn = currentConnection ?: return
            if (conn.connectionType == ConnectionType.USB) {
                runCatching {
                    brandStrategy.liveViewStopOperation?.let { stopOp ->
                        // Panasonic 使用同一个 opcode，参数 0x0d000011 表示停止
                        val stopParams = if (brandStrategy.brand == CameraBrand.Panasonic) intArrayOf(0x0d000011) else IntArray(0)
                        val (stopCode, _) = conn.sendCommand(stopOp, *stopParams)
                        AppLogger.d("stopLiveView: live view stop response 0x${String.format(Locale.US, "%04X", stopCode)}")
                    }
                    // Fuji 的 OpenCapture 需要在停止时调用 TerminateOpenCapture
                    if (brandStrategy.brand == CameraBrand.Fuji) {
                        runCatching { conn.sendCommand(PtpConstants.OPERATION_TERMINATE_OPEN_CAPTURE) }
                    }
                    waitForDeviceReady(conn)
                    // Return the camera to normal mode so it no longer shows
                    // "Connecting to PC" on its screen.
                    brandStrategy.changeCameraModeOperation?.let { op ->
                        val (code, _) = conn.sendCommand(op, 0)
                        AppLogger.d("stopLiveView: PC mode exit response 0x${String.format(Locale.US, "%04X", code)}")
                    }
                    waitForDeviceReady(conn)
                }.onFailure {
                    AppLogger.report("F", "CameraRepositoryImpl.kt:stopLiveView", "Stop live view error", mapOf("error" to (it.message ?: "unknown")))
                }
            }
        }
    }

    override fun pairWifi(address: String, brandPreset: WifiBrandPreset) {
        connections.filterIsInstance<com.phtontools.phtonview.connection.WifiCameraConnection>()
            .firstOrNull()
            ?.pair(address, brandPreset)
    }

    private fun startLiveViewLoop() {
        liveViewJob?.cancel()
        liveViewJob = scope.launch(liveViewDispatcher) {
            // 提升实时取景线程优先级，减少被调度器打断的概率
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)

            // 复用 Options 减少 GC 压力；RGB_565 比 ARGB_8888 解码更快，且实时取景预览够看
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var frameCount = 0
            var dropCount = 0
            var lastLogTime = System.nanoTime()
            var lastErrorTime = 0L
            var consecutiveFrameFailures = 0
            val targetIntervalNs = 16_666_667L // 60fps
            val getCandidates = liveViewGetCandidates()
            if (getCandidates.isEmpty()) {
                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "No live view get candidates", mapOf("brand" to brandStrategy.brand.name))
                _liveViewFrame.value = null
                return@launch
            }
            while (isActive && _liveViewEnabled.value && _connectionState.value is ConnectionState.Connected) {
                val loopStart = System.nanoTime()
                var captured = false
                try {
                    val conn = currentConnection ?: break
                    if (conn.connectionType == ConnectionType.USB) {
                        // 依次尝试所有候选取图 opcode，有一个成功就继续解析
                        var frameData: ByteArray? = null
                        for (getOp in getCandidates) {
                            val data = if (conn is UsbCameraConnection) {
                                conn.sendCommandWithData(getOp, timeoutMs = 300, params = intArrayOf())
                            } else {
                                conn.sendCommandWithData(getOp)
                            }
                            if (data.size > 12) {
                                frameData = data
                                break
                            }
                        }
                        val data = frameData ?: continue
                        val jpegData = extractLiveViewJpeg(data)
                        if (jpegData.isEmpty()) continue
                        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, decodeOptions)
                        bitmap?.let {
                            _liveViewFrame.value = it
                            captured = true
                            consecutiveFrameFailures = 0
                        }

                        frameCount++
                        val now = System.nanoTime()
                        if (now - lastLogTime >= 5_000_000_000L) {
                            val avgMs = ((now - lastLogTime) / 1_000_000.0) / frameCount.coerceAtLeast(1)
                            AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Live view frames", mapOf("frames" to frameCount.toString(), "dropped" to dropCount.toString(), "avgIntervalMs" to String.format(Locale.US, "%.2f", avgMs)))
                            frameCount = 0
                            dropCount = 0
                            lastLogTime = now
                        }
                    }
                } catch (e: Exception) {
                    consecutiveFrameFailures++
                    val now = System.nanoTime()
                    if (now - lastErrorTime >= 1_000_000_000L) {
                        AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Live view frame error", mapOf("error" to (e.message ?: "unknown"), "consecutiveFailures" to consecutiveFrameFailures.toString()))
                        lastErrorTime = now
                    }
                    if (settingsManager.debugMode) e.printStackTrace()
                    // 连续大量帧失败说明连接或相机状态已异常，退出循环避免空转
                    if (consecutiveFrameFailures >= 60) {
                        AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Too many frame failures, stopping", emptyMap())
                        _liveViewEnabled.value = false
                        break
                    }
                }
                val elapsedNs = System.nanoTime() - loopStart
                val delayNs = targetIntervalNs - elapsedNs
                if (delayNs > 0) {
                    delay(delayNs / 1_000_000)
                } else if (captured) {
                    // 已落后超过一帧，跳过下一次等待以追赶，但累计丢帧数便于观察
                    dropCount++
                }
            }
            // Clear the frame when the loop exits so the UI doesn't stick on the last frame.
            _liveViewFrame.value = null
        }
    }

    private fun stopLiveViewInternal() {
        liveViewJob?.cancel()
        liveViewJob = null
        _liveViewFrame.value = null
    }

    override fun setLiveViewEnabled(enabled: Boolean) {
        _liveViewEnabled.value = enabled
        scope.launch {
            if (enabled) startLiveView() else stopLiveView()
        }
    }

    override suspend fun triggerAf() {
        // #region debug-point H:af-trigger
        AppLogger.report("H", "CameraRepositoryImpl.kt:triggerAf", "Trigger AF", mapOf("focusMode" to _focusMode.value.name, "connected" to ensureConnected().toString()))
        // #endregion
        if (_focusMode.value == FocusMode.MF) return
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        brandStrategy.afDriveOperation?.let { op ->
            runCatching { conn.sendCommand(op) }
        }
    }

    override suspend fun setAfArea(x: Float, y: Float) {
        _cameraSettings.value = _cameraSettings.value.copy(focusPoint = x to y)
        if (_focusMode.value == FocusMode.MF) return
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        val camX = (x * 100).toInt()
        val camY = (y * 100).toInt()
        brandStrategy.changeAfAreaOperation?.let { op ->
            runCatching { conn.sendCommand(op, camX, camY) }
        }
    }

    override fun setFocusMode(mode: FocusMode) {
        _focusMode.value = mode
        applyPtpPropertyAsync(PtpConstants.DEVICE_PROP_FOCUS_MODE, PtpValueMapper.focusModeToPtp(mode))
    }

    override fun setAfMode(mode: AfMode) {
        _afMode.value = mode
        // AF mode is often a vendor-specific property; try standard PTP FocusMode as fallback.
        applyPtpPropertyAsync(PtpConstants.DEVICE_PROP_FOCUS_MODE, PtpValueMapper.afModeToPtp(mode))
    }

    override fun setAfAreaMode(mode: AfAreaMode) {
        _afAreaMode.value = mode
        _cameraSettings.value = _cameraSettings.value.copy(afAreaMode = mode)
        val property = brandStrategy.afAreaModeProperty ?: return
        applyPtpPropertyAsync(property, PtpValueMapper.afAreaModeToPtp(mode))
    }

    override suspend fun setMeteringMode(mode: MeteringMode) {
        // #region debug-point I:metering
        AppLogger.report("I", "CameraRepositoryImpl.kt:setMeteringMode", "Set metering mode", mapOf("mode" to mode.name, "ptpValue" to PtpValueMapper.meteringModeToPtp(mode).toString()))
        // #endregion
        _meteringResult.value = _meteringResult.value.copy(mode = mode)
        applyPtpProperty(PtpConstants.DEVICE_PROP_METERING_MODE, PtpValueMapper.meteringModeToPtp(mode))
    }

    override suspend fun setSpotMeteringPoint(x: Float, y: Float) {
        _meteringResult.value = _meteringResult.value.copy(
            mode = MeteringMode.Spot,
            spotPoint = x to y
        )
        applyPtpProperty(PtpConstants.DEVICE_PROP_METERING_MODE, PtpValueMapper.meteringModeToPtp(MeteringMode.Spot))
    }

    override suspend fun setExposure(aperture: String?, shutter: String?, iso: Int?, ev: Float?) {
        val current = _exposureSettings.value
        val newSettings = current.copy(
            aperture = aperture ?: current.aperture,
            shutter = shutter ?: current.shutter,
            iso = iso ?: current.iso,
            ev = ev ?: current.ev
        )
        // #region debug-point J:exposure
        AppLogger.report("J", "CameraRepositoryImpl.kt:setExposure", "Set exposure", mapOf(
            "aperture" to newSettings.aperture,
            "shutter" to newSettings.shutter,
            "iso" to newSettings.iso.toString(),
            "ev" to newSettings.ev.toString(),
            "isoPtp" to PtpValueMapper.isoToPtp(newSettings.iso).toString(),
            "aperturePtp" to PtpValueMapper.apertureToPtp(newSettings.aperture).toString(),
            "shutterPtp" to PtpValueMapper.shutterToPtp(newSettings.shutter, _cameraSettings.value.brand).toString(),
            "evPtp" to PtpValueMapper.evToPtp(newSettings.ev).toString()
        ))
        // #endregion
        _exposureSettings.value = newSettings
        applyPtpProperty(PtpConstants.DEVICE_PROP_ISO, PtpValueMapper.isoToPtp(newSettings.iso))
        applyPtpProperty(PtpConstants.DEVICE_PROP_F_NUMBER, PtpValueMapper.apertureToPtp(newSettings.aperture))
        applyShutterProperty(newSettings.shutter)
        applyPtpProperty(PtpConstants.DEVICE_PROP_EXPOSURE_COMPENSATION, PtpValueMapper.evToPtp(newSettings.ev))
        // 写入后稍等并回读，确保 UI 显示与相机实际值一致
        readNikonExposureFromCamera(delayMs = 400)
    }

    override suspend fun setIso(iso: Int) {
        _exposureSettings.value = _exposureSettings.value.copy(iso = iso)
        applyPtpProperty(PtpConstants.DEVICE_PROP_ISO, PtpValueMapper.isoToPtp(iso))
        readNikonExposureFromCamera(delayMs = 300)
    }

    override suspend fun setAperture(aperture: String) {
        _exposureSettings.value = _exposureSettings.value.copy(aperture = aperture)
        applyPtpProperty(PtpConstants.DEVICE_PROP_F_NUMBER, PtpValueMapper.apertureToPtp(aperture))
        readNikonExposureFromCamera(delayMs = 300)
    }

    override suspend fun setShutter(shutter: String) {
        _exposureSettings.value = _exposureSettings.value.copy(shutter = shutter)
        applyShutterProperty(shutter)
        readNikonExposureFromCamera(delayMs = 300)
    }

    /**
     * Apply shutter speed to the camera.
     * 尼康机身已在连接时通过 selectShutterProperty 选定使用 0x500D 或 0xD100，
     * 这里只写入选定的属性，避免两个属性互相覆盖导致显示错乱。
     */
    private suspend fun applyShutterProperty(shutter: String) {
        val brand = _cameraSettings.value.brand
        applyPtpProperty(
            preferredShutterProperty,
            PtpValueMapper.shutterToPtp(shutter, brand, preferredShutterProperty)
        )
    }

    override suspend fun setEv(ev: Float) {
        _exposureSettings.value = _exposureSettings.value.copy(ev = ev)
        applyPtpProperty(PtpConstants.DEVICE_PROP_EXPOSURE_COMPENSATION, PtpValueMapper.evToPtp(ev))
        readNikonExposureFromCamera(delayMs = 300)
    }

    override suspend fun setImageFormat(format: ImageFormat) {
        _cameraSettings.value = _cameraSettings.value.copy(imageFormat = format)
        // No standard PTP property for image format; vendor-specific.
    }

    override suspend fun setImageSize(size: ImageSize) {
        _cameraSettings.value = _cameraSettings.value.copy(imageSize = size)
        // No standard PTP property for image size; vendor-specific.
    }

    override suspend fun setBurstSpeed(speed: BurstSpeed) {
        _cameraSettings.value = _cameraSettings.value.copy(burstSpeed = speed)
    }

    override suspend fun setBurstCount(count: Int) {
        _cameraSettings.value = _cameraSettings.value.copy(burstCount = count.coerceIn(2, 50))
    }

    override suspend fun setShootingMode(mode: ShootingMode) {
        _cameraSettings.value = _cameraSettings.value.copy(shootingMode = mode)
        // Standard PTP has no shooting mode property; try Nikon-style property 0xD138.
        applyPtpProperty(0xD138.toShort(), PtpValueMapper.shootingModeToPtp(mode))
    }

    override suspend fun setWhiteBalance(wb: WhiteBalance, kelvin: Int?) {
        _cameraSettings.value = _cameraSettings.value.copy(
            whiteBalance = wb,
            kelvinValue = kelvin ?: _cameraSettings.value.kelvinValue
        )
        applyPtpProperty(PtpConstants.DEVICE_PROP_WHITE_BALANCE, PtpValueMapper.whiteBalanceToPtp(wb))
    }

    override suspend fun setFlashMode(mode: FlashMode) {
        _cameraSettings.value = _cameraSettings.value.copy(flashMode = mode)
        applyPtpProperty(PtpConstants.DEVICE_PROP_FLASH_MODE, PtpValueMapper.flashModeToPtp(mode))
    }

    override suspend fun setFlashCompensation(ev: Float) {
        _cameraSettings.value = _cameraSettings.value.copy(flashCompensation = ev)
        // Flash compensation is often vendor-specific; try standard property 0xD124.
        applyPtpProperty(0xD124.toShort(), PtpValueMapper.evToPtp(ev))
    }

    override suspend fun setStorageTarget(target: StorageTarget) {
        _cameraSettings.value = _cameraSettings.value.copy(storageTarget = target)
        // Storage target switching is vendor-specific.
    }

    private fun applyPtpPropertyAsync(code: Short, value: Int) {
        scope.launch { applyPtpProperty(code, value) }
    }

    private suspend fun applyPtpProperty(code: Short, value: Int) {
        // #region debug-point D:property-apply
        AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Applying property", mapOf("code" to String.format(Locale.US, "0x%04X", code), "value" to value.toString()))
        // #endregion
        if (!ensureConnected()) {
            // #region debug-point D:property-not-connected
            AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Not connected, skipping", mapOf("code" to String.format(Locale.US, "0x%04X", code)))
            // #endregion
            return
        }
        val conn = currentConnection ?: return
        val success = runCatching {
            conn.setDeviceProperty(code, value)
        }.getOrDefault(false)
        // #region debug-point D:property-apply-result
        AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Property apply result", mapOf("code" to String.format(Locale.US, "0x%04X", code), "success" to success.toString()))
        // #endregion

        if (!success) {
            // 快门属性失败时回退到品牌策略中的备选快门属性
            val fallbackCode = if (code == brandStrategy.primaryShutterProperty || code == brandStrategy.fallbackShutterProperty) {
                if (code == brandStrategy.primaryShutterProperty) brandStrategy.fallbackShutterProperty else brandStrategy.primaryShutterProperty
            } else null
            if (fallbackCode != null) {
                waitForDeviceReady()
                val fallbackValue = PtpValueMapper.shutterToPtp(
                    _exposureSettings.value.shutter,
                    _cameraSettings.value.brand,
                    fallbackCode
                )
                runCatching {
                    val ok = conn.setDeviceProperty(fallbackCode, fallbackValue)
                    AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Fallback property", mapOf("code" to String.format(Locale.US, "0x%04X", fallbackCode), "success" to ok.toString()))
                }.onFailure {
                    AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Fallback error", mapOf("code" to String.format(Locale.US, "0x%04X", fallbackCode), "error" to (it.message ?: "unknown")))
                }
            } else {
                // 其他属性失败时等待设备就绪后重试一次
                waitForDeviceReady()
                runCatching {
                    val ok = conn.setDeviceProperty(code, value)
                    AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Retry result", mapOf("code" to String.format(Locale.US, "0x%04X", code), "success" to ok.toString()))
                }.onFailure {
                    AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Retry error", mapOf("code" to String.format(Locale.US, "0x%04X", code), "error" to (it.message ?: "unknown")))
                }
            }
        }
    }

    override suspend fun applyPreset(preset: ShootingPreset) {
        _cameraSettings.value = _cameraSettings.value.copy(preset = preset)
        when (preset) {
            ShootingPreset.Portrait -> _exposureSettings.value = ExposureSettings("f/2.8", "1/125", 200, 0f)
            ShootingPreset.Landscape -> _exposureSettings.value = ExposureSettings("f/11", "1/60", 100, 0f)
            ShootingPreset.Sports -> _exposureSettings.value = ExposureSettings("f/4", "1/1000", 800, 0f)
            ShootingPreset.Night -> _exposureSettings.value = ExposureSettings("f/2.8", "5s", 3200, 0f)
            ShootingPreset.Macro -> _exposureSettings.value = ExposureSettings("f/8", "1/200", 400, 0f)
            ShootingPreset.Studio -> _exposureSettings.value = ExposureSettings("f/8", "1/160", 100, 0f)
            ShootingPreset.User1, ShootingPreset.User2 -> { /* keep current */ }
        }
    }

    override suspend fun resetToDefaults() {
        val defaults = ExposureSettings()
        _exposureSettings.value = defaults
        _cameraSettings.value = CameraSettings().copy(
            brand = _cameraSettings.value.brand,
            connectionType = _cameraSettings.value.connectionType
        )
        _meteringResult.value = MeteringResult()
        _focusMode.value = FocusMode.AF
        _afMode.value = AfMode.AF_S
        _afAreaMode.value = AfAreaMode.SinglePoint
        _focusMagnification.value = 1f
        _focusPeakingEnabled.value = false
        _intervalometer.value = IntervalometerSettings()
        _bulbSettings.value = BulbSettings()
        _timerSettings.value = TimerSettings()
        _aebSettings.value = AebSettings()
        _liveViewEnabled.value = false
    }

    override suspend fun captureImage(delayMs: Long) {
        // #region debug-point G:capture
        AppLogger.report("G", "CameraRepositoryImpl.kt:captureImage", "Capture image", mapOf("delayMs" to delayMs.toString(), "connected" to ensureConnected().toString(), "model" to currentModelName))
        // #endregion
        if (delayMs > 0) delay(delayMs)
        val wasLiveView = _liveViewEnabled.value
        if (wasLiveView) {
            stopLiveView()
            // 停止取景后给相机一点时间退出 PC 模式，否则紧接着的拍摄命令会被拒绝
            waitForDeviceReady()
            delay(300)
        }
        runCatching { doCaptureImage() }
            .onFailure {
                AppLogger.report("G", "CameraRepositoryImpl.kt:captureImage", "Capture error", mapOf("error" to (it.message ?: "unknown")))
            }
            .also {
                if (wasLiveView) {
                    runCatching { startLiveView() }
                } else {
                    // 没有取景时拍完就退出 PC 模式，避免相机一直显示“正在连接 PC”
                    runCatching { exitPcControlMode() }
                }
            }
    }

    /**
     * 退出 PC 控制模式，让相机回到正常待机状态。
     */
    private suspend fun exitPcControlMode(conn: CameraConnection? = currentConnection) {
        conn ?: run {
            AppLogger.d("exitPcControlMode: no connection, skip")
            return
        }
        val op = brandStrategy.changeCameraModeOperation
        if (op == null) {
            AppLogger.d("exitPcControlMode: brand ${brandStrategy.brand} has no PC mode operation")
            return
        }
        runCatching {
            AppLogger.d("exitPcControlMode: sending 0x${String.format(Locale.US, "%04X", op)} param=0 to ${brandStrategy.brand}")
            val (code, _) = conn.sendCommand(op, 0)
            AppLogger.d("exitPcControlMode: response 0x${String.format(Locale.US, "%04X", code)}")
            if (code == PtpConstants.RESPONSE_OK) {
                waitForDeviceReady(conn)
                AppLogger.d("exitPcControlMode: device ready after exit")
            } else {
                AppLogger.w("exitPcControlMode: camera did not accept PC mode exit, code=0x${String.format(Locale.US, "%04X", code)}")
            }
        }.onFailure {
            AppLogger.e("exitPcControlMode: failed", it)
        }
    }

    private suspend fun doCaptureImage() {
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        runCatching {
            // Ensure PC control mode before capture; older Nikon/Canon bodies need this.
            brandStrategy.changeCameraModeOperation?.let { conn.sendCommand(it, 1) }
            waitForDeviceReady()

            val (code, _) = when (brandStrategy.brand) {
                CameraBrand.Nikon -> {
                    // Nikon: InitiateCaptureRecInMedia (0x9207) requires two params:
                    // param1 = AF mode: 0xFFFFFFFF (no AF), 0xFFFFFFFE (with AF)
                    // param2 = target: 0 (card), 1 (SDRAM)
                    val afParam = if (_focusMode.value == FocusMode.AF) 0xFFFFFFFE.toInt() else 0xFFFFFFFF.toInt()
                    val targetParam = if (_cameraSettings.value.storageTarget == StorageTarget.Camera) 1 else 0
                    conn.sendCommand(brandStrategy.captureOperation, afParam, targetParam)
                }
                CameraBrand.Canon -> {
                    // Canon EOS: prefer RemoteReleaseOn/Off for a clean shutter press
                    brandStrategy.afDriveOperation?.let { conn.sendCommand(it) }
                    conn.sendCommand(PtpConstants.CANON_EOS_OPERATION_REMOTE_RELEASE_ON, 1)
                }
                CameraBrand.Panasonic -> {
                    // Panasonic: 0x9404 InitiateCapture, param 0x03000019 for still capture
                    conn.sendCommand(brandStrategy.captureOperation, 0x03000019)
                }
                else -> {
                    conn.sendCommand(brandStrategy.captureOperation)
                }
            }
            if (code != PtpConstants.RESPONSE_OK && brandStrategy.brand != CameraBrand.Canon) {
                AppLogger.report("G", "CameraRepositoryImpl.kt:doCaptureImage", "Capture primary failed, fallback", mapOf("responseCode" to String.format(Locale.US, "0x%04X", code)))
                conn.sendCommand(PtpConstants.OPERATION_INITIATE_CAPTURE)
            }
            if (brandStrategy.brand == CameraBrand.Canon) {
                // Release the shutter after a short press
                delay(100)
                conn.sendCommand(PtpConstants.CANON_EOS_OPERATION_REMOTE_RELEASE_OFF, 1)
            }
            waitForDeviceReady()
        }.onFailure {
            AppLogger.report("G", "CameraRepositoryImpl.kt:doCaptureImage", "Capture error", mapOf("error" to (it.message ?: "unknown")))
            runCatching { conn.sendCommand(PtpConstants.OPERATION_INITIATE_CAPTURE) }
        }
    }

    override suspend fun captureBurst(count: Int) {
        _burstRunning.value = true
        try {
            val settings = _cameraSettings.value
            // 优先使用调用方传入的张数；未传入或无效时再使用设置中保存的值
            val actual = if (count > 0) count.coerceIn(1, 50) else settings.burstCount.coerceIn(1, 50)
            val delayMs = 1000 / settings.burstSpeed.framesPerSecond.coerceAtLeast(1)
            AppLogger.report("G", "CameraRepositoryImpl.kt:captureBurst", "Burst start", mapOf(
                "requestedCount" to count.toString(),
                "actualCount" to actual.toString(),
                "speed" to settings.burstSpeed.name,
                "intervalMs" to delayMs.toString()
            ))
            val wasLiveView = _liveViewEnabled.value
            if (wasLiveView) stopLiveView()
            for (index in 0 until actual) {
                if (!ensureConnected()) break
                doCaptureImage()
                if (index < actual - 1) {
                    // 尼康机身需要等待命令处理完成，再拍下一张
                    if (brandStrategy.brand == CameraBrand.Nikon) waitForDeviceReady(waitMs = 50, timeoutMs = 2000)
                    delay(delayMs.toLong())
                }
            }
            if (wasLiveView) runCatching { startLiveView() }
            AppLogger.report("G", "CameraRepositoryImpl.kt:captureBurst", "Burst finished", mapOf("count" to actual.toString()))
        } finally {
            _burstRunning.value = false
        }
    }

    override suspend fun startBulbExposure(seconds: Int) {
        preBulbShutter = _exposureSettings.value.shutter
        _bulbSettings.value = _bulbSettings.value.copy(enabled = true, durationSeconds = seconds)
        if (!ensureConnected()) {
            _bulbSettings.value = _bulbSettings.value.copy(enabled = false)
            return
        }
        bulbJob?.cancel()
        bulbJob = scope.launch {
            try {
                // B门需要退出实时取景，记录状态以便结束后恢复
                val wasLiveView = _liveViewEnabled.value
                if (wasLiveView) stopLiveView()
                if (brandStrategy.brand == CameraBrand.Nikon) {
                    doNikonBulbExposure(seconds)
                } else {
                    doGenericBulbExposure(seconds)
                }
                if (wasLiveView) runCatching { startLiveView() }
            } catch (_: CancellationException) {
                // 用户主动取消，由外部 stopBulbExposure 处理清理
            } finally {
                _bulbSettings.value = _bulbSettings.value.copy(enabled = false)
            }
        }
    }

    /**
     * Nikon B门完整流程：进入 PC 控制模式 -> 手动曝光模式 -> Bulb ->
     * 开始曝光 -> 等待 -> 终止曝光 -> 恢复快门与模式 -> 退出 PC 控制模式。
     */
    private suspend fun doNikonBulbExposure(seconds: Int) {
        val conn = currentConnection ?: return
        runCatching {
            brandStrategy.changeCameraModeOperation?.let { conn.sendCommand(it, 1) }
            waitForDeviceReady()
            // 切到手动模式，确保支持 Bulb
            setShootingMode(ShootingMode.M)
            waitForDeviceReady()
            setShutter("Bulb")
            waitForDeviceReady()
            delay(200)
            val targetParam = if (_cameraSettings.value.storageTarget == StorageTarget.Camera) 1 else 0
            conn.sendCommand(brandStrategy.captureOperation, 0xFFFFFFFF.toInt(), targetParam)
            waitForDeviceReady()
        }.onFailure {
            AppLogger.report("G", "CameraRepositoryImpl.kt:doNikonBulbExposure", "Nikon bulb start error", mapOf("error" to (it.message ?: "unknown")))
            return
        }
        delay(seconds * 1000L)
        doStopBulbExposure()
        // 退出 PC 控制模式，避免相机一直显示“正在连接 PC”
        runCatching {
            brandStrategy.changeCameraModeOperation?.let { conn.sendCommand(it, 0) }
            waitForDeviceReady()
        }
    }

    /**
     * 非尼康机身没有标准 B门停止命令，退而求其次：把快门设为指定秒数后拍摄一次。
     */
    private suspend fun doGenericBulbExposure(seconds: Int) {
        val safeSeconds = seconds.coerceIn(1, 30)
        setShutter("${safeSeconds}s")
        delay(200)
        doCaptureImage()
        delay(safeSeconds * 1000L + 500)
        setShutter(preBulbShutter)
        // 退出 PC 模式，避免相机一直显示“正在连接 PC”
        runCatching { exitPcControlMode() }
    }

    override suspend fun stopBulbExposure() {
        bulbJob?.cancel()
        bulbJob?.join()
        doStopBulbExposure()
    }

    private suspend fun doStopBulbExposure() {
        _bulbSettings.value = _bulbSettings.value.copy(enabled = false)
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        runCatching {
            if (brandStrategy.brand == CameraBrand.Nikon) {
                // Nikon TerminateCapture (0x920C) 不需要参数
                brandStrategy.terminateCaptureOperation?.let { conn.sendCommand(it) }
                waitForDeviceReady()
                // 恢复 B 门之前的快门速度，使显示与实际一致
                setShutter(preBulbShutter)
                waitForDeviceReady()
            }
        }
    }

    override suspend fun captureWithTimer(delaySeconds: Int) {
        _timerSettings.value = _timerSettings.value.copy(enabled = true, delaySeconds = delaySeconds)
        try {
            captureImage(delaySeconds * 1000L)
        } finally {
            _timerSettings.value = _timerSettings.value.copy(enabled = false)
        }
    }

    override suspend fun startIntervalometer(settings: IntervalometerSettings) {
        _intervalometer.value = settings.copy(enabled = true)
        intervalometerJob?.cancel()
        intervalometerJob = scope.launch {
            try {
                if (settings.startDelaySeconds > 0) delay(settings.startDelaySeconds * 1000L)
                val total = settings.totalShots.coerceAtLeast(1)
                repeat(total) { index ->
                    captureImage()
                    if (index < total - 1) delay(settings.intervalSeconds * 1000L)
                }
            } finally {
                _intervalometer.value = _intervalometer.value.copy(enabled = false)
            }
        }
    }

    override suspend fun stopIntervalometer() {
        intervalometerJob?.cancel()
        intervalometerJob?.join()
        _intervalometer.value = _intervalometer.value.copy(enabled = false)
    }

    override fun setIntervalometerSettings(settings: IntervalometerSettings) {
        _intervalometer.value = settings
    }

    override suspend fun captureAeb(settings: AebSettings) {
        _aebSettings.value = settings.copy(enabled = true)
        val baseEv = _exposureSettings.value.ev
        val count = settings.bracketCount.coerceIn(3, 9)
        val half = count / 2
        try {
            for (i in 0 until count) {
                val evOffset = (i - half) * settings.stepEv
                setEv(baseEv + evOffset)
                captureImage()
            }
            setEv(baseEv)
        } finally {
            _aebSettings.value = _aebSettings.value.copy(enabled = false)
        }
    }

    override fun setAebSettings(settings: AebSettings) {
        _aebSettings.value = settings
    }

    override fun setBulbSettings(settings: BulbSettings) {
        _bulbSettings.value = settings
    }

    override fun setTimerSettings(settings: TimerSettings) {
        _timerSettings.value = settings
    }

    override fun setFocusMagnification(scale: Float) {
        _focusMagnification.value = scale.coerceIn(1f, 8f)
    }

    override fun setFocusPeakingEnabled(enabled: Boolean) {
        _focusPeakingEnabled.value = enabled
    }

    override suspend fun fetchCameraStatus(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        result["connection"] = if (ensureConnected()) "已连接" else "未连接"
        result["model"] = currentModelName.ifBlank { "未知" }
        val conn = currentConnection
        if (conn == null || !ensureConnected()) {
            result["error"] = "相机未连接"
            _cameraStatus.value = CameraStatus()
            return result
        }

        val wasLiveView = _liveViewEnabled.value
        if (wasLiveView) stopLiveView()

        runCatching {
            // 基本信息
            result["型号"] = currentModelName.ifBlank { "未知" }

            // 电量
            safeReadProperty(conn, PtpConstants.DEVICE_PROP_BATTERY_LEVEL, "电量(%)")?.let {
                result["电量(%)"] = "$it"
            }

            // 存储卡：尝试读取 storage info
            val storageIds = runCatching { conn.sendCommandWithData(PtpConstants.OPERATION_GET_STORAGE_IDS).let { PtpCommand.decodeIntArray(it) } }.getOrDefault(IntArray(0))
            result["存储卡数量"] = storageIds.size.toString()
            if (storageIds.isEmpty()) {
                result["有无卡"] = "未检测到"
            } else {
                result["有无卡"] = "有"
                val sid = storageIds.firstOrNull()
                if (sid != null) {
                    val info = conn.getStorageInfo(sid)
                    info?.let { (max, free) ->
                        result["总容量"] = formatBytes(max)
                        result["剩余空间"] = formatBytes(free)
                    }
                }
            }

            // 剩余可拍张数（尼康）
            safeReadProperty(conn, 0xD400.toShort(), "剩余可拍张数")?.let {
                result["剩余可拍张数"] = "$it"
            }

            // 拍摄参数
            safeReadProperty(conn, PtpConstants.DEVICE_PROP_ISO, "ISO")?.let {
                result["ISO"] = "$it"
            }
            safeReadProperty(conn, PtpConstants.DEVICE_PROP_F_NUMBER, "光圈")?.let {
                result["光圈"] = formatFNumber(it)
            }
            // 快门：按品牌策略先读主属性，失败再读备选属性
            val primaryShutter = brandStrategy.primaryShutterProperty
            val fallbackShutter = brandStrategy.fallbackShutterProperty
            safeReadProperty(conn, primaryShutter, "快门")?.let {
                result["快门"] = PtpValueMapper.ptpToShutter(it, _cameraSettings.value.brand, primaryShutter)
                result["快门(PTP值)"] = "$it"
            } ?: fallbackShutter?.let { fallback ->
                safeReadProperty(conn, fallback, "备选快门")?.let {
                    result["快门"] = PtpValueMapper.ptpToShutter(it, _cameraSettings.value.brand, fallback)
                    result["快门(PTP值)"] = "$it"
                }
            }
            safeReadProperty(conn, PtpConstants.DEVICE_PROP_EXPOSURE_COMPENSATION, "曝光补偿")?.let {
                result["曝光补偿"] = formatEv(it)
            }
            safeReadProperty(conn, PtpConstants.DEVICE_PROP_METERING_MODE, "测光模式")?.let {
                result["测光模式"] = meteringModeName(it)
            }
            safeReadProperty(conn, PtpConstants.DEVICE_PROP_WHITE_BALANCE, "白平衡")?.let {
                result["白平衡"] = whiteBalanceName(it)
            }
            safeReadProperty(conn, PtpConstants.DEVICE_PROP_FOCUS_MODE, "对焦模式")?.let {
                result["对焦模式"] = focusModeName(it)
            }
            safeReadProperty(conn, PtpConstants.DEVICE_PROP_FLASH_MODE, "闪光灯")?.let {
                result["闪光灯"] = flashModeName(it)
            }
            safeReadProperty(conn, PtpConstants.DEVICE_PROP_EXPOSURE_PROGRAM_MODE, "曝光程序")?.let {
                result["曝光程序"] = exposureProgramName(it)
            }

            // 镜头信息（尼康常见属性，失败也不影响）
            safeReadProperty(conn, 0xD0E0.toShort(), "镜头ID")?.let {
                result["镜头ID"] = String.format(Locale.US, "0x%08X", it)
            }
            safeReadProperty(conn, 0xD0E1.toShort(), "镜头排序")?.let {
                result["镜头排序"] = String.format(Locale.US, "0x%08X", it)
            }
            safeReadProperty(conn, 0xD0E2.toShort(), "镜头焦距")?.let {
                result["镜头焦距"] = "$it mm"
            }

            // 固件/系统版本：从 DeviceInfo 获取
            result["固件版本"] = parseFirmwareVersion(conn)
            result["制造商"] = parseManufacturer(conn)

            // 同时更新旧的 CameraStatus
            _cameraStatus.value = CameraStatus(
                batteryLevel = result["电量(%)"]?.toIntOrNull() ?: -1,
                storageRemaining = result["剩余空间"]?.filter { it.isDigit() }?.toIntOrNull() ?: -1,
                storageTotal = result["总容量"]?.filter { it.isDigit() }?.toIntOrNull() ?: -1,
                temperatureCelsius = -1,
                shotsRemaining = result["剩余可拍张数"]?.toIntOrNull() ?: -1,
                shutterCount = -1,
                firmwareVersion = result["固件版本"] ?: "Unknown"
            )
        }.onFailure {
            AppLogger.report("K", "CameraRepositoryImpl.kt:fetchCameraStatus", "Fetch status error", mapOf("error" to (it.message ?: "unknown")))
            result["error"] = it.message ?: "获取状态失败"
        }

        if (wasLiveView) runCatching { startLiveView() }
        return result
    }

    private suspend fun safeReadProperty(conn: CameraConnection, code: Short, label: String): Int? {
        return runCatching {
            conn.getDeviceProperty(code)
        }.getOrNull().also {
            AppLogger.report("K", "CameraRepositoryImpl.kt:safeReadProperty", label, mapOf("code" to String.format(Locale.US, "0x%04X", code), "value" to (it?.toString() ?: "null")))
        }
    }

    /**
     * Parse DeviceInfo once and return (manufacturer, model, firmwareVersion).
     * ponytail: one PTP round-trip instead of two separate fetch functions.
     */
    private suspend fun parseDeviceInfoStrings(conn: CameraConnection): Triple<String, String, String> {
        return runCatching {
            val data = conn.sendCommandWithData(PtpConstants.OPERATION_GET_DEVICE_INFO)
            if (data.size < 12) return@runCatching Triple("Unknown", "Unknown", "Unknown")
            val payload = PtpCommand.decodeDataPayload(data)
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            buffer.getShort() // StandardVersion
            buffer.getInt() // VendorExtensionID
            buffer.getShort() // VendorExtensionVersion
            PtpCommand.readPtpString(buffer) // vendor extension desc
            buffer.getShort() // FunctionalMode
            buffer.position(buffer.position() + buffer.getInt() * 2) // ops
            buffer.position(buffer.position() + buffer.getInt() * 2) // events
            buffer.position(buffer.position() + buffer.getInt() * 2) // props
            buffer.position(buffer.position() + buffer.getInt() * 2) // capture formats
            buffer.position(buffer.position() + buffer.getInt() * 2) // image formats
            val manufacturer = PtpCommand.readPtpString(buffer)
            val model = PtpCommand.readPtpString(buffer)
            val version = PtpCommand.readPtpString(buffer)
            Triple(manufacturer, model, version)
        }.getOrDefault(Triple("Unknown", "Unknown", "Unknown"))
    }

    private suspend fun parseFirmwareVersion(conn: CameraConnection): String {
        val (_, model, version) = parseDeviceInfoStrings(conn)
        return version.ifBlank { model.ifBlank { "Unknown" } }
    }

    private suspend fun parseManufacturer(conn: CameraConnection): String {
        return parseDeviceInfoStrings(conn).first
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.2f %s", value, units[unitIndex])
    }

    private fun formatFNumber(raw: Int): String {
        val value = raw / 100f
        return String.format(Locale.US, "F%.1f", value)
    }

    private fun formatEv(raw: Int): String {
        val ev = raw / 1000f
        return String.format(Locale.US, "%+.1f EV", ev)
    }

    private fun meteringModeName(raw: Int): String = when (raw) {
        1 -> "平均测光"
        2 -> "中央重点"
        3 -> "点测光"
        4 -> "多点测光"
        5 -> "评价测光/矩阵测光"
        else -> "模式 $raw"
    }

    private fun whiteBalanceName(raw: Int): String = when (raw) {
        1 -> "手动"
        2 -> "自动"
        3 -> "日光"
        4 -> "荧光灯"
        5 -> "钨丝灯"
        6 -> "闪光灯"
        7 -> "阴天"
        8 -> "阴影"
        32784 -> "自动2"
        32785 -> "自动3"
        else -> "模式 $raw"
    }

    private fun focusModeName(raw: Int): String = when (raw) {
        1 -> "手动对焦"
        2 -> "自动对焦"
        32784 -> "AF-S"
        32785 -> "AF-C"
        32786 -> "AF-A"
        32787 -> "AF-F"
        else -> "模式 $raw"
    }

    private fun flashModeName(raw: Int): String = when (raw) {
        0 -> "默认"
        1 -> "自动闪光"
        2 -> "关闭"
        3 -> "填充闪光"
        4 -> "红眼减轻"
        5 -> "红眼减轻+自动"
        6 -> "红眼减轻+填充"
        32784 -> "慢同步"
        32785 -> "后帘同步"
        else -> "模式 $raw"
    }

    private fun exposureProgramName(raw: Int): String = when (raw) {
        1 -> "手动(M)"
        2 -> "程序自动(P)"
        3 -> "光圈优先(A)"
        4 -> "快门优先(S)"
        5 -> "创意程序"
        6 -> "运动程序"
        7 -> "肖像程序"
        8 -> "风景程序"
        32784 -> "自动"
        32785 -> "场景"
        else -> "模式 $raw"
    }

    override suspend fun syncDateTime(): Boolean {
        if (!ensureConnected()) return false
        val conn = currentConnection ?: return false
        val wasLiveView = _liveViewEnabled.value
        if (wasLiveView) stopLiveView()
        val success = runCatching {
            val now = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.getDefault()).format(Date())
            // PTP DateTime 属性 (0x5011) 使用 length-prefixed UTF-16LE 字符串
            val data = PtpCommand.encodePtpString(now)
            val ok = conn.setDevicePropertyValue(0x5011.toShort(), data)
            AppLogger.report("K", "CameraRepositoryImpl.kt:syncDateTime", "Sync date/time", mapOf("value" to now, "success" to ok.toString()))
            ok
        }.getOrElse {
            AppLogger.report("K", "CameraRepositoryImpl.kt:syncDateTime", "Sync date/time error", mapOf("error" to (it.message ?: "unknown")))
            false
        }
        if (wasLiveView) runCatching { startLiveView() }
        return success
    }

    override suspend fun executeGphoto2Command(command: String): String {
        AppLogger.d("Executing gphoto2 command: $command")
        return withContext(Dispatchers.IO) {
            Gphoto2Bridge.executeCommand(command)
        }
    }

    override suspend fun listPhotos(folder: String): List<PhotoItem> {
        if (!ensureConnected()) return emptyList()
        val conn = currentConnection ?: return emptyList()
        val wasLiveView = _liveViewEnabled.value
        if (wasLiveView) {
            stopLiveView()
            // 停止取景后等待相机释放存储访问并退出 PC 模式，否则可能拿到空列表
            delay(800)
        }
        // 再次显式退出 PC 模式（即使 stopLiveView 已尝试退出），确保图库能访问存储。
        // 若相机已退出 PC 模式，此命令会被记录但忽略错误。
        exitPcControlMode(conn)
        delay(300)
        val items = runCatching { conn.listPhotos(folder) }.getOrDefault(emptyList())
        _photos.value = items
        if (wasLiveView) runCatching { startLiveView() }
        return items
    }

    override suspend fun downloadPhoto(photo: PhotoItem, destinationPath: String, renamePattern: String?): Boolean {
        if (!ensureConnected()) return false
        val conn = currentConnection ?: return false
        val handle = photo.id.toIntOrNull() ?: return false
        val bytes = conn.downloadPhoto(handle) ?: return false
        val file = java.io.File(destinationPath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        AppLogger.d("Downloaded ${photo.name} to ${file.absolutePath}")
        return true
    }

    override suspend fun deletePhoto(photo: PhotoItem): Boolean {
        if (!ensureConnected()) return false
        val conn = currentConnection ?: return false
        val handle = photo.id.toIntOrNull() ?: return false
        val result = conn.deletePhoto(handle)
        if (result) {
            _photos.value = _photos.value.filterNot { it.id == photo.id }
        }
        return result
    }

    override suspend fun formatStorage(target: StorageTarget): Boolean {
        if (!ensureConnected()) return false
        val conn = currentConnection ?: return false
        return conn.formatStorage(target)
    }

    override suspend fun getPhotoExif(photo: PhotoItem): ExifInfo {
        if (!ensureConnected()) return ExifInfo()
        val conn = currentConnection ?: return ExifInfo()
        val handle = photo.id.toIntOrNull() ?: return ExifInfo()
        val bytes = conn.downloadPhoto(handle) ?: return ExifInfo()
        return parseExif(bytes)
    }

    override suspend fun writeIptc(photo: PhotoItem, exif: ExifInfo): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val file = if (photo.isInCamera) {
                    val conn = currentConnection ?: return@withContext false
                    val handle = photo.id.toIntOrNull() ?: return@withContext false
                    val bytes = conn.downloadPhoto(handle) ?: return@withContext false
                    val temp = File(context.cacheDir, "iptc_${photo.name}")
                    temp.writeBytes(bytes)
                    temp
                } else {
                    File(photo.path)
                }
                val interfaceExif = ExifInterface(file)
                interfaceExif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, exif.iptcTitle)
                interfaceExif.setAttribute(ExifInterface.TAG_ARTIST, exif.iptcCopyright)
                interfaceExif.setAttribute(ExifInterface.TAG_COPYRIGHT, exif.iptcCopyright)
                interfaceExif.saveAttributes()
                AppLogger.d("IPTC-like metadata written to ${file.absolutePath}")
                true
            } catch (e: Exception) {
                AppLogger.e("writeIptc failed", e)
                false
            }
        }
    }

    override suspend fun writeGps(photo: PhotoItem, latitude: Double, longitude: Double): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val file = if (photo.isInCamera) {
                    val conn = currentConnection ?: return@withContext false
                    val handle = photo.id.toIntOrNull() ?: return@withContext false
                    val bytes = conn.downloadPhoto(handle) ?: return@withContext false
                    val temp = File(context.cacheDir, "gps_${photo.name}")
                    temp.writeBytes(bytes)
                    temp
                } else {
                    File(photo.path)
                }
                val interfaceExif = ExifInterface(file)
                interfaceExif.setLatLong(latitude, longitude)
                interfaceExif.saveAttributes()
                AppLogger.d("GPS written to ${file.absolutePath}")
                true
            } catch (e: Exception) {
                AppLogger.e("writeGps failed", e)
                false
            }
        }
    }

    private fun parseExif(jpegBytes: ByteArray): ExifInfo {
        val tempFile = File.createTempFile("phtonview_exif_", ".jpg")
        tempFile.writeBytes(jpegBytes)
        return try {
            val exif = ExifInterface(tempFile)
            ExifInfo(
                shutter = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: "",
                aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: "",
                iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0),
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH) ?: "",
                captureTime = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "",
                cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
            )
        } finally {
            tempFile.delete()
        }
    }

    override fun release() {
        stopLiveViewInternal()
        intervalometerJob?.cancel()
        bulbJob?.cancel()
        connectionStateJob?.cancel()
        val conn = currentConnection
        currentConnection = null
        scope.cancel()
        conn?.release()
    }

    private fun ensureConnected(): Boolean {
        return _connectionState.value is ConnectionState.Connected
    }

    /**
     * Wait until the connection is not only Connected but also has a usable PTP
     * session (verified by reading DeviceInfo). Some cameras report Connected
     * before the session is fully open, causing subsequent live view commands to
     * fail with session-not-open.
     */
    private suspend fun waitForConnectionReady(timeoutMs: Long = 5000, retryIntervalMs: Long = 200): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val conn = currentConnection
            if (_connectionState.value is ConnectionState.Connected && conn != null) {
                val info = runCatching { conn.getDeviceInfoRaw() }.getOrDefault(ByteArray(0))
                if (info.size >= 12) return true
            }
            delay(retryIntervalMs)
        }
        return ensureConnected() && currentConnection != null
    }

    /**
     * Build a list of candidate live view start operations with their parameters.
     * The brand strategy comes first; redundant fallbacks are appended so we can
     * try multiple methods if the primary opcode is rejected.
     */
    private fun liveViewStartCandidates(): List<Pair<Short, IntArray>> {
        val candidates = mutableListOf<Pair<Short, IntArray>>()
        brandStrategy.liveViewStartOperation?.let {
            candidates.add(it to brandStrategy.liveViewStartParams)
        }
        // Cross-brand fallbacks for tethering apps that identify the wrong brand
        candidates.add(PtpConstants.NIKON_OPERATION_START_LIVEVIEW to IntArray(0))
        candidates.add(PtpConstants.CANON_EOS_OPERATION_INITIATE_VIEWFINDER to IntArray(0))
        candidates.add(PtpConstants.PANASONIC_OPERATION_LIVEVIEW to intArrayOf(0x0d000010))
        candidates.add(PtpConstants.OPERATION_INITIATE_OPEN_CAPTURE to intArrayOf(0, 0))
        return candidates.distinctBy { it.first }
    }

    /**
     * Build a list of candidate live view frame read operations. Some cameras
     * expose live view through more than one vendor opcode; trying them in turn
     * increases the chance of getting a frame.
     */
    private fun liveViewGetCandidates(): List<Short> {
        val candidates = mutableListOf<Short>()
        brandStrategy.liveViewGetOperation?.let { candidates.add(it) }
        when (brandStrategy.brand) {
            CameraBrand.Nikon -> candidates.add(PtpConstants.NIKON_OPERATION_GET_LIVEVIEW_IMAGE)
            CameraBrand.Canon -> candidates.add(PtpConstants.CANON_EOS_OPERATION_GET_VIEWFINDER_DATA)
            CameraBrand.Sony -> candidates.add(PtpConstants.SONY_OPERATION_GET_LIVEVIEW_IMAGE)
            CameraBrand.Fuji -> candidates.add(PtpConstants.FUJI_OPERATION_GET_CAPTURE_PREVIEW)
            CameraBrand.Panasonic -> candidates.add(PtpConstants.PANASONIC_OPERATION_LIVEVIEW_IMAGE)
            CameraBrand.Olympus -> candidates.add(PtpConstants.OLYMPUS_OPERATION_GET_LIVEVIEW_IMAGE)
            else -> { /* no generic fallback */ }
        }
        return candidates.distinct()
    }

    /**
     * 启动品牌特定事件轮询循环。Nikon/Canon EOS 等老机身在 PC 模式下会积压事件，
     * 不及时读取会导致命令被 busy 或进入“正在连接 PC”的假死状态。
     * 循环定期调用品牌策略中的 eventPollOperation 清空事件队列，对事件内容只做日志。
     */
    private fun startEventLoop() {
        val pollOp = brandStrategy.eventPollOperation ?: return
        eventLoopJob?.cancel()
        eventLoopJob = scope.launch {
            while (isActive && ensureConnected()) {
                runCatching {
                    val conn = currentConnection ?: return@runCatching
                    val data = conn.sendCommandWithData(pollOp)
                    AppLogger.report(
                        "N",
                        "CameraRepositoryImpl.kt:startEventLoop",
                        "EVENT_POLL",
                        mapOf(
                            "brand" to brandStrategy.brand.name,
                            "op" to String.format(Locale.US, "0x%04X", pollOp),
                            "bytes" to data.size.toString()
                        )
                    )
                }.onFailure {
                    AppLogger.report(
                        "N",
                        "CameraRepositoryImpl.kt:startEventLoop",
                        "EVENT_POLL error",
                        mapOf(
                            "brand" to brandStrategy.brand.name,
                            "op" to String.format(Locale.US, "0x%04X", pollOp),
                            "error" to (it.message ?: "unknown")
                        )
                    )
                }
                delay(800)
            }
        }
    }

    private fun stopEventLoop() {
        eventLoopJob?.cancel()
        eventLoopJob = null
    }

    /**
     * Poll device-ready command until the camera reports OK or the timeout
     * expires. This mirrors libgphoto2's nikon_wait_busy and is required after
     * starting live view, changing modes, or triggering capture on cameras like
     * the D5200. If the brand strategy has no deviceReadyOperation, this is a
     * simple delay to avoid hammering the bus.
     */
    private suspend fun waitForDeviceReady(
        conn: CameraConnection? = currentConnection,
        waitMs: Long = 50,
        timeoutMs: Long = 2000
    ) {
        conn ?: return
        val readyOp = brandStrategy.deviceReadyOperation
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (readyOp != null) {
                val (code, _) = runCatching {
                    conn.sendCommand(readyOp)
                }.getOrDefault(PtpConstants.RESPONSE_GENERAL_ERROR to IntArray(0))
                if (code == PtpConstants.RESPONSE_OK) return
            }
            delay(waitMs.coerceAtLeast(20))
        }
    }

    /**
     * Extract the JPEG payload from a live view data container.
     * Vendors wrap the JPEG differently (Nikon adds a header, Sony/Fuji may
     * return raw JPEG, some have multiple SOI/EOI pairs). We try several
     * heuristics and return the first valid-looking JPEG.
     */
    private fun extractLiveViewJpeg(data: ByteArray): ByteArray {
        if (data.size < 2) return ByteArray(0)

        // Strategy 1: data already starts with JPEG SOI
        if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) {
            val eoi = data.indexOfJpegMarker(0xD9, start = 2)
            return if (eoi >= 0) data.copyOfRange(0, (eoi + 2).coerceAtMost(data.size)) else data
        }

        // Strategy 2: scan for the first SOI marker
        val soi = data.indexOfJpegMarker(0xD8)
        if (soi >= 0) {
            val eoi = data.indexOfJpegMarker(0xD9, start = soi + 2)
            val end = if (eoi >= soi) (eoi + 2).coerceAtMost(data.size) else data.size
            return data.copyOfRange(soi, end)
        }

        // Strategy 3: some cameras put the frame after a length-prefixed header;
        // scan the payload portion for any SOI marker
        val payload = PtpCommand.decodeDataPayload(data)
        if (payload.size >= 2) {
            val payloadSoi = payload.indexOfJpegMarker(0xD8)
            if (payloadSoi >= 0) {
                val eoi = payload.indexOfJpegMarker(0xD9, start = payloadSoi + 2)
                val end = if (eoi >= payloadSoi) (eoi + 2).coerceAtMost(payload.size) else payload.size
                return payload.copyOfRange(payloadSoi, end)
            }
        }

        return ByteArray(0)
    }

    /**
     * 在字节数组中查找 JPEG 标记（0xFF + marker）。
     * 从 [start] 开始单向前进，避免嵌套循环和中间分配。
     */
    private fun ByteArray.indexOfJpegMarker(marker: Int, start: Int = 0): Int {
        val markerByte = marker.toByte()
        for (i in start until size - 1) {
            if (this[i] == 0xFF.toByte() && this[i + 1] == markerByte) return i
        }
        return -1
    }
}
