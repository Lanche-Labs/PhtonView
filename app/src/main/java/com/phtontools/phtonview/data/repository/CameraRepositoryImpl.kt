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
                    is CameraConnection.ConnectionState.Connected -> ConnectionState.Connected(state.model)
                    is CameraConnection.ConnectionState.Error -> ConnectionState.Error(state.message)
                }
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
        if (!ensureConnected()) return
        _liveViewEnabled.value = true
        val conn = currentConnection ?: return
        if (conn.connectionType == ConnectionType.USB) {
            runCatching { conn.sendCommand(PtpConstants.NIKON_OPERATION_START_LIVEVIEW) }
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
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                try {
                    val conn = currentConnection ?: break
                    if (conn.connectionType == ConnectionType.USB) {
                        val data = conn.sendCommandWithData(PtpConstants.NIKON_OPERATION_GET_LIVEVIEW_IMAGE)
                        if (data.size <= 12) continue
                        val jpegData = data.copyOfRange(12, data.size)
                        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                        bitmap?.let { _liveViewFrame.value = it }
                    }
                } catch (e: Exception) {
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
        _exposureSettings.value = newSettings
        applyPtpProperty(PtpConstants.DEVICE_PROP_ISO, PtpValueMapper.isoToPtp(newSettings.iso))
        applyPtpProperty(PtpConstants.DEVICE_PROP_F_NUMBER, PtpValueMapper.apertureToPtp(newSettings.aperture))
        applyPtpProperty(PtpConstants.DEVICE_PROP_EXPOSURE_TIME, PtpValueMapper.shutterToPtp(newSettings.shutter))
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
        applyPtpProperty(PtpConstants.DEVICE_PROP_EXPOSURE_TIME, PtpValueMapper.shutterToPtp(shutter))
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
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        runCatching {
            conn.setDeviceProperty(code, value)
        }.onFailure { AppLogger.e("Failed to set property 0x%04X".format(code), it) }
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
        if (delayMs > 0) delay(delayMs)
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        runCatching { conn.sendCommand(PtpConstants.OPERATION_INITIATE_CAPTURE) }
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
            captureImage()
            delay(seconds * 1000L)
            // 真正的 B 门结束需要发送终止命令；当前占位
            stopBulbExposure()
        }
    }

    override suspend fun stopBulbExposure() {
        bulbJob?.cancel()
        _bulbSettings.value = _bulbSettings.value.copy(enabled = false)
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        runCatching { conn.sendCommand(PtpConstants.NIKON_OPERATION_TERMINATE_CAPTURE) }
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
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        val battery = conn.getDeviceProperty(PtpConstants.DEVICE_PROP_BATTERY_LEVEL)
        val shots = conn.getDeviceProperty(0xD400.toShort()) // Nikon shots remaining, may vary by brand
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
}
