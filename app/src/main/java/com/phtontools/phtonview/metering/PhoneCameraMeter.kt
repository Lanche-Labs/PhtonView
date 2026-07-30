package com.phtontools.phtonview.metering

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.phtontools.phtonview.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手机摄像头测光器。
 *
 * **为什么用 Camera2 而不是 CameraX？**
 * 项目没有 CameraX 依赖，加进来会拖大 APK。Camera2 是 Android SDK 自带，且测光只需要
 * 一个 YUV_420_888 ImageReader，不需要录像/拍照 SDK 那一套封装，60 行代码就能搞定。
 *
 * **采样策略**：
 * - 默认模式：整画面 Y 通道均值（评价测光）
 * - 点测模式：取画面 12.5% × 12.5% 区域均值
 * - 中心加权：中心 50% × 50% 区域
 * - 节流：每 180ms 最多更新一次，UI 不会卡死
 *
 * **输出**：[MeteringSample] StateFlow 含 sceneEV、当前 ISO/快门/光圈、meanLumaY。
 * UI 端订阅后可以直接渲染 EV 数值。
 */
class PhoneCameraMeter(private val context: Context) {

    /** 单次采样结果。 */
    data class MeteringSample(
        val meanLumaY: Double = 0.0,
        val phoneAperture: Double = 0.0,
        val phoneExposureTimeNs: Long = 0L,
        val phoneIso: Int = 0,
        val sceneEvAtIso100: Double = Double.NaN,
        val timestampMs: Long = 0L
    )

    enum class MeteringPattern { Matrix, CenterWeighted, Spot }

    private val _sample = MutableStateFlow(MeteringSample())
    val sample: StateFlow<MeteringSample> = _sample.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    /** 当前测光模式（默认评价测光，可切到点测 / 中心加权）。 */
    var pattern: MeteringPattern = MeteringPattern.Matrix
        set(value) {
            field = value
            spotRegion = null
        }

    /**
     * 点测位置（归一化 0..1），仅在 pattern = Spot 时生效。
     * null 表示未点选，使用画面中心。
     */
    var spotRegion: Pair<Float, Float>? = null

    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // 最近一次 CaptureResult 的曝光 metadata，由 captureCallback 异步写入
    @Volatile private var latestAperture: Double = 0.0
    @Volatile private var latestExposureTimeNs: Long = 0L
    @Volatile private var latestIso: Int = 0

    // 不可变光圈机型的回退值：从 CameraCharacteristics 读取的实际光圈
    // 部分手机（如 iPhone 多数型号、固定光圈 Android 旗舰）的 LENS_APERTURE
    // 永远不会通过 captureCallback 上报，必须在启动相机时一次性读出来用。
    @Volatile private var fallbackAperture: Double = 1.8

    private var lastEmitMs: Long = 0L
    private val emitIntervalMs = 180L

