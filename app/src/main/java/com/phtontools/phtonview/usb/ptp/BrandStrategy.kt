package com.phtontools.phtonview.usb.ptp

import com.phtontools.phtonview.data.model.CameraBrand

/**
 * 品牌连接策略：预置各品牌常用的 PTP 操作码与设备属性码，
 * 连接时根据 DeviceInfo 识别品牌并应用对应策略；若策略失败则回退到通用 PTP。
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

    companion object {
        fun forBrand(brand: CameraBrand): BrandStrategy = when (brand) {
            CameraBrand.Nikon -> NikonStrategy
            CameraBrand.Canon -> CanonStrategy
            CameraBrand.Sony -> SonyStrategy
            CameraBrand.Fuji -> FujiStrategy
            CameraBrand.Panasonic -> PanasonicStrategy
            CameraBrand.Olympus -> OlympusStrategy
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
}

object CanonStrategy : BrandStrategy {
    override val brand = CameraBrand.Canon
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_CANON
    // 佳能 EOS 使用标准 0x100E 拍摄；实时取景专用码 0x91E8/0x91E9，取图未公开则回退标准
    override val liveViewStartOperation = PtpConstants.CANON_OPERATION_START_LIVEVIEW
    override val liveViewStopOperation = PtpConstants.CANON_OPERATION_STOP_LIVEVIEW
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
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
}

object SonyStrategy : BrandStrategy {
    override val brand = CameraBrand.Sony
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_SONY
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = PtpConstants.SONY_OPERATION_GET_LIVEVIEW_IMAGE
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
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
}

object FujiStrategy : BrandStrategy {
    override val brand = CameraBrand.Fuji
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_FUJI
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = PtpConstants.FUJI_OPERATION_GET_LIVEVIEW_IMAGE
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
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
}

object PanasonicStrategy : BrandStrategy {
    override val brand = CameraBrand.Panasonic
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_PANASONIC
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
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
}

object OlympusStrategy : BrandStrategy {
    override val brand = CameraBrand.Olympus
    override val vendorExtensionId = PtpConstants.VENDOR_EXTENSION_OLYMPUS
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
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
}

object GenericStrategy : BrandStrategy {
    override val brand = CameraBrand.Generic
    override val vendorExtensionId = null
    override val liveViewStartOperation = null
    override val liveViewStopOperation = null
    override val liveViewGetOperation = null
    override val captureOperation = PtpConstants.OPERATION_INITIATE_CAPTURE
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
}
