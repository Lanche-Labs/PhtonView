package com.phtontools.phtonview.usb.ptp

import com.phtontools.phtonview.data.model.CameraBrand

/**
 * 品牌连接策略：预置各品牌常用的 PTP 操作码与设备属性码，
 * 连接时根据 DeviceInfo 识别品牌并应用对应策略；若策略失败则回退到通用 PTP。
 * 操作码数值均来自 libgphoto2 的 ptp.h，覆盖 Canon EOS、Sony、Fuji、Panasonic、Olympus OMD。
 */
interface BrandStrategy {
    val brand: CameraBrand
    val vendorExtensionId: Int?

    /** 实时取景相关操作码，null 表示该品牌当前不支持/不需要 */
    val liveViewStartOperation: Short?
    val liveViewStopOperation: Short?
    val liveViewGetOperation: Short?

    /** 拍摄相关操作码 */
    val captureOperation: Short
    val bulbOperation: Short?
    val changeCameraModeOperation: Short?
    val terminateCaptureOperation: Short?
    val afDriveOperation: Short?
    val changeAfAreaOperation: Short?

    /** 对焦区域模式属性码 */
    val afAreaModeProperty: Short?

    /** 快门属性码：主选 + 备选 */
    val primaryShutterProperty: Short
    val fallbackShutterProperty: Short?

    /** 实时取景状态属性码 */
    val liveViewStatusProperty: Short?
    val liveViewProhibitProperty: Short?
    val recordingMediaProperty: Short?

    /** 事件轮询命令：USB 上需要主动轮询事件的品牌（如 Nikon/Canon EOS） */
    val eventPollOperation: Short?

    /** 设备就绪轮询命令：Nikon 等老机身需要 */
    val deviceReadyOperation: Short?

    /** 实时取景启动是否需要额外参数（如 Panasonic 0x0d000010） */
    val liveViewStartParams: IntArray

    companion object {
        fun forBrand(brand: CameraBrand): BrandStrategy = when (brand) {
            CameraBrand.Nikon -> NikonStrategy
            CameraBrand.Canon -> CanonStrategy
            CameraBrand.Sony -> SonyStrategy
            CameraBrand.Fuji -> FujiStrategy
            CameraBrand.Panasonic -> PanasonicStrategy
            CameraBrand.Olympus -> OlympusStrategy
            CameraBrand.Pentax -> PentaxStrategy
            CameraBrand.Ricoh -> RicohStrategy
            CameraBrand.Leica -> LeicaStrategy
            CameraBrand.Sigma -> SigmaStrategy
            CameraBrand.Tamron -> TamronStrategy
            CameraBrand.Kodak -> KodakStrategy
            CameraBrand.Generic -> GenericStrategy
        }
    }
}

object NikonStrategy : BrandStrategy {
    override val brand = CameraBrand.Nikon
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_NIKON
    override val liveViewStartOperation = PtpConstants.NIKON_OPERATION_START_LIVEVIEW
    override val liveViewStopOperation = PtpConstants.NIKON_OPERATION_STOP_LIVEVIEW
    override val liveViewGetOperation = PtpConstants.NIKON_OPERATION_GET_LIVEVIEW_IMAGE
    override val captureOperation = PtpConstants.NIKON_OPERATION_INITIATE_CAPTURE_REC_IN_MEDIA
    override val bulbOperation = PtpConstants.NIKON_OPERATION_INITIATE_BULB_CAPTURE
    override val changeCameraModeOperation = PtpConstants.NIKON_OPERATION_CHANGE_CAMERA_MODE
    override val terminateCaptureOperation = PtpConstants.NIKON_OPERATION_TERMINATE_CAPTURE
    override val afDriveOperation = PtpConstants.NIKON_OPERATION_AF_DRIVE
    override val changeAfAreaOperation = PtpConstants.NIKON_OPERATION_CHANGE_AF_AREA
    override val afAreaModeProperty = PtpConstants.DEVICE_PROP_NIKON_AF_AREA_MODE
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = PtpConstants.DEVICE_PROP_NIKON_EXPOSURE_TIME
    override val liveViewStatusProperty = PtpConstants.DEVICE_PROP_NIKON_LIVE_VIEW_STATUS
    override val liveViewProhibitProperty = PtpConstants.DEVICE_PROP_NIKON_LIVE_VIEW_PROHIBIT_CONDITION
    override val recordingMediaProperty = PtpConstants.DEVICE_PROP_NIKON_RECORDING_MEDIA
    override val eventPollOperation = PtpConstants.NIKON_OPERATION_GET_EVENT
    override val deviceReadyOperation = PtpConstants.NIKON_OPERATION_DEVICE_READY
    override val liveViewStartParams = IntArray(0)
}