    fun refreshPermissionState() {
        _hasPermission.value = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 启动摄像头预览 + 测光采样循环。
     * @param previewSurface 预览 Surface（来自 Compose AndroidView 的 SurfaceView），
     *   传 null 时只采样不显示画面。
     */
    fun start(previewSurface: Surface? = null) {
        refreshPermissionState()
        if (!_hasPermission.value) {
            AppLogger.w("PhoneCameraMeter.start: no CAMERA permission")
            return
        }
        if (_isRunning.value) return
        this.previewSurface = previewSurface
        try {
            startBackgroundThread()
            openCamera()
            _isRunning.value = true
        } catch (e: Exception) {
            AppLogger.w("PhoneCameraMeter.start failed: ${e.message}")
            cleanup()
            _isRunning.value = false
        }
    }

    /**
     * 预览 Surface 就绪回调。
     * 如果正在运行，重启 session 把新 surface 加进去；
     * 如果未运行，只缓存 surface。
     */
    fun onPreviewSurfaceChanged(surface: Surface?) {
        previewSurface = surface
        if (_isRunning.value) {
            try {
                cleanup()
                openCamera()
            } catch (e: Exception) {
                AppLogger.w("PhoneCameraMeter.onPreviewSurfaceChanged failed: ${e.message}")
            }
        }
    }

    fun stop() {
        cleanup()
        _isRunning.value = false
    }

    fun release() {
        stop()
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        val thread = HandlerThread("PhoneCameraMeter")
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    private fun openCamera() {
        val manager = cameraManager ?: return
        val cameraId = pickBackCameraId(manager) ?: run {
            AppLogger.w("PhoneCameraMeter: no back camera available")
            return
        }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val readerSize = chooseMeteringSize(map) ?: Size(640, 480)
        val reader = ImageReader.newInstance(
            readerSize.width, readerSize.height, ImageFormat.YUV_420_888, 2
        )
        reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, backgroundHandler)
        imageReader = reader

        // 读出相机实际光圈（用于固定光圈机型回退）
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let {
            fallbackAperture = it.toDouble()
        }

        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createSession(device)
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close()
                cameraDevice = null
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                cameraDevice = null
                AppLogger.w("PhoneCameraMeter camera onError: $error")
            }
        }, backgroundHandler)
    }

    private fun pickBackCameraId(manager: CameraManager): String? {
        return try {
            manager.cameraIdList.firstOrNull { id ->
                val c = manager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun chooseMeteringSize(
        map: android.hardware.camera2.params.StreamConfigurationMap?
    ): Size? {
        if (map == null) return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        return sizes.firstOrNull { it.width == 640 && it.height == 480 }
            ?: sizes.firstOrNull { it.width <= 800 && it.height <= 600 }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    private fun createSession(device: CameraDevice) {
        val reader = imageReader ?: return
        val targets = mutableListOf<Surface>(reader.surface)
        previewSurface?.let { targets.add(it) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = targets.map { OutputConfiguration(it) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    { command -> backgroundHandler?.post(command) },
                    sessionStateCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(targets, sessionStateCallback, backgroundHandler)
            }
        } catch (e: Exception) {
            AppLogger.w("PhoneCameraMeter createSession failed: ${e.message}")
        }
    }

    private val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            startRepeatingRequest()
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
            AppLogger.w("PhoneCameraMeter onConfigureFailed")
        }
    }

    /**
     * **关键**：把 captureCallback 注册到 setRepeatingRequest，
     * 这样每帧回调里都能更新 SENSOR_EXPOSURE_TIME / SENSOR_SENSITIVITY / LENS_APERTURE。
     * 没有它，imageExposureTimeNs 永远是 0。
     */
    private val captureResultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            result.get(CaptureResult.LENS_APERTURE)?.let { latestAperture = it.toDouble() }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { latestExposureTimeNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { latestIso = it }
        }
    }

    private fun startRepeatingRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }
            session.setRepeatingRequest(requestBuilder.build(), captureResultCallback, backgroundHandler)
        } catch (e: Exception) {
            AppLogger.w("PhoneCameraMeter setRepeatingRequest failed: ${e.message}")
        }
    }

    /**
     * 每一帧到来时计算亮度 + 用手机曝光参数计算 EV。
     * 手机 AE 会调整快门/ISO 使画面亮度接近 118，因此必须用曝光参数计算 EV。
     */
    private fun onImageAvailable(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val now = System.currentTimeMillis()
            if (now - lastEmitMs < emitIntervalMs) return
            lastEmitMs = now

            val meanLuma = computeMeanLuma(image)
            val effectiveAperture = if (latestAperture > 0) latestAperture else fallbackAperture
            val shutterSeconds = latestExposureTimeNs / 1_000_000_000.0
            
            // 用手机曝光参数计算 EV
            val sceneEv = MeteringMath.computeEvFromPhoneParams(
                aperture = effectiveAperture,
                shutterSeconds = shutterSeconds,
                iso = latestIso,
                meanLumaY = meanLuma
            )
            
            _sample.value = MeteringSample(
                meanLumaY = meanLuma,
                phoneAperture = effectiveAperture,
                phoneExposureTimeNs = latestExposureTimeNs,
                phoneIso = latestIso,
                sceneEvAtIso100 = sceneEv,
                timestampMs = now
            )
        } catch (e: Exception) {
            AppLogger.w("PhoneCameraMeter onImageAvailable error: ${e.message}")
        } finally {
            try { image?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 计算 Y 通道均值。
     * 1. 整画面（评价测光 / 中心加权）
     * 2. 局部画面（点测）
     * 抽样步长 4（YUV420 是 2x2 块），约 1/16 像素采样，性能与精度的折中。
     */
    private fun computeMeanLuma(image: Image): Double {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return 0.0

        val region = when (pattern) {
            MeteringPattern.Matrix -> Rect(0, 0, width, height)
            MeteringPattern.CenterWeighted -> {
                val cx = width / 2
                val cy = height / 2
                val halfW = width / 4
                val halfH = height / 4
                Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            }
            MeteringPattern.Spot -> {
                val (nx, ny) = spotRegion ?: (0.5f to 0.5f)
                val spotHalfW = width / 8
                val spotHalfH = height / 8
                val cx = (nx * width).toInt().coerceIn(0, width - 1)
                val cy = (ny * height).toInt().coerceIn(0, height - 1)
                Rect(
                    (cx - spotHalfW).coerceAtLeast(0),
                    (cy - spotHalfH).coerceAtLeast(0),
                    (cx + spotHalfW).coerceAtMost(width),
                    (cy + spotHalfH).coerceAtMost(height)
                )
            }
        }

        var sum = 0L
        var count = 0L
        val step = 4
        var y = region.top
        while (y < region.bottom) {
            val rowStart = y * rowStride
            var x = region.left
            while (x < region.right) {
                val index = rowStart + x * pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xFF
                    count++
                }
                x += step
            }
            y += step
        }
        return if (count > 0) sum.toDouble() / count else 0.0
    }

    private fun cleanup() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        imageReader = null
    }

    companion object {
        fun hasBackCamera(context: Context): Boolean {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return false
            return try {
                mgr.cameraIdList.any { id ->
                    val c = mgr.getCameraCharacteristics(id)
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
