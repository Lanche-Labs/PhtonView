package com.phtontools.phtonview.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.data.local.UiMode
import com.phtontools.phtonview.data.model.*
import com.phtontools.phtonview.data.repository.CameraRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val repository: CameraRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.Disconnected)

    val liveViewFrame: StateFlow<Bitmap?> = repository.liveViewFrame
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val exposureSettings: StateFlow<ExposureSettings> = repository.exposureSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExposureSettings())

    val cameraSettings: StateFlow<CameraSettings> = repository.cameraSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CameraSettings())

    val meteringResult: StateFlow<MeteringResult> = repository.meteringResult
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeteringResult())

    val histogramData: StateFlow<HistogramData> = repository.histogramData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistogramData())

    val focusMode: StateFlow<FocusMode> = repository.focusMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FocusMode.AF)

    val afMode: StateFlow<AfMode> = repository.afMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AfMode.AF_S)

    val focusMagnification: StateFlow<Float> = repository.focusMagnification
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)

    val focusPeakingEnabled: StateFlow<Boolean> = repository.focusPeakingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val detectedUsbDevice: StateFlow<String?> = repository.detectedUsbDevice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val wifiExperimental: StateFlow<Boolean> = settingsManager.wifiExperimentalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val uiMode: StateFlow<UiMode> = settingsManager.uiModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiMode.PRO)

    val cameraStatus: StateFlow<CameraStatus> = repository.cameraStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CameraStatus())

    val histogramType: StateFlow<HistogramType> = repository.histogramType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistogramType.None)

    val gridType: StateFlow<GridType> = repository.gridType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GridType.None)

    val zebraPattern: StateFlow<ZebraPattern> = repository.zebraPattern
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ZebraPattern.None)

    val intervalometer: StateFlow<IntervalometerSettings> = repository.intervalometer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IntervalometerSettings())

    val bulbSettings: StateFlow<BulbSettings> = repository.bulbSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BulbSettings())

    val timerSettings: StateFlow<TimerSettings> = repository.timerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimerSettings())

    val aebSettings: StateFlow<AebSettings> = repository.aebSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AebSettings())

    val liveViewEnabled: StateFlow<Boolean> = repository.liveViewEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val photos: StateFlow<List<PhotoItem>> = repository.photos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 不在初始化时立即连接，避免首次进入主界面时因无设备而异常
        viewModelScope.launch {
            delay(800)
            repository.connect()
        }
    }

    fun connect() = viewModelScope.launch { repository.connect() }
    fun disconnect() = repository.disconnect()
    fun clearError() = repository.clearError()

    fun startLiveView() = viewModelScope.launch { repository.startLiveView() }
    fun stopLiveView() = viewModelScope.launch { repository.stopLiveView() }
    fun setLiveViewEnabled(enabled: Boolean) { repository.setLiveViewEnabled(enabled) }

    fun triggerAf() = viewModelScope.launch { repository.triggerAf() }
    fun setAfArea(x: Float, y: Float) = viewModelScope.launch { repository.setAfArea(x, y) }

    fun setFocusMode(mode: FocusMode) { repository.setFocusMode(mode) }
    fun setAfMode(mode: AfMode) { repository.setAfMode(mode) }

    fun setMeteringMode(mode: MeteringMode) = viewModelScope.launch { repository.setMeteringMode(mode) }
    fun setSpotMeteringPoint(x: Float, y: Float) = viewModelScope.launch { repository.setSpotMeteringPoint(x, y) }

    fun setExposure(aperture: String?, shutter: String?, iso: Int?, ev: Float?) = viewModelScope.launch {
        repository.setExposure(aperture, shutter, iso, ev)
    }

    fun setIso(iso: Int) = viewModelScope.launch { repository.setIso(iso) }
    fun setAperture(aperture: String) = viewModelScope.launch { repository.setAperture(aperture) }
    fun setShutter(shutter: String) = viewModelScope.launch { repository.setShutter(shutter) }
    fun setEv(ev: Float) = viewModelScope.launch { repository.setEv(ev) }

    fun setImageFormat(format: ImageFormat) = viewModelScope.launch { repository.setImageFormat(format) }
    fun setImageSize(size: ImageSize) = viewModelScope.launch { repository.setImageSize(size) }
    fun setBurstSpeed(speed: BurstSpeed) = viewModelScope.launch { repository.setBurstSpeed(speed) }
    fun setBurstCount(count: Int) = viewModelScope.launch { repository.setBurstCount(count) }
    fun setShootingMode(mode: ShootingMode) = viewModelScope.launch { repository.setShootingMode(mode) }
    fun setWhiteBalance(wb: WhiteBalance, kelvin: Int? = null) = viewModelScope.launch { repository.setWhiteBalance(wb, kelvin) }
    fun setFlashMode(mode: FlashMode) = viewModelScope.launch { repository.setFlashMode(mode) }
    fun setFlashCompensation(ev: Float) = viewModelScope.launch { repository.setFlashCompensation(ev) }
    fun setStorageTarget(target: StorageTarget) = viewModelScope.launch { repository.setStorageTarget(target) }
    fun applyPreset(preset: ShootingPreset) = viewModelScope.launch { repository.applyPreset(preset) }

    fun setConnectionType(type: ConnectionType) { repository.setConnectionType(type) }
    fun pairWifi(address: String) { repository.pairWifi(address) }

    fun captureImage(delayMs: Long = 0) = viewModelScope.launch { repository.captureImage(delayMs) }
    fun startBurstCapture(count: Int) = viewModelScope.launch { repository.captureBurst(count) }
    fun startBulb(seconds: Int) = viewModelScope.launch { repository.startBulbExposure(seconds) }
    fun stopBulb() = viewModelScope.launch { repository.stopBulbExposure() }
    fun setBulbDuration(seconds: Int) = viewModelScope.launch { repository.setBulbSettings(repository.bulbSettings.value.copy(durationSeconds = seconds)) }
    fun captureWithTimer(delaySeconds: Int) = viewModelScope.launch { repository.captureWithTimer(delaySeconds) }
    fun setTimerDelay(seconds: Int) = viewModelScope.launch { repository.setTimerSettings(repository.timerSettings.value.copy(delaySeconds = seconds)) }
    fun startIntervalometer(settings: IntervalometerSettings) = viewModelScope.launch { repository.startIntervalometer(settings) }
    fun stopIntervalometer() = viewModelScope.launch { repository.stopIntervalometer() }
    fun setIntervalometer(settings: IntervalometerSettings) = viewModelScope.launch { repository.setIntervalometerSettings(settings) }
    fun captureAeb(settings: AebSettings) = viewModelScope.launch { repository.captureAeb(settings) }
    fun setAeb(settings: AebSettings) = viewModelScope.launch { repository.setAebSettings(settings) }

    fun setFocusMagnification(scale: Float) { repository.setFocusMagnification(scale) }
    fun setFocusPeakingEnabled(enabled: Boolean) { repository.setFocusPeakingEnabled(enabled) }

    fun setHistogramType(type: HistogramType) { repository.setHistogramType(type) }
    fun setGridType(type: GridType) { repository.setGridType(type) }
    fun setZebraPattern(pattern: ZebraPattern) { repository.setZebraPattern(pattern) }

    fun fetchCameraStatus() = viewModelScope.launch { repository.fetchCameraStatus() }
    fun syncDateTime() = viewModelScope.launch { repository.syncDateTime() }
    fun resetToDefaults() = viewModelScope.launch { repository.resetToDefaults() }
    fun executeGphoto2Command(command: String) = viewModelScope.launch { repository.executeGphoto2Command(command) }

    fun listPhotos(folder: String = "/store_00010001") = viewModelScope.launch { repository.listPhotos(folder) }
    fun downloadPhoto(photo: PhotoItem, destinationPath: String, renamePattern: String? = null) =
        viewModelScope.launch { repository.downloadPhoto(photo, destinationPath, renamePattern) }
    fun deletePhoto(photo: PhotoItem) = viewModelScope.launch { repository.deletePhoto(photo) }
    fun formatStorage(target: StorageTarget) = viewModelScope.launch { repository.formatStorage(target) }
    fun getPhotoExif(photo: PhotoItem) = viewModelScope.launch { repository.getPhotoExif(photo) }
    fun writeIptc(photo: PhotoItem, exif: ExifInfo) = viewModelScope.launch { repository.writeIptc(photo, exif) }
    fun writeGps(photo: PhotoItem, latitude: Double, longitude: Double) =
        viewModelScope.launch { repository.writeGps(photo, latitude, longitude) }

    override fun onCleared() {
        super.onCleared()
        repository.release()
    }
}