object CanonStrategy : BrandStrategy {
    override val brand = CameraBrand.Canon
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_CANON
    // Canon EOS 使用 0x9151/0x9152/0x9153 进行实时取景；
    // 拍摄使用标准 0x100E 或 EOS RemoteRelease 0x910F/0x9128。
    override val liveViewStartOperation = PtpConstants.CANON_EOS_OPERATION_INITIATE_VIEWFINDER
    override val liveViewStopOperation = PtpConstants.CANON_EOS_OPERATION_TERMINATE_VIEWFINDER
    override val liveViewGetOperation = PtpConstants.CANON_EOS_OPERATION_GET_VIEWFINDER_DATA
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = PtpConstants.CANON_EOS_OPERATION_SET_REMOTE_MODE
    override val terminateCaptureOperation = null
    override val afDriveOperation = PtpConstants.CANON_EOS_OPERATION_DO_AF
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = PtpConstants.DEVICE_PROP_CANON_EOS_EVF_MODE
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = PtpConstants.CANON_EOS_OPERATION_GET_EVENT
    override val deviceReadyOperation = null
    override val liveViewStartParams = IntArray(0)
}

object SonyStrategy : BrandStrategy {
    override val brand = CameraBrand.Sony
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_SONY
    // Sony 实时取景依赖 SDIO_Connect/ControlDevice 与属性通知，
    // 取图尝试 0x926E；拍摄使用标准 0x100E。
    override val liveViewStartOperation = PtpConstants.SONY_OPERATION_SDIO_CONNECT
    override val liveViewStopOperation = PtpConstants.SONY_OPERATION_SDIO_CONNECT
    override val liveViewGetOperation = PtpConstants.SONY_OPERATION_GET_LIVEVIEW_IMAGE
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = PtpConstants.SONY_OPERATION_SDIO_CONTROL_DEVICE
    override val terminateCaptureOperation = null
    override val afDriveOperation = PtpConstants.SONY_OPERATION_SDIO_CONTROL_DEVICE
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = PtpConstants.DEVICE_PROP_SONY_RecordingState
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = null // Sony 事件通过异步中断/PTP-IP 事件通道上报
    override val deviceReadyOperation = null
    override val liveViewStartParams = intArrayOf(1, 0, 0)
}

object FujiStrategy : BrandStrategy {
    override val brand = CameraBrand.Fuji
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_FUJI
    // Fuji 实时取景通过 InitiateOpenCapture (0x101C) 启动，
    // 然后从 object handle 0x80000001 读取图像。
    override val liveViewStartOperation = PtpConstants.OPERATION_INITIATE_OPEN_CAPTURE
    override val liveViewStopOperation = PtpConstants.OPERATION_TERMINATE_OPEN_CAPTURE
    override val liveViewGetOperation = PtpConstants.FUJI_OPERATION_GET_CAPTURE_PREVIEW
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = PtpConstants.OPERATION_TERMINATE_OPEN_CAPTURE
    override val afDriveOperation = PtpConstants.FUJI_OPERATION_SET_FOCUS_POINT
    override val changeAfAreaOperation = PtpConstants.FUJI_OPERATION_SET_FOCUS_POINT
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = PtpConstants.DEVICE_PROP_FUJI_LiveViewImageQuality
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = null
    override val deviceReadyOperation = null
    override val liveViewStartParams = intArrayOf(0, 0)
}

