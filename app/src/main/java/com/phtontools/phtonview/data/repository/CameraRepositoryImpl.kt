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
import com.phtontools.phtonview.usb.ptp.PtpConstants
import com.phtontools.phtonview.usb.ptp.PtpValueMapper
import com.phtontools.phtonview.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private var liveViewJob: Job? = null
    private var intervalometerJob: Job? = null
    private var bulbJob: Job? = null

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

    private val _histogramData = MutableStateFlow(HistogramData())
    override val histogramData: StateFlow<HistogramData> = _histogramData

    private val _focusMode = MutableStateFlow(FocusMode.AF)
    override val focusMode: StateFlow<FocusMode> = _focusMode

    private val _afMode = MutableStateFlow(AfMode.AF_S)
    override val afMode: StateFlow<AfMode> = _afMode

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

    private val _histogramType = MutableStateFlow(HistogramType.None)
    override val histogramType: StateFlow<HistogramType> = _histogramType

    private val _gridType = MutableStateFlow(GridType.None)
    override val gridType: StateFlow<GridType> = _gridType

    private val _zebraPattern = MutableStateFlow(ZebraPattern.None)
    override val zebraPattern: StateFlow<ZebraPattern> = _zebraPattern

    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    override val photos: StateFlow<List<PhotoItem>> = _photos

    private val _liveViewEnabled = MutableStateFlow(false)
    override val liveViewEnabled: StateFlow<Boolean> = _liveViewEnabled

    private var currentConnection: CameraConnection? = null
    private var currentModelName: String = ""
    private val isNikon: Boolean
        get() = currentModelName.contains("Nikon", ignoreCase = true) || currentModelName.contains("NIKON", ignoreCase = true)
            || detectBrand(currentModelName) == CameraBrand.Nikon

    /**
     * Nikon cameras (especially older bodies like the D5200) control the regular
     * shutter speed through the standard PTP ExposureTime property 0x500D using
     * 1/10000-second units. The vendor-specific property 0xD100 uses a different
     * (x<<16)|y encoding and is not always writable on these bodies. We still
     * mirror values to 0xD100 when possible for newer models, but 0x500D is the
     * authoritative source for exposure control and Bulb mode.
     */
    private val shutterPropertyCode: Short
        get() = if (isNikon) PtpConstants.DEVICE_PROP_EXPOSURE_TIME else PtpConstants.DEVICE_PROP_EXPOSURE_TIME

    private val nikonShutterPropertyCode: Short
        get() = PtpConstants.DEVICE_PROP_NIKON_EXPOSURE_TIME

    init {
        // 始终使用通用品牌，不再按品牌选择
        _cameraSettings.value = _cameraSettings.value.copy(
            brand = CameraBrand.Generic,
            connectionType = settingsManager.connectionType
        )
        currentConnection = resolveConnection(settingsManager.connectionType)
        currentConnection?.let { collectConnectionState(it) }

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

    private fun collectConnectionState(connection: CameraConnection) {
        scope.launch {
            connection.connectionState.collect { state ->
                _connectionState.value = when (state) {
                    is CameraConnection.ConnectionState.Disconnected -> ConnectionState.Disconnected
                    is CameraConnection.ConnectionState.Connecting -> ConnectionState.Connecting
                    is CameraConnection.ConnectionState.Connected -> {
                        currentModelName = state.model
                        val detectedBrand = detectBrand(state.model)
                        _cameraSettings.value = _cameraSettings.value.copy(brand = detectedBrand)
                        if (detectedBrand == CameraBrand.Nikon) {
                            inspectNikonProperties()
                            readNikonExposureFromCamera()
                        }
                        ConnectionState.Connected(state.model)
                    }
                    is CameraConnection.ConnectionState.Error -> ConnectionState.Error(state.message)
                }
            }
        }
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
            else -> CameraBrand.Generic
        }
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
    private fun readNikonExposureFromCamera() {
        scope.launch {
            delay(300)
            if (!ensureConnected()) return@launch
            val conn = currentConnection ?: return@launch
            val brand = _cameraSettings.value.brand
            runCatching {
                val iso = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_ISO) ?: _exposureSettings.value.iso
                val fNumber = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_F_NUMBER) ?: PtpValueMapper.apertureToPtp(_exposureSettings.value.aperture)
                val shutterStandard = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_EXPOSURE_TIME)
                val shutterNikon = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_NIKON_EXPOSURE_TIME)
                val ev = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_EXPOSURE_COMPENSATION)

                val shutter = when {
                    shutterNikon != null && shutterNikon != 0 -> {
                        PtpValueMapper.ptpToShutter(shutterNikon, brand, PtpConstants.DEVICE_PROP_NIKON_EXPOSURE_TIME)
                    }
                    shutterStandard != null -> {
                        PtpValueMapper.ptpToShutter(shutterStandard, brand, PtpConstants.DEVICE_PROP_EXPOSURE_TIME)
                    }
                    else -> _exposureSettings.value.shutter
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
                    "shutterStandard" to shutterStandard.toString(),
                    "shutterNikon" to shutterNikon.toString(),
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
        val target = resolveConnection(type)
        if (target != currentConnection) {
            currentConnection?.disconnect()
            currentConnection = target
            target?.let { collectConnectionState(it) }
            _connectionState.value = ConnectionState.Disconnected
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
            val target = resolveConnection(type)
            currentConnection = target
            target?.let {
                collectConnectionState(it)
                it.connect()
            } ?: run {
                _connectionState.value = ConnectionState.Error("No connection implementation available")
            }
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
        intervalometerJob?.cancel()
        bulbJob?.cancel()
        currentConnection?.disconnect()
    }

    override suspend fun startLiveView() {
        // #region debug-point F:liveview-start
        AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Start live view", mapOf("connected" to ensureConnected().toString()))
        // #endregion
        if (!ensureConnected()) return
        _liveViewEnabled.value = true
        val conn = currentConnection ?: return
        if (conn.connectionType == ConnectionType.USB) {
            runCatching {
                if (isNikon) {
                    // Nikon 实时取景需要先进入 PC 控制模式，否则可能无法启动。
                    conn.sendCommand(PtpConstants.NIKON_OPERATION_CHANGE_CAMERA_MODE, 1)
                    waitForDeviceReady()

                    // Check live view status and prohibit condition as libgphoto2 does.
                    val lvStatus = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_NIKON_LIVE_VIEW_STATUS)
                    AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Live view status", mapOf("status" to (lvStatus?.toString() ?: "null")))

                    val prohibit = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_NIKON_LIVE_VIEW_PROHIBIT_CONDITION)
                    if (prohibit != null && prohibit != 0) {
                        AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Live view prohibited", mapOf("condition" to String.format(Locale.US, "0x%08X", prohibit)))
                    }

                    // Set recording media to 1 (PC/SDRAM) if the property is supported.
                    runCatching { conn.setDeviceProperty(PtpConstants.DEVICE_PROP_NIKON_RECORDING_MEDIA, 1) }
                }
                val (code, _) = conn.sendCommand(PtpConstants.NIKON_OPERATION_START_LIVEVIEW)
                // #region debug-point F:liveview-start-ok
                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Live view start command sent", mapOf("responseCode" to String.format(Locale.US, "0x%04X", code)))
                // #endregion
                if (isNikon) waitForDeviceReady()
            }.onFailure {
                // #region debug-point F:liveview-start-error
                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "Live view start error", mapOf("error" to (it.message ?: "unknown")))
                // #endregion
            }
        }
        startLiveViewLoop()
    }

    override suspend fun stopLiveView() {
        _liveViewEnabled.value = false
        stopLiveViewInternal()
        if (ensureConnected()) {
            val conn = currentConnection ?: return
            if (conn.connectionType == ConnectionType.USB) {
                runCatching { conn.sendCommand(PtpConstants.NIKON_OPERATION_STOP_LIVEVIEW) }
            }
        }
    }

    override fun pairWifi(address: String) {
        connections.filterIsInstance<com.phtontools.phtonview.connection.WifiCameraConnection>()
            .firstOrNull()
            ?.pair(address)
    }

    private fun startLiveViewLoop() {
        liveViewJob?.cancel()
        liveViewJob = scope.launch {
            var frameCount = 0
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                try {
                    val conn = currentConnection ?: break
                    if (conn.connectionType == ConnectionType.USB) {
                        val data = conn.sendCommandWithData(PtpConstants.NIKON_OPERATION_GET_LIVEVIEW_IMAGE)
                        // #region debug-point F:liveview-frame
                        frameCount++
                        if (frameCount <= 5 || data.size <= 12) {
                            AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Live view frame", mapOf("bytes" to data.size.toString(), "frame" to frameCount.toString()))
                        }
                        // #endregion
                        if (data.size <= 12) continue
                        val jpegData = extractNikonLiveViewJpeg(data)
                        if (jpegData.isEmpty()) continue
                        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                        bitmap?.let { _liveViewFrame.value = it }
                    }
                } catch (e: Exception) {
                    // #region debug-point F:liveview-frame-error
                    AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Live view frame error", mapOf("error" to (e.message ?: "unknown")))
                    // #endregion
                    if (settingsManager.debugMode) e.printStackTrace()
                }
                delay(33)
            }
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
        runCatching { conn.sendCommand(PtpConstants.NIKON_OPERATION_AF_DRIVE) }
    }

    override suspend fun setAfArea(x: Float, y: Float) {
        _cameraSettings.value = _cameraSettings.value.copy(focusPoint = x to y)
        if (_focusMode.value == FocusMode.MF) return
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        val camX = (x * 100).toInt()
        val camY = (y * 100).toInt()
        runCatching { conn.sendCommand(PtpConstants.NIKON_OPERATION_CHANGE_AF_AREA, camX, camY) }
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
    }

    override suspend fun setIso(iso: Int) {
        _exposureSettings.value = _exposureSettings.value.copy(iso = iso)
        applyPtpProperty(PtpConstants.DEVICE_PROP_ISO, PtpValueMapper.isoToPtp(iso))
    }

    override suspend fun setAperture(aperture: String) {
        _exposureSettings.value = _exposureSettings.value.copy(aperture = aperture)
        applyPtpProperty(PtpConstants.DEVICE_PROP_F_NUMBER, PtpValueMapper.apertureToPtp(aperture))
    }

    override suspend fun setShutter(shutter: String) {
        _exposureSettings.value = _exposureSettings.value.copy(shutter = shutter)
        applyShutterProperty(shutter)
    }

    /**
     * Apply shutter speed to the camera. For Nikon we write both the standard
     * 0x500D property (used by D5200 and similar bodies) and the vendor-specific
     * 0xD100 property (used by newer bodies), ignoring failures on the optional
     * 0xD100 write.
     */
    private suspend fun applyShutterProperty(shutter: String) {
        val brand = _cameraSettings.value.brand
        applyPtpProperty(shutterPropertyCode, PtpValueMapper.shutterToPtp(shutter, brand, shutterPropertyCode))
        if (isNikon) {
            runCatching {
                applyPtpProperty(nikonShutterPropertyCode, PtpValueMapper.shutterToPtp(shutter, brand, nikonShutterPropertyCode))
            }
        }
    }

    override suspend fun setEv(ev: Float) {
        _exposureSettings.value = _exposureSettings.value.copy(ev = ev)
        applyPtpProperty(PtpConstants.DEVICE_PROP_EXPOSURE_COMPENSATION, PtpValueMapper.evToPtp(ev))
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
        runCatching {
            val success = conn.setDeviceProperty(code, value)
            // #region debug-point D:property-apply-result
            AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Property apply result", mapOf("code" to String.format(Locale.US, "0x%04X", code), "success" to success.toString()))
            // #endregion
        }.onFailure {
            // #region debug-point D:property-apply-error
            AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Property apply error", mapOf("code" to String.format(Locale.US, "0x%04X", code), "error" to (it.message ?: "unknown")))
            // #endregion
            AppLogger.e("Failed to set property ${String.format(Locale.US, "0x%04X", code)}", it)
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
        _focusMagnification.value = 1f
        _focusPeakingEnabled.value = false
        _intervalometer.value = IntervalometerSettings()
        _bulbSettings.value = BulbSettings()
        _timerSettings.value = TimerSettings()
        _aebSettings.value = AebSettings()
        _histogramType.value = HistogramType.None
        _gridType.value = GridType.None
        _zebraPattern.value = ZebraPattern.None
        _liveViewEnabled.value = false
    }

    override suspend fun captureImage(delayMs: Long) {
        // #region debug-point G:capture
        AppLogger.report("G", "CameraRepositoryImpl.kt:captureImage", "Capture image", mapOf("delayMs" to delayMs.toString(), "connected" to ensureConnected().toString(), "model" to currentModelName))
        // #endregion
        if (delayMs > 0) delay(delayMs)
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        runCatching {
            if (isNikon) {
                // Ensure PC control mode before capture; older Nikon bodies need this.
                conn.sendCommand(PtpConstants.NIKON_OPERATION_CHANGE_CAMERA_MODE, 1)
                waitForDeviceReady()

                // Nikon: InitiateCaptureRecInMedia (0x9207) requires two params:
                // param1 = AF mode: 0xFFFFFFFF (no AF), 0xFFFFFFFE (with AF)
                // param2 = target: 0 (card), 1 (SDRAM)
                val afParam = if (_focusMode.value == FocusMode.AF) 0xFFFFFFFE.toInt() else 0xFFFFFFFF.toInt()
                val targetParam = if (_cameraSettings.value.storageTarget == StorageTarget.Camera) 1 else 0
                val (code, _) = conn.sendCommand(
                    PtpConstants.NIKON_OPERATION_INITIATE_CAPTURE_REC_IN_MEDIA,
                    afParam,
                    targetParam
                )
                if (code != PtpConstants.RESPONSE_OK) {
                    AppLogger.report("G", "CameraRepositoryImpl.kt:captureImage", "Nikon capture fallback", mapOf("responseCode" to String.format(Locale.US, "0x%04X", code)))
                    conn.sendCommand(PtpConstants.OPERATION_INITIATE_CAPTURE)
                }
                waitForDeviceReady()
                return@runCatching
            }
            conn.sendCommand(PtpConstants.OPERATION_INITIATE_CAPTURE)
        }.onFailure {
            AppLogger.report("G", "CameraRepositoryImpl.kt:captureImage", "Capture error", mapOf("error" to (it.message ?: "unknown")))
            runCatching { conn.sendCommand(PtpConstants.OPERATION_INITIATE_CAPTURE) }
        }
    }

    override suspend fun captureBurst(count: Int) {
        val actual = count.coerceIn(2, 50)
        val delayMs = 1000 / (_cameraSettings.value.burstSpeed.framesPerSecond.coerceAtLeast(1))
        repeat(actual) {
            captureImage()
            delay(delayMs.toLong())
        }
    }

    override suspend fun startBulbExposure(seconds: Int) {
        _bulbSettings.value = _bulbSettings.value.copy(enabled = true, durationSeconds = seconds)
        if (!ensureConnected()) return
        bulbJob?.cancel()
        bulbJob = scope.launch {
            val conn = currentConnection ?: return@launch
            if (isNikon) {
                // Nikon B门流程参考 libgphoto2 _put_Nikon_Bulb：
                // 1. 进入 PC 控制模式；2. 曝光程序设为手动；3. 快门设为 Bulb；
                // 4. 发送 capture2；5. 等待；6. 发送 terminate capture。
                runCatching {
                    conn.sendCommand(PtpConstants.NIKON_OPERATION_CHANGE_CAMERA_MODE, 1)
                    waitForDeviceReady()
                    applyPtpProperty(PtpConstants.DEVICE_PROP_EXPOSURE_PROGRAM_MODE, 1)
                    setShutter("Bulb")
                    waitForDeviceReady()
                    delay(200)
                    val targetParam = if (_cameraSettings.value.storageTarget == StorageTarget.Camera) 1 else 0
                    conn.sendCommand(
                        PtpConstants.NIKON_OPERATION_INITIATE_CAPTURE_REC_IN_MEDIA,
                        0xFFFFFFFF.toInt(),
                        targetParam
                    )
                }.onFailure {
                    AppLogger.report("G", "CameraRepositoryImpl.kt:startBulbExposure", "Nikon bulb start error", mapOf("error" to (it.message ?: "unknown")))
                }
                delay(seconds * 1000L)
                stopBulbExposure()
            } else {
                setShutter("Bulb")
                delay(200)
                captureImage()
                delay(seconds * 1000L)
                stopBulbExposure()
            }
        }
    }

    override suspend fun stopBulbExposure() {
        bulbJob?.cancel()
        _bulbSettings.value = _bulbSettings.value.copy(enabled = false)
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        runCatching {
            if (isNikon) {
                // Nikon TerminateCapture (0x920C) 需要两个参数，通常都传 0。
                conn.sendCommand(PtpConstants.NIKON_OPERATION_TERMINATE_CAPTURE, 0, 0)
                waitForDeviceReady()
            } else {
                conn.sendCommand(PtpConstants.NIKON_OPERATION_TERMINATE_CAPTURE)
            }
        }
    }

    override suspend fun captureWithTimer(delaySeconds: Int) {
        _timerSettings.value = _timerSettings.value.copy(enabled = true, delaySeconds = delaySeconds)
        captureImage(delaySeconds * 1000L)
        _timerSettings.value = _timerSettings.value.copy(enabled = false)
    }

    override suspend fun startIntervalometer(settings: IntervalometerSettings) {
        _intervalometer.value = settings.copy(enabled = true)
        intervalometerJob?.cancel()
        intervalometerJob = scope.launch {
            if (settings.startDelaySeconds > 0) delay(settings.startDelaySeconds * 1000L)
            repeat(settings.totalShots.coerceAtLeast(1)) {
                captureImage()
                delay(settings.intervalSeconds * 1000L)
            }
            _intervalometer.value = _intervalometer.value.copy(enabled = false)
        }
    }

    override suspend fun stopIntervalometer() {
        intervalometerJob?.cancel()
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
        for (i in 0 until count) {
            val evOffset = (i - half) * settings.stepEv
            setEv(baseEv + evOffset)
            captureImage()
        }
        setEv(baseEv)
        _aebSettings.value = _aebSettings.value.copy(enabled = false)
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

    override fun setHistogramType(type: HistogramType) {
        _histogramType.value = type
    }

    override fun setGridType(type: GridType) {
        _gridType.value = type
    }

    override fun setZebraPattern(pattern: ZebraPattern) {
        _zebraPattern.value = pattern
    }

    override suspend fun fetchCameraStatus() {
        // #region debug-point K:status
        AppLogger.report("K", "CameraRepositoryImpl.kt:fetchCameraStatus", "Fetch camera status", mapOf("connected" to ensureConnected().toString()))
        // #endregion
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        val battery = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_BATTERY_LEVEL)
        val shots = conn.getDeviceProperty(0xD400.toShort()) // Nikon shots remaining, may vary by brand
        // #region debug-point K:status-result
        AppLogger.report("K", "CameraRepositoryImpl.kt:fetchCameraStatus", "Camera status result", mapOf("battery" to battery.toString(), "shots" to shots.toString()))
        // #endregion
        _cameraStatus.value = CameraStatus(
            batteryLevel = battery ?: -1,
            storageRemaining = -1,
            storageTotal = -1,
            temperatureCelsius = -1,
            shotsRemaining = shots ?: -1,
            shutterCount = -1,
            firmwareVersion = "Unknown"
        )
    }

    override suspend fun syncDateTime() {
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        val now = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.getDefault()).format(Date())
        if (conn is UsbCameraConnection) {
            val data = now.toByteArray(Charsets.UTF_16LE) // simplified encoding; real PTP uses length-prefixed UTF-16LE
            val success = conn.setDevicePropertyValue(0x5011.toShort(), data)
            AppLogger.d("Syncing camera date/time to $now, success=$success")
        } else {
            AppLogger.d("Syncing camera date/time to $now (connection type not supported)")
        }
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
        val items = conn.listPhotos(folder)
        _photos.value = items
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
        scope.cancel()
        currentConnection?.release()
    }

    private fun ensureConnected(): Boolean {
        return _connectionState.value is ConnectionState.Connected
    }

    /**
     * Poll Nikon DeviceReady (0x90C8) until the camera reports OK or the timeout
     * expires. This mirrors libgphoto2's nikon_wait_busy and is required after
     * starting live view, changing modes, or triggering capture on cameras like
     * the D5200.
     */
    private suspend fun waitForDeviceReady(waitMs: Long = 50, timeoutMs: Long = 2000) {
        val conn = currentConnection ?: return
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val (code, _) = runCatching {
                conn.sendCommand(PtpConstants.NIKON_OPERATION_DEVICE_READY)
            }.getOrDefault(PtpConstants.RESPONSE_GENERAL_ERROR to IntArray(0))
            if (code == PtpConstants.RESPONSE_OK) return
            delay(waitMs.coerceAtLeast(20))
        }
    }

    /**
     * Extract the JPEG payload from a Nikon GetLiveViewImage response.
     * The Nikon live-view frame usually carries a small header before the JPEG,
     * so we first skip the 12-byte PTP data container and look for SOI/EOI.
     * If no markers are found in the stripped payload, fall back to scanning
     * the full data buffer to tolerate variable header lengths.
     */
    private fun extractNikonLiveViewJpeg(data: ByteArray): ByteArray {
        val soiMarker = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val eoiMarker = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        fun extractFrom(buffer: ByteArray): ByteArray? {
            val soi = indexOfBytes(buffer, soiMarker)
            if (soi < 0) return null
            val eoi = indexOfBytes(buffer, eoiMarker)
            val end = if (eoi >= soi) (eoi + 2).coerceAtMost(buffer.size) else buffer.size
            return buffer.copyOfRange(soi, end)
        }

        // Most frames: PTP data container (12 bytes) + Nikon header + JPEG.
        if (data.size > 12) {
            val payload = data.copyOfRange(12, data.size)
            extractFrom(payload)?.let { return it }
        }
        // Fallback: scan the whole received buffer.
        return extractFrom(data) ?: data
    }

    private fun indexOfBytes(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        for (i in 0..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
