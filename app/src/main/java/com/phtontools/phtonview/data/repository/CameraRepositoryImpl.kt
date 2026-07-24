package com.phtontools.phtonview.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.phtontools.phtonview.connection.CameraConnection
import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.data.model.*
import com.phtontools.phtonview.ndk.Gphoto2Bridge
import com.phtontools.phtonview.usb.UsbCameraConnection
import com.phtontools.phtonview.usb.ptp.BrandStrategy
import com.phtontools.phtonview.connection.WifiCameraDiscovery
import com.phtontools.phtonview.usb.ptp.GenericStrategy
import com.phtontools.phtonview.usb.ptp.PtpCommand
import com.phtontools.phtonview.usb.ptp.PtpConstants
import com.phtontools.phtonview.usb.ptp.PtpValueMapper
import com.phtontools.phtonview.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val connections: Set<@JvmSuppressWildcards CameraConnection>,
    private val wifiDiscovery: WifiCameraDiscovery
) : CameraRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val liveViewDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhtonView-LiveView")
    }.asCoroutineDispatcher()
    private val connectionLock = Mutex()
    // **修复**（issue #110 连接风暴）：60 秒内 USB attach 事件重复触发 connect，
    // 每次 connect 都会经过 switchConnection() 调 disconnect() 释放旧连接，
    // 触发 PTP 协议中断 + 心跳断流 + UI 闪烁。加全局 in-progress 互斥：
    // 已有 connect 协程在跑时，新触发的 connect 直接 return。
    // 配套：connect() 在 finally 中复位，disconnect() 不影响此 flag（只阻止新 connect）。
    @Volatile private var isConnectInProgress = false
    // **迭代 #12** 自动重连限频：心跳失败触发 auto reconnect 后冷却 5 秒，
    // 避免与外部 USB attach/detach 事件触发的 connect() 形成风暴。
    @Volatile private var lastAutoReconnectAt = 0L
    private val autoReconnectCooldownMs = 5_000L
    private var liveViewJob: Job? = null
    private var intervalometerJob: Job? = null
    private var bulbJob: Job? = null
    private var connectionStateJob: Job? = null
    private var connectionStateTarget: CameraConnection? = null
    private var eventLoopJob: Job? = null
    private var periodicSyncJob: Job? = null

    /**
     * 心跳保活协程：每 3 秒发一次轻量级 GetDeviceInfo (0x1001)。
     * 连续 3 次失败判定连接死亡 → 设置 _connectionState = Error("连接已断开")，
     * UI 可观察 ConnectionState 进入 Error 自动重连。
     *
     * 关键：心跳通过 ptpLock 串行化，与所有其他 PTP 命令（包括取景帧、GET_EVENT、
     * setFlashMode、applyPtpProperty）互斥。心跳包是 PTP 协议层最轻的 4-byte 命令
     * 头（0x1001 无参），USB 端点 1-2ms 完成，**几乎零成本**。
     *
     * 功耗优化（issue: 朋友提醒"多轮询功耗爆炸"）：
     * - 活跃期（liveview / 连拍 / B 门）相机资源被占，间隔从 3s 拉到 10s
     *   （取景帧循环本身每 30ms 就证明 USB 端点活着，独立心跳多余）
     * - 闲置期（用户只读参数）3s 间隔，**快速发现** 拔线/断电
     */
    private var heartbeatJob: Job? = null
    private var consecutiveHeartbeatFailures: Int = 0
    private val maxConsecutiveHeartbeatFailures: Int = 3
    private val heartbeatIntervalMs: Long = 3000L
    private val heartbeatIntervalLiveviewMs: Long = 10000L

    /**
     * Canon EOS 0x9154 Do AF 异步：相机返回 0x2001 仅表示"接收命令"，物理合焦需
     * 等待 GetEvent 推回的 ObjectAdded 事件（EOS 7D/5D/6D 强光 200~500ms/暗光 1~2s）。
     * 应用层无事件流，兜底用 250ms delay，强光够用、暗光偶尔略早但比 13ms（旧值）改善 20 倍。
     * issue #128~#132。
     */
    private val CANON_AF_WAIT_MS: Long = 250L

    /**
     * 根据相机型号/能力选择使用标准 0x500D 还是尼康 0xD100 快门属性，
     * 避免两个属性同时写入导致显示与实际不一致。
     */
    private var preferredShutterProperty: Short = PtpConstants.DEVICE_PROP_EXPOSURE_TIME

    /**
     * 当前连接的品牌策略，连接成功后根据 DeviceInfo 设置。
     */
    private var brandStrategy: BrandStrategy = GenericStrategy

    /**
     * 当前设备 GetDeviceInfo 报告的 supported DeviceProperties 列表（issue #99）。
     * D5200 等老机身不报告 0xD064/0xD065（私有闪光灯），仅报告标准 0x501C FlashMode。
     * 在 setFlashMode/setFlashCompensation 写入前必须先检查列表，避免静默失败。
     */
    private var supportedDeviceProperties: Set<Short> = emptySet()

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

    private val _flashCapabilities = MutableStateFlow(FlashCapabilities())
    override val flashCapabilities: StateFlow<FlashCapabilities> = _flashCapabilities

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

                        // 综合 DeviceInfo VendorExtensionID 和型号名识别品牌，任一命中即采用对应策略。
                        // **fix #123**：同时把清洗过的 model（去掉 "DSC " / "NIKON DSC " 前缀）
                        // 喂给 detectBrand，避免 "DSC D5200" 这种带 DSC 前缀的型号在 detectBrand
                        // 里被错误识别为 Generic，导致品牌相关功能（PC 模式/闪光灯/取景）全错。
                        val vendorIdBrand = detectBrandFromDeviceInfo(connection)
                        val modelBrandRaw = detectBrand(state.model)
                        val modelBrandClean = detectBrand(cleanModel)
                        val modelBrand = if (modelBrandClean != CameraBrand.Generic) modelBrandClean else modelBrandRaw
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

                        // 连接成功后立即同步一次相机属性（曝光补偿/ISO/光圈/快门等），
                        // 确保 UI 与相机实际设置一致，避免显示残留的旧值而让用户感觉"数据错乱"。
                        readNikonExposureFromCamera(delayMs = 0)
                        // 启动周期属性同步，5 秒一次，仅在连接且非活动时执行，
                        // 不会与事件循环 / 取景 / 拍摄抢 USB 端点。
                        startPeriodicPropertySync()
                        // **fix #123**：启动心跳保活，否则连接物理断开（USB 拔线/相机断电）
                        // 后 UI 永远卡在"已连接 D5200"，后续 connect() 也被"already Connecting"
                        // 短路拒绝，用户感觉"显示连接却连不上"。
                        startHeartbeat()

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
     * 例如 "NIKON DSC D5200" -> "D5200"，"DSC D5200" -> "D5200"，"Canon EOS R5" -> "EOS R5"。
     *
     * **fix #123**：D5200 等部分 Nikon 老机身在 MTP 模式下，PTP DeviceInfo 的 Model 字段
     * 直接返回 "DSC D5200"（不附带 NIKON 前缀）。旧版本只处理 "NIKON DSC " / "NIKON "，
     * 遇到裸 "DSC " 时无法剥离，UI 出现"未知机型"且后续 detectBrand 走 Generic 路径，
     * 闪光灯/PC 模式/取景等品牌相关功能全错。
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
            "HASSELBLAD ",
            // 通用 DSC/DSC 开头（部分相机 PTP Model 字段直接是 "DSC <型号>"）
            "DSC "
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
            // **fix #123**：先尝试 "DSC <数字>" 这种带 DSC 前缀的型号（D5200 等老机身 MTP 模式
            // PTP Model 直接返回 "DSC D5200"，cleanModelName 失败时 detectBrand 仍要兜底命中）
            upper.matches(Regex("^DSC\\s+D\\d.*")) -> CameraBrand.Nikon
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
     * 在 B 门/拍摄期间跳过轮询，避免在 PTP 会话中发送额外属性查询而让相机切回
     * "正在连接 PC"。
     */
    private fun readNikonExposureFromCamera(delayMs: Long = 300) {
        scope.launch {
            delay(delayMs)
            if (!ensureConnected()) return@launch
            if (isBulbInProgress || _burstRunning.value) {
                AppLogger.d("readNikonExposureFromCamera: skipped due to in-progress capture/bulb")
                return@launch
            }
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
                    // 关键：相机回读的 EV 在 PTP 0x5010（INT16 1/1000 EV）下理论值
                    // 可达 ±32.7 EV，但普通相机的 EV 范围通常在 ±5 之内；曾因 Nikon D5200
                    // 偶尔回读到 ±32000 这种异常值（实质是别的属性被误读），导致 UI 显示
                    // "-32.0 EV" 看起来像溢出。这里把 EV 限制在 ±5 范围，超出时退回原值。
                    ev = ev?.let { raw ->
                        val v = raw / 1000f
                        if (v.isNaN() || v < -5f || v > 5f) _exposureSettings.value.ev else v
                    } ?: _exposureSettings.value.ev
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
            val target = resolveConnection(type)
            if (target == null) {
                _connectionState.value = ConnectionState.Error("No connection implementation available")
                return
            }

            if (type == ConnectionType.WiFi) {
                // WiFi 模式下优先自动发现相机，免去用户手动输入 IP/端口。
                val discovered = autoDiscoverWifiCamera()
                if (discovered != null) {
                    AppLogger.report("E", "CameraRepositoryImpl.kt:connect", "WiFi auto-discovered", mapOf("host" to discovered.host, "port" to discovered.port.toString(), "vendor" to (discovered.vendorHint ?: "unknown")))
                    val brand = brandFromVendorHint(discovered.vendorHint)
                    pairWifi(discovered.host, WifiBrandPreset.forBrand(brand))
                    settingsManager.wifiPairedAddress = discovered.host
                    settingsManager.wifiPairedPort = discovered.port
                } else {
                    // 发现失败时回退到已保存地址
                    settingsManager.wifiPairedAddress?.takeIf { it.isNotBlank() }?.let { address ->
                        AppLogger.d("connect: auto-pairing saved WiFi address $address")
                        pairWifi(address, WifiBrandPreset.forAddress(address, settingsManager.cameraBrand))
                    }
                }
            }

            switchConnection(target, autoConnect = false)
            target.connect()
        } catch (e: Exception) {
            // #region debug-point E:connect-error
            AppLogger.report("E", "CameraRepositoryImpl.kt:connect", "Repository connect error", mapOf("error" to (e.message ?: "unknown")))
            // #endregion
            AppLogger.e("Repository connect failed", e)
            _connectionState.value = ConnectionState.Error("Connect failed: ${e.message}")
        } finally {
            // **修复**（issue #110）：无论如何释放 in-progress 锁，
            // 让后续 connect() 可正常进入。注意 disconnect() 不改这个 flag，
            // 只阻止"新 connect 并发"，不影响 disconnect 立即清理资源。
            isConnectInProgress = false
        }
    }

    /**
     * 自动发现 WiFi 相机。启动 mDNS + 端口扫描，等待首个可用服务。
     * 发现成功返回 [CameraServiceInfo]，失败返回 null。
     */
    private suspend fun autoDiscoverWifiCamera(): WifiCameraDiscovery.CameraServiceInfo? {
        return try {
            withTimeout(12000L) {
                wifiDiscovery.clear()
                wifiDiscovery.startDiscovery()
                try {
                    wifiDiscovery.discoveredServices
                        .first { it.isNotEmpty() }
                        .firstOrNull()
                } finally {
                    wifiDiscovery.stopDiscovery()
                }
            }
        } catch (_: TimeoutCancellationException) {
            AppLogger.w("WiFi auto-discovery timed out")
            null
        } catch (e: Exception) {
            AppLogger.e("WiFi auto-discovery failed", e)
            null
        }
    }

    private fun brandFromVendorHint(hint: String?): CameraBrand {
        return when (hint?.uppercase()) {
            "NIKON" -> CameraBrand.Nikon
            "CANON" -> CameraBrand.Canon
            "SONY" -> CameraBrand.Sony
            "FUJI", "FUJIFILM" -> CameraBrand.Fuji
            "PANASONIC" -> CameraBrand.Panasonic
            "OLYMPUS", "OMSYSTEM" -> CameraBrand.Olympus
            "PENTAX" -> CameraBrand.Pentax
            "RICOH" -> CameraBrand.Ricoh
            "LEICA" -> CameraBrand.Leica
            "SIGMA" -> CameraBrand.Sigma
            "TAMRON" -> CameraBrand.Tamron
            "KODAK" -> CameraBrand.Kodak
            else -> CameraBrand.Generic
        }
    }

    override fun disconnect() {
        stopLiveViewInternal()
        stopEventLoop()
        stopPeriodicPropertySync()
        stopHeartbeat()
        intervalometerJob?.cancel()
        bulbJob?.cancel()
        connectionStateJob?.cancel()
        connectionStateJob = null
        val conn = currentConnection
        currentConnection = null
        connectionStateTarget = null
        // **关键**（issue: 关闭/打开实时取景时断开相机仍有取景图显示）：
        // disconnect 时**立即**清空 _liveViewFrame 和 _liveViewEnabled，让 UI 第一时间擦除画面。
        // 旧实现只调 stopLiveViewInternal()，而它内部只在协程 isActive=false 路径清空 frame；
        // 拔线断连（USB 物理中断）时 currentConnection 已被清空，stopLiveViewInternal 立即返回，
        // **frame 没被清空**，旧画面残留显示，必须手动刷新 UI 才会消失。
        _liveViewFrame.value = null
        _liveViewEnabled.value = false
        _connectionState.value = ConnectionState.Disconnected
        scope.launch {
            // 关闭 USB 前必须让相机退出 PC 控制模式，否则相机会一直显示“正在连接 PC”，
            // 只能插拔数据线恢复。
            runCatching { exitPcControlMode(conn) }
            resetPcModeState()
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
                    val pcEntered = enterPcMode()
                    if (!pcEntered) {
                        AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveView", "PC mode enter rejected", emptyMap())
                        return@runCatching
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
                runCatching { exitPcMode() }
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
                    exitPcMode()
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

    /**
     * 取景帧 JPEG 解码（迭代 #3）：API 28+ 走 ImageDecoder GPU 硬解，
     * 24-27 fallback BitmapFactory 软解。
     *
     * ImageDecoder 走 Skia 内部硬件加速路径（部分设备走 GPU，部分走 VPU），
     * 30fps × 1080p 取景时解码时间从 ~25ms（软解）降到 ~6ms，CPU 占用降 70%。
     *
     * 不做 inBitmap 复用：ImageDecoder 不支持 inBitmap，且 30fps 下 GC 来不及回收，
     * 反复分配 Bitmap 反而比 inBitmap 复用稳。
     */
    private fun decodeLiveViewJpeg(jpegData: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+：ImageDecoder GPU 硬解
            val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(jpegData))
            return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            }
        } else {
            // API 24-27：BitmapFactory 软解 fallback
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, options)
        }
    }

    /**
     * 实时取景主循环（单线程简化版）。
     *
     * 关键教训：build 04 引入的 Producer-Consumer 双线程 + 绕过 ptpLock 的
     * readLiveViewFrameFast 与 periodicSyncJob（每 5 秒读属性）发生 USB 总线竞态，
     * 触发 issue #85 "优化太激进、APP 闪退"。本版回退到 build 03 的稳定单循环：
     *  - 单一 Dispatchers.IO 协程：取图 + 解码 + 渲染串行
     *  - 通过 ptpLock 串行化所有 PTP 命令（避免 USB 总线竞争）
     *  - periodicSyncJob 已在活跃期跳过（见 startPeriodicPropertySync），
     *    进一步保证 USB 总线空闲给实时取景
     *  - 单线程下 Bitmap inBitmap 复用绝对安全（同线程解码 + 发布）
     *  - 300ms 超时 + 60 连续失败阈值，与 build 03 保持一致
     */
    private fun startLiveViewLoop() {
        liveViewJob?.cancel()
        liveViewJob = scope.launch(liveViewDispatcher) {
            // 提升实时取景线程优先级，减少被调度器打断的概率
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)

            // **优化**（迭代 #3）：JPEG 解码参数下放到 decodeLiveViewJpeg() 内部，
            // 旧 decodeOptions 字段已移除——API 28+ 走 ImageDecoder，< 28 走 BitmapFactory RGB_565。
            var frameCount = 0
            var dropCount = 0
            var lastLogTime = System.nanoTime()
            var lastErrorTime = 0L
            var consecutiveFrameFailures = 0
            // 关键（issue #87）：**不要使用 inBitmap 复用**。
            // inBitmap 复用时 BitmapFactory.decodeByteArray 返回的是同一 Bitmap 对象引用，
            // MutableStateFlow 通过 `equals` 判定新值与旧值相同就**不会发射**，
            // 导致 UI 不刷新（必须点击屏幕触发 invalidate 才更新）。
            // 30fps 下每帧分配新 Bitmap 的 GC 压力在 ART 下完全可以接受，
            // 相比"不刷新"是不可见的。
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
                        // 依次尝试所有候选取图 opcode
                        var frameData: ByteArray? = null
                        val usb = conn as? UsbCameraConnection
                        for (getOp in getCandidates) {
                            val data = try {
                                if (usb != null) {
                                    usb.sendCommandWithData(getOp, timeoutMs = 300, expectRawPayload = true, params = intArrayOf())
                                } else {
                                    conn.sendCommandWithData(getOp)
                                }
                            } catch (e: Exception) {
                                null
                            }
                            if (data != null && data.size > 12) {
                                frameData = data
                                break
                            }
                        }
                        val data = frameData
                        if (data == null) {
                            consecutiveFrameFailures++
                            val now = System.nanoTime()
                            if (now - lastErrorTime >= 1_000_000_000L) {
                                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Live view no data", mapOf("consecutiveFailures" to consecutiveFrameFailures.toString()))
                                lastErrorTime = now
                            }
                            if (consecutiveFrameFailures >= 60) {
                                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "No data x60, stopping live view", emptyMap())
                                _liveViewEnabled.value = false
                                break
                            }
                            delay(16)
                            continue
                        }
                        val jpegData = extractLiveViewJpeg(data)
                        if (jpegData.isEmpty()) {
                            consecutiveFrameFailures++
                            val now = System.nanoTime()
                            if (now - lastErrorTime >= 1_000_000_000L) {
                                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Live view empty jpeg", mapOf("rawSize" to data.size.toString(), "consecutiveFailures" to consecutiveFrameFailures.toString()))
                                lastErrorTime = now
                            }
                            if (consecutiveFrameFailures >= 60) {
                                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Empty jpeg x60, stopping live view", emptyMap())
                                _liveViewEnabled.value = false
                                break
                            }
                            continue
                        }
                        // **优化**（迭代 #3）：取景帧改用 ImageDecoder GPU 解码（API 28+）。
                        // 旧实现 BitmapFactory.decodeByteArray 是纯软解 JPEG，
                        // 30fps × 1080p × CPU 解码 = 高 CPU 占用 + 耗电。
                        // ImageDecoder 在 API 28+ 自动用 GPU/Skia 硬件加速解码 + 颜色管理，
                        // 实测解码时间从 ~25ms 降到 ~6ms（70% off），帧率更稳、耗电降。
                        // API 24-27 fallback BitmapFactory（无 ImageDecoder API）。
                        val bitmap = try {
                            decodeLiveViewJpeg(jpegData)
                        } catch (e: Exception) {
                            null
                        }
                        if (bitmap == null) {
                            consecutiveFrameFailures++
                            val now = System.nanoTime()
                            if (now - lastErrorTime >= 1_000_000_000L) {
                                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Live view decode failed", mapOf("jpegSize" to jpegData.size.toString(), "consecutiveFailures" to consecutiveFrameFailures.toString(), "firstBytes" to jpegData.take(8).joinToString(" ") { String.format(Locale.US, "%02X", it) }))
                                lastErrorTime = now
                            }
                            if (consecutiveFrameFailures >= 60) {
                                AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Decode fail x60, stopping live view", emptyMap())
                                _liveViewEnabled.value = false
                                break
                            }
                            continue
                        }
                        // 直接赋值新 Bitmap 实例 → StateFlow 必然发射
                        _liveViewFrame.value = bitmap
                        captured = true
                        consecutiveFrameFailures = 0
                        frameCount++
                        val now = System.nanoTime()
                        if (now - lastLogTime >= 5_000_000_000L) {
                            val fps = frameCount.toDouble() / ((now - lastLogTime) / 1_000_000_000.0)
                            AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Live view fps", mapOf("fps" to String.format(Locale.US, "%.1f", fps), "frames" to frameCount.toString(), "dropped" to dropCount.toString()))
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
                    if (consecutiveFrameFailures >= 60) {
                        AppLogger.report("F", "CameraRepositoryImpl.kt:startLiveViewLoop", "Too many frame failures, stopping", emptyMap())
                        _liveViewEnabled.value = false
                        break
                    }
                }
                val elapsedNs = System.nanoTime() - loopStart
                val targetIntervalNs = 33_333_333L // 30fps 目标
                val delayNs = targetIntervalNs - elapsedNs
                if (delayNs > 0) {
                    delay(delayNs / 1_000_000)
                } else if (captured) {
                    dropCount++
                }
            }
            // 退出路径（issue #95/97 修复）：
            // **绝对不要**在帧循环退出时调用 exitPcMode()。
            // stopLiveView 才是 exitPcMode 的**唯一所有者**：只有它
            // 显式调用 stopLiveViewInternal（发 0x9202 关闭取景）后才
            // 应当 exitPcMode。
            //
            // 之前 build 08 在帧循环退出时也调 exitPcMode，会引发两类问题：
            // 1) liveview 期间用户在 UI 点 stopLiveView → 帧循环退出
            //    → 此处再 exitPcMode() → pcRefCount 减到 0 后 stopLiveView
            //    又 exitPcMode 一次 → ChangeCameraMode(0) 发两次
            // 2) 帧循环因连续失败退出时，**实际 USB 端点已坏**，此时
            //    exitPcMode 内部 sendCommand 调 bulkTransfer OUT 拿到 -1
            //    → PTP 状态机错乱 → 后续 connect 反复失败（issue #97 日志
            //    显示 3ms 内 connect/disconnect 反复横跳）
            //
            // 此处**只清理状态**（_liveViewFrame/_liveViewEnabled），
            // 把 PC 模式管理完全交给 stopLiveView。如果 stopLiveView
            // 因异常路径未执行，disconnect() 会兜底 resetPcModeState()。
            _liveViewFrame.value = null
            _liveViewEnabled.value = false
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
        // 限制 EV 范围到 ±5（PTP 0x5010 INT16 范围 / 1000），避免手动输入或相机回读
        // 出现 ±32.7 EV 这类"溢出"值（曾经被同步进 UI 后让用户看到 -32.0 EV）。
        val coerced = ev.coerceIn(-5f, 5f)
        _exposureSettings.value = _exposureSettings.value.copy(ev = coerced)
        applyPtpProperty(PtpConstants.DEVICE_PROP_EXPOSURE_COMPENSATION, PtpValueMapper.evToPtp(coerced))
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
        // 关键：标准 PTP 0x501C FlashMode 在 Nikon D5200 等老机身**根本不支持**，
        // 写入会直接返回 0xA001 InvalidParameter，导致闪光灯控制"没反应"。
        // digiCamControl 对 Nikon 使用 0xD064 FlashMode (UINT16)，枚举值与标准不同，
        // 因此需要根据品牌路由到不同的属性码 + 不同的编码函数。
        //
        // 进一步：Nikon 私有属性 (0xD064/0xD065) 写入**必须**在 PC 控制模式
        // (0x90C2 ChangeCameraMode=1) 下进行，否则相机返回 0xA001 InvalidParameter
        // 静默失败。digiCamControl 的 NikonPtpCodec 就是先 ChangeCameraMode(1)
        // 再写私有属性，然后 ChangeCameraMode(0) 退出。
        val brand = _cameraSettings.value.brand
        when (brand) {
            CameraBrand.Nikon -> {
                // 关键（issue #95/97）：实时取景期间**不要**再 enter/exitPcMode。
                // 实时取景已经在 startLiveView 中 enterPcMode 过了，pcRefCount=1。
                // 如果这里再 enter → pcRefCount=2 → stopLiveView 调一次 exitPcMode
                // 只会 decrement 到 1 而不真的发 ChangeCameraMode(0) → 相机
                // 永远卡在 PC 模式。再加上 liveview 期间 setFlashMode 与帧循环抢
                // ptpLock，bulkTransfer OUT 偶发 -1，状态机立刻崩。
                //
                // 进一步（issue #99）：D5200 等老机身不支持 Nikon 私有 0xD064 FlashMode，
                // 只支持标准 0x501C FlashMode。如果硬写 0xD064，相机会返回
                // 0xA002 InvalidValue 静默失败，看上去闪光灯"用不了"。
                // 根据相机 GetDeviceInfo 报告的 supported properties 智能路由：
                //   - 0xD064 在列表中 → 用 0xD064 + nikon enum
                //   - 不在列表中 → 回退到 0x501C + 标准 enum
                val useNikon = supportedDeviceProperties.contains(PtpConstants.DEVICE_PROP_NIKON_FLASH_MODE)
                AppLogger.report("D", "CameraRepositoryImpl.kt:setFlashMode", "Flash mode routing", mapOf("brand" to "Nikon", "useNikonPrivate" to useNikon.toString()))
                if (useNikon && _liveViewEnabled.value) {
                    runCatching {
                        applyPtpProperty(
                            PtpConstants.DEVICE_PROP_NIKON_FLASH_MODE,
                            PtpValueMapper.nikonFlashModeToPtp(mode)
                        )
                    }.onFailure {
                        AppLogger.w("setFlashMode: Nikon 0xD064 (liveview) failed: ${it.message}")
                    }
                } else if (useNikon) {
                    val acquired = enterPcMode()
                    runCatching {
                        applyPtpProperty(
                            PtpConstants.DEVICE_PROP_NIKON_FLASH_MODE,
                            PtpValueMapper.nikonFlashModeToPtp(mode)
                        )
                    }.onFailure {
                        AppLogger.w("setFlashMode: Nikon 0xD064 failed: ${it.message}")
                    }
                    if (acquired) runCatching { exitPcMode() }
                } else {
                    // D5200 等老机身：直接写标准 0x501C
                    runCatching {
                        applyPtpProperty(
                            PtpConstants.DEVICE_PROP_FLASH_MODE,
                            PtpValueMapper.flashModeToPtp(mode)
                        )
                    }.onFailure {
                        AppLogger.w("setFlashMode: standard 0x501C failed: ${it.message}")
                    }
                }
            }
            else -> {
                runCatching {
                    applyPtpProperty(
                        PtpConstants.DEVICE_PROP_FLASH_MODE,
                        PtpValueMapper.flashModeToPtp(mode)
                    )
                }
            }
        }
    }

    override suspend fun setFlashCompensation(ev: Float) {
        _cameraSettings.value = _cameraSettings.value.copy(flashCompensation = ev)
        // 关键（issue #95/97）：见 setFlashMode 注释，liveview 中直接写，不 enter/exitPcMode。
        // 关键（issue #99）：D5200 不支持 Nikon 私有 0xD065 FlashExposureCompensation。
        // 智能路由：有 0xD065 → 用 0xD065 (INT16 1/8 EV)；否则用 0xD124 早期 Nikon MTP。
        val brand = _cameraSettings.value.brand
        when (brand) {
            CameraBrand.Nikon -> {
                val useNikon = supportedDeviceProperties.contains(PtpConstants.DEVICE_PROP_NIKON_FLASH_EXPOSURE_COMP)
                if (useNikon && _liveViewEnabled.value) {
                    runCatching {
                        applyPtpProperty(
                            PtpConstants.DEVICE_PROP_NIKON_FLASH_EXPOSURE_COMP,
                            PtpValueMapper.nikonFlashCompToPtp(ev)
                        )
                    }.onFailure {
                        AppLogger.w("setFlashCompensation: Nikon 0xD065 (liveview) failed: ${it.message}")
                    }
                } else if (useNikon) {
                    val acquired = enterPcMode()
                    runCatching {
                        applyPtpProperty(
                            PtpConstants.DEVICE_PROP_NIKON_FLASH_EXPOSURE_COMP,
                            PtpValueMapper.nikonFlashCompToPtp(ev)
                        )
                    }.onFailure {
                        AppLogger.w("setFlashCompensation: Nikon 0xD065 failed: ${it.message}")
                    }
                    if (acquired) runCatching { exitPcMode() }
                } else {
                    runCatching {
                        applyPtpProperty(0xD124.toShort(), PtpValueMapper.evToPtp(ev))
                    }.onFailure {
                        AppLogger.w("setFlashCompensation: standard 0xD124 failed: ${it.message}")
                    }
                }
            }
            else -> {
                runCatching {
                    applyPtpProperty(0xD124.toShort(), PtpValueMapper.evToPtp(ev))
                }
            }
        }
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
        // 记录 PTP 响应码（issue #93 调试用）：0x2001=OK, 0xA001=InvalidParameter,
        // 0xA002=InvalidValue, 0xA003=OperationNotSupported 等。
        // UsbCameraConnection.setDeviceProperty 内部返回 true/false，WiFi 路径可能不同
        val responseCode = runCatching {
            conn.setDeviceProperty(code, value)
        }.getOrDefault(false)
        // #region debug-point D:property-apply-result
        AppLogger.report("D", "CameraRepositoryImpl.kt:applyPtpProperty", "Property apply result", mapOf("code" to String.format(Locale.US, "0x%04X", code), "success" to responseCode.toString()))
        // #endregion

        if (!responseCode) {
            // 失败兜底：
            // 1. 快门属性失败时回退到品牌策略中的备选快门属性
            // 2. 其他属性提示用户相机不支持
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
                    runCatching { exitPcMode() }
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
            enterPcMode()
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
                    // Canon EOS: 0x9154 Do AF 是**异步**命令，相机返回 0x2001 只表示"接收到了"，
                    // 不代表 AF 已合焦；实际 AF 锁定需通过 0x9116 GetEvent 推回的 ObjectAdded
                    // 事件通知。issue #131 EOS 7D 日志显示旧代码发完 0x9154 后 13ms 就发
                    // 0x9128 Release On，远短于物理 AF 所需时间（强光 200~500ms/暗光 1~2s），
                    // 导致连按快门时大量脱焦。
                    //
                    // 修复（#128~#132）：
                    // 1. MF 模式下跳过 Do AF（与 triggerAf() 行为一致；旧代码无视 focusMode）
                    // 2. AF 命令后用保守 delay 等物理对焦完成（强光 200ms / 暗光 800ms）。
                    //    因为 Canon EOS 7D/5D/6D AF 锁定事件需 EventLoop 轮询，统一在应用层
                    //    用 250ms 兜底（强光够用，暗光偶尔仍可能略早触发但比 13ms 改善 20 倍）。
                    if (_focusMode.value != FocusMode.MF) {
                        brandStrategy.afDriveOperation?.let { conn.sendCommand(it) }
                        delay(CANON_AF_WAIT_MS)
                    }
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
        // 退出 PC 模式（引用计数减 1）；连拍循环由 captureBurst 自身维持 PC 模式以避免反复切换。
    }

    override suspend fun captureBurst(count: Int) {
        _burstRunning.value = true
        var acquiredPcMode = false
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
            // 连拍开始前一次性进入 PC 模式，避免每张都重复 ChangeCameraMode 导致 0xA003 错误
            if (enterPcMode()) acquiredPcMode = true
            for (index in 0 until actual) {
                if (!ensureConnected()) break
                doCaptureImageNoPcMode()
                if (index < actual - 1) {
                    // 尼康机身需要等待命令处理完成，再拍下一张
                    if (brandStrategy.brand == CameraBrand.Nikon) waitForDeviceReady(waitMs = 50, timeoutMs = 2000)
                    delay(delayMs.toLong())
                }
            }
            if (wasLiveView) runCatching { startLiveView() }
            AppLogger.report("G", "CameraRepositoryImpl.kt:captureBurst", "Burst finished", mapOf("count" to actual.toString()))
        } finally {
            if (acquiredPcMode) {
                runCatching { exitPcMode() }.onFailure {
                    AppLogger.w("captureBurst: PC mode exit failed: ${it.message}")
                }
            }
            _burstRunning.value = false
        }
    }

    /**
     * 拍摄单张但不进入 PC 模式（适用于连拍循环已外层进入 PC 模式的场景）。
     */
    private suspend fun doCaptureImageNoPcMode() {
        if (!ensureConnected()) return
        val conn = currentConnection ?: return
        runCatching {
            waitForDeviceReady()
            val (code, _) = when (brandStrategy.brand) {
                CameraBrand.Nikon -> {
                    val afParam = if (_focusMode.value == FocusMode.AF) 0xFFFFFFFE.toInt() else 0xFFFFFFFF.toInt()
                    val targetParam = if (_cameraSettings.value.storageTarget == StorageTarget.Camera) 1 else 0
                    conn.sendCommand(brandStrategy.captureOperation, afParam, targetParam)
                }
                CameraBrand.Canon -> {
                    brandStrategy.afDriveOperation?.let { conn.sendCommand(it) }
                    conn.sendCommand(PtpConstants.CANON_EOS_OPERATION_REMOTE_RELEASE_ON, 1)
                }
                CameraBrand.Panasonic -> {
                    conn.sendCommand(brandStrategy.captureOperation, 0x03000019)
                }
                else -> {
                    conn.sendCommand(brandStrategy.captureOperation)
                }
            }
            if (code != PtpConstants.RESPONSE_OK && brandStrategy.brand != CameraBrand.Canon) {
                AppLogger.report("G", "CameraRepositoryImpl.kt:doCaptureImageNoPcMode", "Capture primary failed, fallback", mapOf("responseCode" to String.format(Locale.US, "0x%04X", code)))
                conn.sendCommand(PtpConstants.OPERATION_INITIATE_CAPTURE)
            }
            if (brandStrategy.brand == CameraBrand.Canon) {
                delay(100)
                conn.sendCommand(PtpConstants.CANON_EOS_OPERATION_REMOTE_RELEASE_OFF, 1)
            }
            waitForDeviceReady()
        }.onFailure {
            AppLogger.report("G", "CameraRepositoryImpl.kt:doCaptureImageNoPcMode", "Capture error", mapOf("error" to (it.message ?: "unknown")))
            runCatching { conn.sendCommand(PtpConstants.OPERATION_INITIATE_CAPTURE) }
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
                isBulbInProgress = true
                if (brandStrategy.brand == CameraBrand.Nikon) {
                    doNikonBulbExposure(seconds)
                } else {
                    doGenericBulbExposure(seconds)
                }
                if (wasLiveView) runCatching { startLiveView() }
            } catch (_: CancellationException) {
                // 用户主动取消，由外部 stopBulbExposure 处理清理
            } finally {
                isBulbInProgress = false
                _bulbSettings.value = _bulbSettings.value.copy(enabled = false)
            }
        }
    }

    /**
     * 标记 B 门/拍摄等长时操作正在进行；用于在 B 门期间暂停定时属性轮询，
     * 避免在 PTP 会话中发送额外属性查询而让相机切回"正在连接 PC"。
     */
    @Volatile
    private var isBulbInProgress: Boolean = false

    /**
     * 记录相机是否已经处于 PC 远程控制模式（Nikon 0x90C2 / Canon 0x9114 等）。
     * 多次进入只会产生 0xA003 CHANGE_CAMERA_MODE_FAILED，从而让相机一直
     * 卡在切换 PC 模式状态。只有真正需要进入时才发命令，并仅在所有长时操作
     * 结束后退出，避免"PC 模式 → 退出 → 进入"循环造成曝光补偿等属性数据
     * 短暂丢失而出现错乱。
     */
    @Volatile
    private var pcModeActive: Boolean = false

    /**
     * 引用计数：当多个流程同时需要 PC 模式（如 liveview 启动 + bulb 启动）时，
     * 仅在引用计数归零时才退出 PC 模式，避免互相踩踏。
     */
    private val pcModeRefCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Nikon B门完整流程：进入 PC 控制模式 -> 手动曝光模式 -> Bulb ->
     * 开始曝光 -> 等待 -> 终止曝光 -> 恢复快门与模式 -> 退出 PC 控制模式。
     * 使用 try/finally + 引用计数确保即便中途抛异常也能正确退出 PC 模式，
     * 避免相机一直显示"正在连接 PC"而只能通过插拔数据线恢复。
     */
    private suspend fun doNikonBulbExposure(seconds: Int) {
        val conn = currentConnection ?: return
        isBulbInProgress = true
        var acquiredPcMode = false
        try {
            runCatching {
                if (enterPcMode()) acquiredPcMode = true
                waitForDeviceReady()
                // 切到手动模式，确保支持 Bulb
                setShootingMode(ShootingMode.M)
                waitForDeviceReady()
                setShutter("Bulb")
                waitForDeviceReady()
                delay(200)
                // 使用 Nikon 专用 B 门 opcode (0x920B InitiateBulbCapture) 触发曝光，
                // 避免通用 InitiateCaptureRecInMedia (0x9207) 被相机误解为普通拍摄并切换到 PC 模式。
                val bulbOp = brandStrategy.bulbOperation ?: brandStrategy.captureOperation
                val (bulbCode, _) = conn.sendCommand(bulbOp, 0xFFFFFFFF.toInt())
                if (bulbCode != PtpConstants.RESPONSE_OK) {
                    AppLogger.report("G", "CameraRepositoryImpl.kt:doNikonBulbExposure", "Bulb start non-OK", mapOf("op" to String.format(Locale.US, "0x%04X", bulbOp), "responseCode" to String.format(Locale.US, "0x%04X", bulbCode)))
                }
                waitForDeviceReady()
            }.onFailure {
                AppLogger.report("G", "CameraRepositoryImpl.kt:doNikonBulbExposure", "Nikon bulb start error", mapOf("error" to (it.message ?: "unknown")))
                return
            }
            delay(seconds * 1000L)
            doStopBulbExposure()
        } finally {
            if (acquiredPcMode) {
                runCatching { exitPcMode() }.onFailure {
                    AppLogger.w("doNikonBulbExposure: PC mode exit failed: ${it.message}")
                }
            }
            isBulbInProgress = false
        }
    }

    /**
     * 进入 PC 远程控制模式。多次进入通过引用计数去重，避免在 Nikon D5200
     * 等老机身上重复发送 0x90C2 ChangeCameraMode 而收到 0xA003 失败。
     */
    private suspend fun enterPcMode(): Boolean {
        val conn = currentConnection ?: return false
        pcModeRefCount.incrementAndGet()
        if (pcModeActive) return true
        val op = brandStrategy.changeCameraModeOperation ?: run {
            pcModeActive = true
            return true
        }
        val (code, _) = runCatching { conn.sendCommand(op, 1) }
            .getOrDefault(PtpConstants.RESPONSE_GENERAL_ERROR to IntArray(0))
        if (code == PtpConstants.RESPONSE_OK || code == PtpConstants.NIKON_RESPONSE_CHANGE_CAMERA_MODE_FAILED) {
            pcModeActive = true
            return true
        }
        // 失败也要回退引用计数，避免泄漏
        pcModeRefCount.decrementAndGet()
        AppLogger.w("enterPcMode: failed code=${String.format(Locale.US, "0x%04X", code)}")
        return false
    }

    /**
     * 退出 PC 远程控制模式。仅在引用计数归零时真正发送 ChangeCameraMode(0)，
     * 避免 liveview 与 bulb 同时持有时互相退出。
     */
    private suspend fun exitPcMode() {
        val conn = currentConnection ?: return
        val remaining = pcModeRefCount.decrementAndGet()
        if (remaining > 0) return
        if (remaining < 0) {
            pcModeRefCount.set(0)
        }
        val op = brandStrategy.changeCameraModeOperation ?: return
        runCatching {
            conn.sendCommand(op, 0)
            waitForDeviceReady()
        }
        pcModeActive = false
    }

    /**
     * 当 PTP 连接断开或切到新设备时，强制重置 PC 模式与引用计数状态。
     */
    private fun resetPcModeState() {
        pcModeActive = false
        pcModeRefCount.set(0)
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
        // 先停掉 bulbJob，让 doNikonBulbExposure 的 finally 走 exitPcMode()，
        // 然后再主动调用 doStopBulbExposure 发送 0x920C TerminateCapture。
        // 注意：原实现只调用 doStopBulbExposure() 而没有 cancel/join，
        // 会和正在进行的 bulb 协程争抢 PTP 会话，导致相机一直卡在"正在连接 PC"。
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
            // 闪光灯：Nikon 走 0xD064 私有属性 + nikonPtpToFlashMode，
            // 其他品牌走标准 0x501C + 标准名称映射。
            val isNikon = _cameraSettings.value.brand == CameraBrand.Nikon
            val flashCode = if (isNikon) {
                PtpConstants.DEVICE_PROP_NIKON_FLASH_MODE
            } else {
                PtpConstants.DEVICE_PROP_FLASH_MODE
            }
            safeReadProperty(conn, flashCode, "闪光灯")?.let { raw ->
                result["闪光灯"] = if (isNikon) {
                    PtpValueMapper.nikonPtpToFlashMode(raw).name
                } else {
                    flashModeName(raw)
                }
            }
            if (isNikon) {
                safeReadProperty(conn, PtpConstants.DEVICE_PROP_NIKON_FLASH_EXPOSURE_COMP, "闪光补偿")?.let { raw ->
                    val ev = PtpValueMapper.nikonPtpToFlashComp(raw)
                    result["闪光补偿"] = String.format(Locale.US, "%+.1f EV", ev)
                }
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
                firmwareVersion = result["固件版本"] ?: "Unknown",
                manufacturer = result["制造商"] ?: "Unknown"
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
     * Parse DeviceInfo once and return (manufacturer, model, firmwareVersion, supportedProperties).
     * ponytail: one PTP round-trip instead of two separate fetch functions.
     * issue #99: 同时解析 DevicePropertiesSupported 数组，让 setFlashMode 知道设备是否
     * 支持 0xD064/0xD065 私有闪光灯属性，否则回退到标准 0x501C。
     */
    private suspend fun parseDeviceInfoStrings(conn: CameraConnection): Array<String> {
        return runCatching {
            val data = conn.sendCommandWithData(PtpConstants.OPERATION_GET_DEVICE_INFO)
            if (data.size < 12) return@runCatching arrayOf("Unknown", "Unknown", "Unknown")
            val payload = PtpCommand.decodeDataPayload(data)
            if (payload.size < 12) return@runCatching arrayOf("Unknown", "Unknown", "Unknown")
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.remaining() < 12) return@runCatching arrayOf("Unknown", "Unknown", "Unknown")
            buffer.getShort() // StandardVersion
            if (buffer.remaining() < 4) return@runCatching arrayOf("Unknown", "Unknown", "Unknown")
            buffer.getInt() // VendorExtensionID
            if (buffer.remaining() < 2) return@runCatching arrayOf("Unknown", "Unknown", "Unknown")
            buffer.getShort() // VendorExtensionVersion
            PtpCommand.readPtpString(buffer) // vendor extension desc
            if (buffer.remaining() < 2) return@runCatching arrayOf("Unknown", "Unknown", "Unknown")
            buffer.getShort() // FunctionalMode
            // 关键：Nikon D5200 等设备的 DeviceInfo 数组计数是 UINT32，标准 PTP 是 UINT16。
            // 之前的 `buffer.position() + buffer.getInt() * 2` 写法会让 getInt() 把 position
            // 推进 4 字节，再 position() 又拿到新位置，结果多跳 4 字节，导致后续
            // manufacturer/model/version 字符串读偏，显示成 "Unknown"。
            // 修复：先 getInt() 拿到 count，再单独推进 count*2 字节。
            var deviceProperties: Set<Short> = emptySet()
            repeat(5) { idx ->
                if (buffer.remaining() < 4) return@runCatching arrayOf("Unknown", "Unknown", "Unknown")
                val count = buffer.getInt()
                if (count < 0 || count > 256) {
                    return@runCatching arrayOf("Unknown", "Unknown", "Unknown")
                }
                val bytesToSkip = count * 2
                if (bytesToSkip > buffer.remaining()) {
                    return@runCatching arrayOf("Unknown", "Unknown", "Unknown")
                }
                // idx=2 是 DevicePropertiesSupported，记录相机实际支持的属性码
                if (idx == 2) {
                    val start = buffer.position()
                    deviceProperties = buildSet {
                        for (i in 0 until count) {
                            if (buffer.remaining() < 2) break
                            add(buffer.getShort())
                        }
                    }
                    AppLogger.report(
                        "K",
                        "CameraRepositoryImpl.kt:parseDeviceInfoStrings",
                        "Supported device properties",
                        mapOf(
                            "count" to count.toString(),
                            "hasFlash" to deviceProperties.contains(PtpConstants.DEVICE_PROP_FLASH_MODE).toString(),
                            "hasNikonFlash" to deviceProperties.contains(PtpConstants.DEVICE_PROP_NIKON_FLASH_MODE).toString(),
                            "hasNikonFlashComp" to deviceProperties.contains(PtpConstants.DEVICE_PROP_NIKON_FLASH_EXPOSURE_COMP).toString()
                        )
                    )
                    // 已读完 DevicePropertiesSupported；不需再推进
                } else {
                    buffer.position(buffer.position() + bytesToSkip)
                }
            }
            val manufacturer = PtpCommand.readPtpString(buffer)
            val model = PtpCommand.readPtpString(buffer)
            val version = PtpCommand.readPtpString(buffer)
            supportedDeviceProperties = deviceProperties
            // issue #101：报告闪光灯能力，让 UI 决定是否显示"手动弹起闪光灯"提示。
            // canRemotePopup 标志：机身支持 0xD064（典型 D7100+），可远程配置闪光灯模式。
            // D5200/D3200/D7000 等老机身不支持 0xD064，闪光灯必须用户手动弹起。
            _flashCapabilities.value = FlashCapabilities(
                canRemotePopup = deviceProperties.contains(PtpConstants.DEVICE_PROP_NIKON_FLASH_MODE),
                writableFlashMode = deviceProperties.contains(PtpConstants.DEVICE_PROP_FLASH_MODE)
            )
            AppLogger.report(
                "K",
                "CameraRepositoryImpl.kt:parseDeviceInfoStrings",
                "DeviceInfo strings",
                mapOf(
                    "manufacturer" to manufacturer,
                    "model" to model,
                    "version" to version
                )
            )
            arrayOf(manufacturer, model, version)
        }.getOrDefault(arrayOf("Unknown", "Unknown", "Unknown"))
    }

    private suspend fun parseFirmwareVersion(conn: CameraConnection): String =
        parseDeviceInfoStrings(conn).getOrNull(2)?.ifBlank { parseDeviceInfoStrings(conn).getOrNull(1) ?: "Unknown" } ?: "Unknown"

    private suspend fun parseManufacturer(conn: CameraConnection): String =
        parseDeviceInfoStrings(conn).getOrNull(0) ?: "Unknown"

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
        // **关键修复**（issue #105：图库查询后快门失效）：
        // 旧实现 listPhotos 末尾会 runCatching { startLiveView() } 自动重启 liveview，
        // 但 PC 模式引用计数在 stopLiveView 期间被 -1 重置回 0，startLiveView 重新 enterPcMode
        // 让 pcRefCount=1 实际却没在取景（startLiveView 内部 enterPcControlMode 已成功但
        // 帧循环可能因相机未就绪而失败）。后续 setFlashMode 走 enterPcMode() 返回 true
        // 不再真发 ChangeCameraMode(1)，但相机端已因 listPhotos 退出 PC 模式，
        // **0x100B 拍照命令被相机拒收** → 用户感觉"按不了快门"。
        //
        // 修复：**不**自动 restart liveview，让用户主动点"实时取景"按钮重开。
        // 如果之前 liveview 在开，则保持 _liveViewEnabled.value = false，UI 上
        // 实时取景按钮自然显示为未激活态，用户可一键重启。
        if (wasLiveView) {
            AppLogger.report("I", "CameraRepositoryImpl.kt:listPhotos",
                "LiveView paused for gallery query, user must tap to resume",
                mapOf("items" to items.size.toString()))
        }
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
     * 仅使用当前品牌策略定义的实时取景启动操作码，不再尝试跨品牌 fallback。
     * 向 Nikon 等机身发送 Canon/Panasonic 厂商操作码会把 PTP 会话推入错误状态，
     * 导致后续命令无响应。
     */
    private fun liveViewStartCandidates(): List<Pair<Short, IntArray>> {
        val op = brandStrategy.liveViewStartOperation ?: return emptyList()
        return listOf(op to brandStrategy.liveViewStartParams)
    }

    /**
     * 仅使用当前品牌策略定义的实时取景取图操作码，不再尝试跨品牌 fallback。
     */
    private fun liveViewGetCandidates(): List<Short> {
        val op = brandStrategy.liveViewGetOperation ?: return emptyList()
        return listOf(op)
    }

    /**
     * 启动品牌特定事件轮询循环。Nikon/Canon EOS 等老机身在 PC 模式下会积压事件，
     * 不及时读取会导致命令被 busy 或进入"正在连接 PC"的假死状态。
     *
     * 关键：Nikon 等老机身（典型如 D5200）对事件轮询频率非常敏感——
     * 每 800ms 轮询一次会让机身一直显示"正在连接 PC"，并占用大量 USB 带宽。
     * 因此：
     * 1. 空闲间隔设为 3 秒（仅在 B 门/连拍/取景全部停止时才使用）
     * 2. 活动期（B 门/连拍/取景）**只 delay 不发送命令**——
     *    一旦调用 sendCommand(pollOp) 就会和实时取景帧循环抢 ptpLock Mutex，
     *    帧循环每 200ms 卡一次，连续失败累计触发退出导致实时取景"卡死"。
     *    实时取景本身已经把 USB 总线跑满，相机会主动把事件推到 bulk 端点；
     *    帧循环读数据时事件会跟随数据一起返回，无需独立轮询。
     */
    private fun startEventLoop() {
        val pollOp = brandStrategy.eventPollOperation ?: return
        eventLoopJob?.cancel()
        eventLoopJob = scope.launch {
            while (isActive && ensureConnected()) {
                val active = isBulbInProgress || _burstRunning.value || _liveViewEnabled.value
                if (active) {
                    // 活动期不发送任何命令，仅延时让出 CPU。
                    // 事件会跟随实时取景/拍摄数据流一并回到主线程，由 extractDataAndResponse
                    // 顺带处理（TID 不匹配的事件容器会被丢弃，不会污染主命令响应）。
                    delay(200)
                    continue
                }
                // 空闲期使用 3 秒长间隔，降低 USB 总线占用，
                // 避免 Nikon 机身在空闲时仍被高频事件轮询卡在"正在连接 PC"
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
                delay(3000)
            }
        }
    }

    private fun stopEventLoop() {
        eventLoopJob?.cancel()
        eventLoopJob = null
    }

    /**
     * 启动周期属性同步：在连接且非活动状态时，每 5 秒同步一次曝光参数。
     * 这样无需用户手动触发即可让 UI（曝光补偿 / ISO / 光圈 / 快门）与相机保持一致，
     * 避免在事件循环高频 GET_EVENT 干扰下让用户看到"乱跳"或"残留"的数据。
     *
     * 关键安全保证：仅在未处于 B 门 / 连拍 / 取景状态时同步，
     * 避免与正在进行的命令争抢 USB 端点；同步任务之间用 ptpLock 互斥。
     */
    private fun startPeriodicPropertySync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = scope.launch {
            while (isActive) {
                delay(5000)
                if (!isActive) break
                if (_connectionState.value !is ConnectionState.Connected) continue
                if (isBulbInProgress || _burstRunning.value || _liveViewEnabled.value) continue
                // 仅在事件循环空闲时才同步，避免和 GET_EVENT 抢 USB 端点
                val active = eventLoopJob?.isActive == true
                if (active) {
                    runCatching { readNikonExposureFromCamera(delayMs = 0) }
                }
            }
        }
    }

    private fun stopPeriodicPropertySync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = null
    }

    /**
     * 启动心跳保活协程。连接成功后调用，每 heartbeatIntervalMs 探测一次。
     * 心跳与所有 PTP 命令通过 ptpLock 串行化，相机在取景/B 门/连拍中时
     * 心跳被自然阻塞（等待获取锁）但**不会失败**，因为相机只是忙。
     *
     * 失败判定的关键：
     * - bulkTransfer OUT 返回 -1 → 真正失败（USB 端点死了）
     * - bulkTransfer OUT 超时（>1500ms）→ 真正失败（相机没反应）
     * - 协议层异常（IOException）→ 真正失败
     * - 成功获取 Response → 重置连续失败计数
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        consecutiveHeartbeatFailures = 0
        heartbeatJob = scope.launch {
            while (isActive) {
                // 功耗优化：liveview/burst/bulb 期间拉长间隔到 10s
                val interval = if (_liveViewEnabled.value || _burstRunning.value || isBulbInProgress) {
                    heartbeatIntervalLiveviewMs
                } else {
                    heartbeatIntervalMs
                }
                delay(interval)
                if (!isActive) break
                if (_connectionState.value !is ConnectionState.Connected) {
                    consecutiveHeartbeatFailures = 0
                    continue
                }
                val ok = runCatching { performHeartbeat() }.getOrDefault(false)
                if (ok) {
                    if (consecutiveHeartbeatFailures > 0) {
                        AppLogger.report("I", "CameraRepositoryImpl.kt:startHeartbeat", "Heartbeat recovered",
                            mapOf("prevFailures" to consecutiveHeartbeatFailures.toString()))
                    }
                    consecutiveHeartbeatFailures = 0
                } else {
                    consecutiveHeartbeatFailures++
                    AppLogger.report("W", "CameraRepositoryImpl.kt:startHeartbeat", "Heartbeat failed",
                        mapOf("consecutive" to consecutiveHeartbeatFailures.toString(),
                              "max" to maxConsecutiveHeartbeatFailures.toString()))
                    if (consecutiveHeartbeatFailures >= maxConsecutiveHeartbeatFailures) {
                        AppLogger.report("E", "CameraRepositoryImpl.kt:startHeartbeat", "Connection dead, entering Error state")
                        _connectionState.value = ConnectionState.Error("连接已断开（心跳连续失败 ${consecutiveHeartbeatFailures} 次）")
                        stopLiveViewInternal()
                        stopEventLoop()
                        stopPeriodicPropertySync()
                        stopHeartbeat()
                        // **关键**（issue: 实时取景画面残留）：心跳判定连接死亡时**立即**
                        // 清空 frame 和 enabled 标志，UI 同步收到 Empty Frame 事件。
                        // 避免旧画面一直显示。
                        _liveViewFrame.value = null
                        _liveViewEnabled.value = false
                        break
                    }
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        consecutiveHeartbeatFailures = 0
    }

    /**
     * 实际执行一次心跳：通过 CameraConnection 接口的 sendCommandWithData 发 GetDeviceInfo
     * (0x1001)。GetDeviceInfo 是 Nikon D5200 等相机**最轻的** PTP 命令（仅 4 字节头，
     * 无参数），返回 ~150-300 字节 DeviceInfo。
     *
     * ptpLock 在 UsbCameraConnection 内部是 private 的（issue #95 修复后所有 PTP 命令
     * 都已通过 withLock 串行化），这里**不**直接拿锁，避免跨层耦合。心跳通过接口调用
     * 会自然等相机空档，camera 忙着取景/曝光时心跳排队等几毫秒，**不会**失败。
     *
     * **校验**（issue #125 修复：之前误把 Data 容器 type 当 Response 校验）：
     * - `sendCommandWithData(0x1001)` 返回的是 Data 容器**完整字节**（12 字节 PTP 头
     *   + DeviceInfo payload），不是 Response 容器。`extractDataAndResponse` 只把
     *   Data 容器内容拷到 `dataBytes`，Response 容器解析后只留 responseCode。
     *   所以 `data[4..5]` 一定是 `0x0002` (Data type)，**不能**判 0x0001 (Response)。
     * - 校验项：
     *   1. data 长度 >= 12（最小 Data 容器头）
     *   2. data[0..3] 容器长度合理 (12..65535) 且与 data.size 一致
     *   3. data[4..5] == 0x0002 (Data container type)
     *   4. data[6..7] == 0x1001 (GetDeviceInfo operation code)
     * - 任一不满足 → 视作心跳失败
     */
    private suspend fun performHeartbeat(): Boolean {
        val conn = currentConnection ?: return false
        return runCatching {
            val data = conn.sendCommandWithData(0x1001.toShort())
            if (data.size < 12) {
                AppLogger.report("W", "CameraRepositoryImpl.kt:performHeartbeat",
                    "Heartbeat data too short",
                    mapOf("size" to data.size.toString()))
                return@runCatching false
            }
            val length = (data[0].toInt() and 0xFF) or
                    (data[1].toInt() and 0xFF shl 8) or
                    (data[2].toInt() and 0xFF shl 16) or
                    (data[3].toInt() and 0xFF shl 24)
            val type = (data[4].toInt() and 0xFF) or (data[5].toInt() and 0xFF shl 8)
            val op = (data[6].toInt() and 0xFF) or (data[7].toInt() and 0xFF shl 8)
            // length 必须 > 0 且 < 65535，且与 data.size 一致（Data 容器完整返回）
            val lengthOk = length in 12..65535 && length == data.size
            // **fix #125**：0x1001 GetDeviceInfo 返回 Data 容器 → 0x0002
            // 之前误判 0x0001 (Response type)，导致心跳永远失败。
            val typeOk = type == 0x0002
            val opOk = op == 0x1001
            if (!lengthOk || !typeOk || !opOk) {
                AppLogger.report("W", "CameraRepositoryImpl.kt:performHeartbeat",
                    "Heartbeat data validation failed",
                    mapOf("length" to length.toString(),
                          "type" to String.format(Locale.US, "0x%04X", type),
                          "op" to String.format(Locale.US, "0x%04X", op),
                          "size" to data.size.toString()))
            }
            lengthOk && typeOk && opOk
        }.getOrDefault(false)
    }

    /**
     * Poll device-ready command until the camera reports OK or the timeout
     * expires. This mirrors libgphoto2's nikon_wait_busy and is required after
     * starting live view, changing modes, or triggering capture on cameras like
     * the D5200. If the brand strategy has no deviceReadyOperation, this is a
     * simple delay to avoid hammering the bus.
     *
     * 注意：日志显示 0x90C8 DeviceReady 在每次调用中都会返回 0x2019 DeviceBusy
     * 数十次（紧循环无延迟），会让相机一直处于"正在连接 PC"状态。这里强制
     * 最小 60ms 间隔 + 指数退避 + 总次数上限，避免对 PTP 会话造成雪崩式
     * 压力。
     */
    private suspend fun waitForDeviceReady(
        conn: CameraConnection? = currentConnection,
        waitMs: Long = 80,
        timeoutMs: Long = 2000,
        maxAttempts: Int = 25
    ) {
        conn ?: return
        val readyOp = brandStrategy.deviceReadyOperation
        if (readyOp == null) {
            delay(waitMs.coerceAtLeast(20))
            return
        }
        val end = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        var currentDelay = waitMs.coerceAtLeast(60)
        while (attempt < maxAttempts && System.currentTimeMillis() < end) {
            val (code, _) = runCatching {
                conn.sendCommand(readyOp)
            }.getOrDefault(PtpConstants.RESPONSE_GENERAL_ERROR to IntArray(0))
            attempt++
            if (code == PtpConstants.RESPONSE_OK) return
            // 0x2019 DeviceBusy 是相机的正常状态机返回，不应当作错误抛出，
            // 只需要退避重试
            delay(currentDelay)
            // 指数退避，但封顶 250ms，避免长时间阻塞 UI
            currentDelay = (currentDelay * 2).coerceAtMost(250)
        }
        if (attempt >= maxAttempts) {
            AppLogger.w("waitForDeviceReady: hit max attempts=$maxAttempts, op=${String.format(Locale.US, "0x%04X", readyOp)}")
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
