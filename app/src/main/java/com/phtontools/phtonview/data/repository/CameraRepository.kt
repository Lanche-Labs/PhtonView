package com.phtontools.phtonview.data.repository

import android.graphics.Bitmap
import com.phtontools.phtonview.data.model.*
import kotlinx.coroutines.flow.StateFlow

/**
 * 相机控制仓库接口，支持多品牌与多种连接方式。
 */
interface CameraRepository {

    val connectionState: StateFlow<ConnectionState>
    val liveViewFrame: StateFlow<Bitmap?>
    val exposureSettings: StateFlow<ExposureSettings>
    val cameraSettings: StateFlow<CameraSettings>
    val meteringResult: StateFlow<MeteringResult>
    val histogramData: StateFlow<HistogramData>
    val focusMode: StateFlow<FocusMode>
    val afMode: StateFlow<AfMode>
    val focusMagnification: StateFlow<Float>
    val focusPeakingEnabled: StateFlow<Boolean>
    val detectedUsbDevice: StateFlow<String?>
    val cameraStatus: StateFlow<CameraStatus>
    val intervalometer: StateFlow<IntervalometerSettings>
    val bulbSettings: StateFlow<BulbSettings>
    val timerSettings: StateFlow<TimerSettings>
    val aebSettings: StateFlow<AebSettings>
    val histogramType: StateFlow<HistogramType>
    val gridType: StateFlow<GridType>
    val zebraPattern: StateFlow<ZebraPattern>
    val photos: StateFlow<List<PhotoItem>>
    val liveViewEnabled: StateFlow<Boolean>

    fun setConnectionType(type: ConnectionType)
    fun clearError()

    suspend fun connect()
    fun disconnect()
    suspend fun startLiveView()
    suspend fun stopLiveView()
    fun pairWifi(address: String)
    suspend fun triggerAf()
    suspend fun setAfArea(x: Float, y: Float)
    fun setFocusMode(mode: FocusMode)
    fun setAfMode(mode: AfMode)
    suspend fun setMeteringMode(mode: MeteringMode)
    suspend fun setSpotMeteringPoint(x: Float, y: Float)

    suspend fun setExposure(aperture: String?, shutter: String?, iso: Int?, ev: Float?)
    suspend fun setIso(iso: Int)
    suspend fun setAperture(aperture: String)
    suspend fun setShutter(shutter: String)
    suspend fun setEv(ev: Float)
    suspend fun setImageFormat(format: ImageFormat)
    suspend fun setImageSize(size: ImageSize)
    suspend fun setBurstSpeed(speed: BurstSpeed)
    suspend fun setBurstCount(count: Int)
    suspend fun setShootingMode(mode: ShootingMode)
    suspend fun setWhiteBalance(wb: WhiteBalance, kelvin: Int? = null)
    suspend fun setFlashMode(mode: FlashMode)
    suspend fun setFlashCompensation(ev: Float)
    suspend fun setStorageTarget(target: StorageTarget)
    suspend fun applyPreset(preset: ShootingPreset)
    suspend fun resetToDefaults()

    suspend fun captureImage(delayMs: Long = 0)
    suspend fun captureBurst(count: Int)
    suspend fun startBulbExposure(seconds: Int)
    suspend fun stopBulbExposure()
    suspend fun captureWithTimer(delaySeconds: Int)
    suspend fun startIntervalometer(settings: IntervalometerSettings)
    suspend fun stopIntervalometer()
    fun setIntervalometerSettings(settings: IntervalometerSettings)
    suspend fun captureAeb(settings: AebSettings)
    fun setAebSettings(settings: AebSettings)
    fun setBulbSettings(settings: BulbSettings)
    fun setTimerSettings(settings: TimerSettings)

    fun setFocusMagnification(scale: Float)
    fun setFocusPeakingEnabled(enabled: Boolean)
    fun setHistogramType(type: HistogramType)
    fun setGridType(type: GridType)
    fun setZebraPattern(pattern: ZebraPattern)
    fun setLiveViewEnabled(enabled: Boolean)

    suspend fun fetchCameraStatus()
    suspend fun syncDateTime()
    suspend fun executeGphoto2Command(command: String): String

    suspend fun listPhotos(folder: String = "/store_00010001"): List<PhotoItem>
    suspend fun downloadPhoto(photo: PhotoItem, destinationPath: String, renamePattern: String? = null): Boolean
    suspend fun deletePhoto(photo: PhotoItem): Boolean
    suspend fun formatStorage(target: StorageTarget): Boolean
    suspend fun getPhotoExif(photo: PhotoItem): ExifInfo
    suspend fun writeIptc(photo: PhotoItem, exif: ExifInfo): Boolean
    suspend fun writeGps(photo: PhotoItem, latitude: Double, longitude: Double): Boolean

    fun release()
}
