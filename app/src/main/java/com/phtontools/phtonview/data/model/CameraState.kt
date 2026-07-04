package com.phtontools.phtonview.data.model

/**
 * 相机连接状态。
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val model: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * 相机品牌（不再由用户选择，统一兼容）。
 */
enum class CameraBrand {
    Generic, Nikon, Canon, Sony, Fuji, Panasonic, Olympus;

    companion object {
        fun fromOrdinal(ordinal: Int): CameraBrand = entries.getOrElse(ordinal) { Generic }
    }
}

/**
 * 连接方式。
 */
enum class ConnectionType {
    USB, WiFi;

    companion object {
        fun fromOrdinal(ordinal: Int): ConnectionType = entries.getOrElse(ordinal) { USB }
    }
}

/**
 * 对焦模式。
 */
enum class FocusMode {
    AF, // 自动对焦
    MF  // 手动对焦辅助
}

/**
 * AF 对焦模式子类型。
 */
enum class AfMode {
    AF_S, // 单次自动对焦
    AF_C  // 连续自动对焦
}

/**
 * 测光模式。
 */
enum class MeteringMode {
    Matrix,
    CenterWeighted,
    Spot
}

/**
 * 图像格式。
 */
enum class ImageFormat {
    JPEG, RAW, RAW_PLUS_JPEG
}

/**
 * 图像尺寸/画质。
 */
enum class ImageSize {
    Small, Medium, Large
}

/**
 * 连拍速度。
 */
enum class BurstSpeed(val framesPerSecond: Int) {
    Low(1),
    Medium(3),
    High(5),
    Max(8)
}

/**
 * 拍摄模式（P/A/S/M）。
 */
enum class ShootingMode {
    P, A, S, M, Auto, Scene
}

/**
 * 白平衡模式。
 */
enum class WhiteBalance {
    Auto, Daylight, Cloudy, Shade, Fluorescent, Tungsten, Flash, Custom, Kelvin
}

/**
 * 闪光灯模式。
 */
enum class FlashMode {
    Auto, On, Off, RedEye, SlowSync, RearSync
}

/**
 * 存储介质目标。
 */
enum class StorageTarget {
    Camera, Card1, Card2
}

/**
 * 拍摄方案预设。
 */
enum class ShootingPreset {
    Portrait, Landscape, Sports, Night, Macro, Studio, User1, User2
}

/**
 * 曝光参数。
 */
data class ExposureSettings(
    val aperture: String = "f/5.6",
    val shutter: String = "1/125",
    val iso: Int = 400,
    val ev: Float = 0f
)

/**
 * 相机状态监控。
 */
data class CameraStatus(
    val batteryLevel: Int = -1,
    val storageRemaining: Int = -1,
    val storageTotal: Int = -1,
    val temperatureCelsius: Int = -1,
    val shotsRemaining: Int = -1,
    val shutterCount: Int = -1,
    val firmwareVersion: String = "Unknown",
    val isRecordingVideo: Boolean = false
)

/**
 * 完整相机设置。
 */
data class CameraSettings(
    val brand: CameraBrand = CameraBrand.Generic,
    val connectionType: ConnectionType = ConnectionType.USB,
    val shootingMode: ShootingMode = ShootingMode.M,
    val imageFormat: ImageFormat = ImageFormat.RAW_PLUS_JPEG,
    val imageSize: ImageSize = ImageSize.Large,
    val burstSpeed: BurstSpeed = BurstSpeed.Medium,
    val burstCount: Int = 5,
    val whiteBalance: WhiteBalance = WhiteBalance.Auto,
    val kelvinValue: Int = 5500,
    val flashMode: FlashMode = FlashMode.Auto,
    val flashCompensation: Float = 0f,
    val focusPoint: Pair<Float, Float>? = null,
    val storageTarget: StorageTarget = StorageTarget.Card1,
    val preset: ShootingPreset = ShootingPreset.User1
)

/**
 * 测光结果。
 */
data class MeteringResult(
    val mode: MeteringMode = MeteringMode.Matrix,
    val ev: Float = 0f,
    val spotPoint: Pair<Float, Float>? = null
)

/**
 * 照片文件信息。
 */
data class PhotoItem(
    val id: String,
    val name: String,
    val path: String,
    val thumbnailPath: String? = null,
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val isInCamera: Boolean = true,
    val exif: ExifInfo? = null
)

/**
 * EXIF 元数据。
 */
data class ExifInfo(
    val shutter: String = "",
    val aperture: String = "",
    val iso: Int = 0,
    val focalLength: String = "",
    val captureTime: String = "",
    val cameraModel: String = "",
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val iptcTitle: String = "",
    val iptcKeywords: List<String> = emptyList(),
    val iptcCopyright: String = ""
)

/**
 * 间隔拍摄任务。
 */
data class IntervalometerSettings(
    val enabled: Boolean = false,
    val intervalSeconds: Int = 5,
    val totalShots: Int = 10,
    val startDelaySeconds: Int = 0
)

/**
 * 包围曝光设置。
 */
data class AebSettings(
    val enabled: Boolean = false,
    val bracketCount: Int = 3,
    val stepEv: Float = 1f
)

/**
 * B 门曝光设置。
 */
data class BulbSettings(
    val enabled: Boolean = false,
    val durationSeconds: Int = 30
)

/**
 * 定时自拍设置。
 */
data class TimerSettings(
    val enabled: Boolean = false,
    val delaySeconds: Int = 10
)