object PanasonicStrategy : BrandStrategy {
    override val brand = CameraBrand.Panasonic
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_PANASONIC
    // Panasonic 实时取景：0x9412 启动（参数 0x0d000010），0x9706 取图。
    override val liveViewStartOperation = PtpConstants.PANASONIC_OPERATION_LIVEVIEW
    override val liveViewStopOperation = PtpConstants.PANASONIC_OPERATION_LIVEVIEW
    override val liveViewGetOperation = PtpConstants.PANASONIC_OPERATION_LIVEVIEW_IMAGE
    override val captureOperation = PtpConstants.PANASONIC_OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = null
    override val afDriveOperation = PtpConstants.PANASONIC_OPERATION_MANUAL_FOCUS_DRIVE
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = PtpConstants.DEVICE_PROP_PANASONIC_LiveView
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = PtpConstants.PANASONIC_OPERATION_POLL_EVENTS
    override val deviceReadyOperation = null
    override val liveViewStartParams = intArrayOf(0x0d000010)
}

object OlympusStrategy : BrandStrategy {
    override val brand = CameraBrand.Olympus
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_OLYMPUS
    // Olympus OMD 实时取景：设置 D052=0x04000300 后通过 0x9484 取图。
    // 启动不通过单一操作码，而是由 CameraRepositoryImpl 设置属性完成。
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = PtpConstants.OLYMPUS_OPERATION_GET_LIVEVIEW_IMAGE
    override val captureOperation = PtpConstants.OLYMPUS_OPERATION_OMD_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = null
    override val afDriveOperation = PtpConstants.OLYMPUS_OPERATION_OMD_MF_DRIVE
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = PtpConstants.DEVICE_PROP_OLYMPUS_LiveViewModeOM
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = PtpConstants.OLYMPUS_OPERATION_OMD_CHANGED_PROPERTIES
    override val deviceReadyOperation = null
    override val liveViewStartParams = IntArray(0)
}

object PentaxStrategy : BrandStrategy {
    override val brand = CameraBrand.Pentax
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_PENTAX
    // Pentax 机身通常使用标准 PTP/MTP，实时取景与拍摄无公开厂商扩展操作码，
    // 在确认具体取图句柄前不启用实时取景，避免发送错误命令导致连接中断。
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = null
    override val afDriveOperation = null
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = null
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = null
    override val deviceReadyOperation = null
    override val liveViewStartParams = IntArray(0)
}

object RicohStrategy : BrandStrategy {
    override val brand = CameraBrand.Ricoh
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_RICOH
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = null
    override val afDriveOperation = null
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = null
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = null
    override val deviceReadyOperation = null
    override val liveViewStartParams = IntArray(0)
}

object LeicaStrategy : BrandStrategy {
    override val brand = CameraBrand.Leica
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_LEICA
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = null
    override val afDriveOperation = null
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = null
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = null
    override val deviceReadyOperation = null
    override val liveViewStartParams = IntArray(0)
}

object SigmaStrategy : BrandStrategy {
    override val brand = CameraBrand.Sigma
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_SIGMA
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = null
    override val afDriveOperation = null
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = null
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = null
    override val deviceReadyOperation = null
    override val liveViewStartParams = IntArray(0)
}

object TamronStrategy : BrandStrategy {
    override val brand = CameraBrand.Tamron
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_TAMRON
    // Tamron 主要为镜头厂商，若出现 USB 相机/底座则按标准 PTP 处理，不启用实时取景。
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = null
    override val afDriveOperation = null
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = null
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = null
    override val deviceReadyOperation = null
    override val liveViewStartParams = IntArray(0)
}

object KodakStrategy : BrandStrategy {
    override val brand = CameraBrand.Kodak
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_KODAK
    // Kodak 是早期 PTP 实现代表，使用标准操作码；老机型通常无实时取景。
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = null
    override val afDriveOperation = null
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = null
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = null
    override val deviceReadyOperation = null
    override val liveViewStartParams = IntArray(0)
}

object GenericStrategy : BrandStrategy {
    override val brand = CameraBrand.Generic
    override val vendorExtensionId = null
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
    override val bulbOperation = null
    override val changeCameraModeOperation = null
    override val terminateCaptureOperation = null
    override val afDriveOperation = null
    override val changeAfAreaOperation = null
    override val afAreaModeProperty = null
    override val primaryShutterProperty = PtpConstants.DEVICE_PROP_EXPOSURE_TIME
    override val fallbackShutterProperty = null
    override val liveViewStatusProperty = null
    override val liveViewProhibitProperty = null
    override val recordingMediaProperty = null
    override val eventPollOperation = null
    override val deviceReadyOperation = null
    override val liveViewStartParams = IntArray(0)
}
