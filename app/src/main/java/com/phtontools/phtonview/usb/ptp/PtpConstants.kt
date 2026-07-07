package com.phtontools.phtonview.usb.ptp

/**
 * PTP / MTP 协议常用常量定义。
 */
object PtpConstants {

    // 容器类型
    const val CONTAINER_TYPE_COMMAND: Short = 1
    const val CONTAINER_TYPE_DATA: Short = 2
    const val CONTAINER_TYPE_RESPONSE: Short = 3
    const val CONTAINER_TYPE_EVENT: Short = 4

    // 标准 PTP 操作码
    const val OPERATION_GET_DEVICE_INFO: Short = 0x1001
    const val OPERATION_OPEN_SESSION: Short = 0x1002
    const val OPERATION_CLOSE_SESSION: Short = 0x1003
    const val OPERATION_GET_STORAGE_IDS: Short = 0x1004
    const val OPERATION_GET_STORAGE_INFO: Short = 0x1005
    const val OPERATION_GET_NUM_OBJECTS: Short = 0x1006
    const val OPERATION_GET_OBJECT_HANDLES: Short = 0x1007
    const val OPERATION_GET_OBJECT_INFO: Short = 0x1008
    const val OPERATION_GET_OBJECT: Short = 0x1009
    const val OPERATION_GET_THUMB: Short = 0x100A
    const val OPERATION_DELETE_OBJECT: Short = 0x100B
    const val OPERATION_INITIATE_CAPTURE: Short = 0x100E
    const val OPERATION_INITIATE_OPEN_CAPTURE: Short = 0x101C
    const val OPERATION_TERMINATE_OPEN_CAPTURE: Short = 0x1018
    const val OPERATION_DEVICE_PROP_DESC: Short = 0x1014
    const val OPERATION_DEVICE_PROP_VALUE_GET: Short = 0x1015
    const val OPERATION_DEVICE_PROP_VALUE_SET: Short = 0x1016

    // 品牌 Vendor Extension ID（来自 DeviceInfo），数值与 libgphoto2 的 PTP_VENDOR_* 对齐。
    const val VENDOR_EXTENSION_MICROSOFT: Int = 0x00000006
    const val VENDOR_EXTENSION_NIKON: Int = 0x0000000A
    const val VENDOR_EXTENSION_CANON: Int = 0x0000000B
    const val VENDOR_EXTENSION_FUJI: Int = 0x0000000E
    const val VENDOR_EXTENSION_SONY: Int = 0x00000011
    const val VENDOR_EXTENSION_PANASONIC: Int = 0x0000001C
    const val VENDOR_EXTENSION_OLYMPUS: Int = 0x0000FFFE
    const val VENDOR_EXTENSION_OLYMPUS_OMD: Int = 0x0000FFFD
    const val VENDOR_EXTENSION_KODAK: Int = 0x00000001
    const val VENDOR_EXTENSION_LEICA: Int = 0x00000000
    const val VENDOR_EXTENSION_PENTAX: Int = 0x00000000
    const val VENDOR_EXTENSION_RICOH: Int = 0x00000000
    const val VENDOR_EXTENSION_SIGMA: Int = 0x00000000
    const val VENDOR_EXTENSION_TAMRON: Int = 0x00000000

    // 佳能厂商扩展操作码（EOS 系列，来自 libgphoto2）
    const val CANON_OPERATION_GET_OBJECT_SIZE: Short = 0x9009.toShort()
    const val CANON_OPERATION_LOCK_DEVICE_UI: Short = 0x9004.toShort()
    const val CANON_OPERATION_UNLOCK_DEVICE_UI: Short = 0x9005.toShort()
    const val CANON_OPERATION_DO_AE_AF_AWB: Short = 0x900D.toShort()
    const val CANON_EOS_OPERATION_SET_REMOTE_MODE: Short = 0x9114.toShort()
    const val CANON_EOS_OPERATION_SET_EVENT_MODE: Short = 0x9115.toShort()
    const val CANON_EOS_OPERATION_GET_EVENT: Short = 0x9116.toShort()
    const val CANON_EOS_OPERATION_SET_UI_LOCK: Short = 0x911B.toShort()
    const val CANON_EOS_OPERATION_RESET_UI_LOCK: Short = 0x911C.toShort()
    const val CANON_EOS_OPERATION_REMOTE_RELEASE: Short = 0x910F.toShort()
    const val CANON_EOS_OPERATION_REMOTE_RELEASE_ON: Short = 0x9128.toShort()
    const val CANON_EOS_OPERATION_REMOTE_RELEASE_OFF: Short = 0x9129.toShort()
    const val CANON_EOS_OPERATION_BULB_START: Short = 0x9125.toShort()
    const val CANON_EOS_OPERATION_BULB_END: Short = 0x9126.toShort()
    const val CANON_EOS_OPERATION_INITIATE_VIEWFINDER: Short = 0x9151.toShort()
    const val CANON_EOS_OPERATION_TERMINATE_VIEWFINDER: Short = 0x9152.toShort()
    const val CANON_EOS_OPERATION_GET_VIEWFINDER_DATA: Short = 0x9153.toShort()
    const val CANON_EOS_OPERATION_DO_AF: Short = 0x9154.toShort()
    const val CANON_EOS_OPERATION_DRIVE_LENS: Short = 0x9155.toShort()
    const val CANON_EOS_OPERATION_AF_CANCEL: Short = 0x9160.toShort()

    // 索尼厂商扩展操作码（来自 libgphoto2）
    const val SONY_OPERATION_SDIO_CONNECT: Short = 0x9201.toShort()
    const val SONY_OPERATION_SDIO_GET_EXT_DEVICE_INFO: Short = 0x9202.toShort()
    const val SONY_OPERATION_GET_DEVICE_PROPDESC: Short = 0x9203.toShort()
    const val SONY_OPERATION_GET_DEVICE_PROPERTY_VALUE: Short = 0x9204.toShort()
    const val SONY_OPERATION_SDIO_SET_EXT_DEVICE_PROP_VALUE: Short = 0x9205.toShort()
    const val SONY_OPERATION_GET_CONTROL_DEVICE_DESC: Short = 0x9206.toShort()
    const val SONY_OPERATION_SDIO_CONTROL_DEVICE: Short = 0x9207.toShort()
    const val SONY_OPERATION_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO: Short = 0x9209.toShort()
    const val SONY_OPERATION_GET_LIVEVIEW_IMAGE: Short = 0x926E.toShort()

    // 富士厂商扩展操作码（来自 libgphoto2）
    const val FUJI_OPERATION_INITIATE_MOVIE_CAPTURE: Short = 0x9020.toShort()
    const val FUJI_OPERATION_TERMINATE_MOVIE_CAPTURE: Short = 0x9021.toShort()
    const val FUJI_OPERATION_GET_CAPTURE_PREVIEW: Short = 0x9022.toShort()
    const val FUJI_OPERATION_SET_FOCUS_POINT: Short = 0x9026.toShort()
    const val FUJI_OPERATION_RESET_FOCUS_POINT: Short = 0x9027.toShort()
    const val FUJI_OPERATION_GET_LIVEVIEW_IMAGE: Short = 0x9416.toShort()

    // 松下厂商扩展操作码（来自 libgphoto2）
    const val PANASONIC_OPERATION_OPEN_SESSION: Short = 0x9102.toShort()
    const val PANASONIC_OPERATION_CLOSE_SESSION: Short = 0x9103.toShort()
    const val PANASONIC_OPERATION_GET_PROPERTY: Short = 0x9402.toShort()
    const val PANASONIC_OPERATION_SET_PROPERTY: Short = 0x9403.toShort()
    const val PANASONIC_OPERATION_INITIATE_CAPTURE: Short = 0x9404.toShort()
    const val PANASONIC_OPERATION_LIVEVIEW: Short = 0x9412.toShort()
    const val PANASONIC_OPERATION_POLL_EVENTS: Short = 0x9414.toShort()
    const val PANASONIC_OPERATION_MANUAL_FOCUS_DRIVE: Short = 0x9416.toShort()
    const val PANASONIC_OPERATION_LIVEVIEW_IMAGE: Short = 0x9706.toShort()

    // 奥林巴斯厂商扩展操作码（来自 libgphoto2）
    const val OLYMPUS_OPERATION_CAPTURE: Short = 0x9101.toShort()
    const val OLYMPUS_OPERATION_OMD_CAPTURE: Short = 0x9481.toShort()
    const val OLYMPUS_OPERATION_GET_LIVEVIEW_IMAGE: Short = 0x9484.toShort()
    const val OLYMPUS_OPERATION_OMD_GET_IMAGE: Short = 0x9485.toShort()
    const val OLYMPUS_OPERATION_OMD_CHANGED_PROPERTIES: Short = 0x9486.toShort()
    const val OLYMPUS_OPERATION_OMD_MF_DRIVE: Short = 0x9487.toShort()
    const val OLYMPUS_OPERATION_OMD_SET_PROPERTIES: Short = 0x9489.toShort()

    // 尼康厂商扩展操作码
    const val NIKON_OPERATION_AF_DRIVE: Short = 0x90C1.toShort()
    const val NIKON_OPERATION_GET_EVENT: Short = 0x90C7.toShort()
    const val NIKON_OPERATION_DEVICE_READY: Short = 0x90C8.toShort()
    const val NIKON_OPERATION_MFDRIVE: Short = 0x9204.toShort()
    const val NIKON_OPERATION_START_LIVEVIEW: Short = 0x9201.toShort()
    const val NIKON_OPERATION_STOP_LIVEVIEW: Short = 0x9202.toShort()
    const val NIKON_OPERATION_GET_LIVEVIEW_IMAGE: Short = 0x9203.toShort()
    const val NIKON_OPERATION_CHANGE_AF_AREA: Short = 0x9205.toShort()
    const val NIKON_OPERATION_AFDRIVE_D850: Short = 0x90C1.toShort()
    const val NIKON_OPERATION_CHANGE_CAMERA_MODE: Short = 0x90C2.toShort()
    const val NIKON_OPERATION_TERMINATE_CAPTURE: Short = 0x920C.toShort()
    const val NIKON_OPERATION_INITIATE_CAPTURE_REC_IN_MEDIA: Short = 0x9207.toShort()

    // 响应码
    const val RESPONSE_OK: Short = 0x2001
    const val RESPONSE_GENERAL_ERROR: Short = 0x2002
    const val RESPONSE_SESSION_NOT_OPEN: Short = 0x2003
    const val RESPONSE_INVALID_TRANSACTION: Short = 0x2004
    const val RESPONSE_OPERATION_NOT_SUPPORTED: Short = 0x2005
    const val RESPONSE_PARAMETER_NOT_SUPPORTED: Short = 0x2006
    const val RESPONSE_INVALID_OBJECT_HANDLE: Short = 0x2009
    const val RESPONSE_STORE_FULL: Short = 0x200C
    const val RESPONSE_DEVICE_BUSY: Short = 0x2019

    // 佳能厂商扩展响应码
    const val CANON_RESPONSE_UNKNOWN_COMMAND: Short = 0xA001.toShort()
    const val CANON_RESPONSE_OPERATION_REFUSED: Short = 0xA005.toShort()
    const val CANON_RESPONSE_LENS_COVER: Short = 0xA006.toShort()
    const val CANON_RESPONSE_BATTERY_LOW: Short = 0xA101.toShort()
    const val CANON_RESPONSE_NOT_READY: Short = 0xA102.toShort()
    const val CANON_EOS_RESPONSE_OBJECT_NOT_READY: Short = 0xA102.toShort()
    const val CANON_EOS_RESPONSE_CANNOT_MAKE_OBJECT: Short = 0xA104.toShort()
    const val CANON_EOS_RESPONSE_MEMORY_STATUS_NOT_READY: Short = 0xA106.toShort()

    // 索尼厂商扩展响应码
    const val SONY_RESPONSE_AUTHENTICATION_FAILED: Short = 0xA101.toShort()
    const val SONY_RESPONSE_PASSWORD_LENGTH_OVER_MAX: Short = 0xA102.toShort()
    const val SONY_RESPONSE_TEMPORARY_STORAGE_FULL: Short = 0xA105.toShort()
    const val SONY_RESPONSE_CAMERA_STATUS_ERROR: Short = 0xA106.toShort()

    // 松下厂商扩展响应码（复用标准 busy，品牌特定错误较少公开）

    // 奥林巴斯厂商扩展响应码（复用标准 busy）

    // 尼康厂商扩展响应码（与 libgphoto2 对齐）
    const val NIKON_RESPONSE_CHANGE_CAMERA_MODE_FAILED: Short = 0xA003.toShort()
    const val NIKON_RESPONSE_INVALID_STATUS: Short = 0xA004.toShort()
    const val NIKON_RESPONSE_NOT_LIVE_VIEW: Short = 0xA00B.toShort()
    const val NIKON_RESPONSE_BULB_RELEASE_BUSY: Short = 0xA200.toShort()

    // 设备属性
    const val DEVICE_PROP_F_NUMBER: Short = 0x5007
    const val DEVICE_PROP_EXPOSURE_TIME: Short = 0x500D
    const val DEVICE_PROP_EXPOSURE_PROGRAM_MODE: Short = 0x500E
    const val DEVICE_PROP_ISO: Short = 0x500F
    const val DEVICE_PROP_EXPOSURE_COMPENSATION: Short = 0x5010
    const val DEVICE_PROP_FOCUS_MODE: Short = 0x501A
    const val DEVICE_PROP_METERING_MODE: Short = 0x500B // PTP_DPC_ExposureMeteringMode, was wrong 0x501B
    const val DEVICE_PROP_FLASH_MODE: Short = 0x501C
    const val DEVICE_PROP_WHITE_BALANCE: Short = 0x5005
    const val DEVICE_PROP_BATTERY_LEVEL: Short = 0x5001

    // Canon vendor-specific device properties
    const val DEVICE_PROP_CANON_EOS_REMOTE_RELEASE: Short = 0xD2B5.toShort()
    const val DEVICE_PROP_CANON_EOS_EVF_MODE: Short = 0xD1F8.toShort()
    const val DEVICE_PROP_CANON_EOS_EVF_OUTPUT_DEVICE: Short = 0xD1B5.toShort()
    const val DEVICE_PROP_CANON_EOS_FOCUS_INFO: Short = 0xD1D9.toShort()

    // Sony vendor-specific device properties
    const val DEVICE_PROP_SONY_RecordingState: Short = 0xD213.toShort()
    const val DEVICE_PROP_SONY_FocusFound: Short = 0xD20E.toShort()

    // Fuji vendor-specific device properties
    const val DEVICE_PROP_FUJI_LiveViewImageSize: Short = 0xD06D.toShort()
    const val DEVICE_PROP_FUJI_LiveViewImageQuality: Short = 0xD173.toShort()
    const val DEVICE_PROP_FUJI_ExposurePreview: Short = 0xD226.toShort()

    // Panasonic vendor-specific device properties
    const val DEVICE_PROP_PANASONIC_LiveView: Short = 0xD100.toShort()

    // Olympus vendor-specific device properties
    const val DEVICE_PROP_OLYMPUS_LiveViewModeOM: Short = 0xD052.toShort()

    // Nikon vendor-specific device properties
    const val DEVICE_PROP_NIKON_EXPOSURE_TIME: Short = 0xD100.toShort() // Shutter Speed (Nikon-specific)
    const val DEVICE_PROP_NIKON_EXPOSURE_DELAY: Short = 0xD06A.toShort() // D5200 etc.
    const val DEVICE_PROP_NIKON_LIVE_VIEW_STATUS: Short = 0xD1A2.toShort()
    const val DEVICE_PROP_NIKON_LIVE_VIEW_PROHIBIT_CONDITION: Short = 0xD1A4.toShort()
    const val DEVICE_PROP_NIKON_RECORDING_MEDIA: Short = 0xD10B.toShort()
    const val DEVICE_PROP_NIKON_AF_MODE: Short = 0xD161.toShort()
    const val DEVICE_PROP_NIKON_AF_MODE_AT_LIVE_VIEW: Short = 0xD061.toShort()
    const val DEVICE_PROP_NIKON_AF_AREA_MODE: Short = 0xD163.toShort()

    // 对象格式
    const val OBJECT_FORMAT_ASSOCIATION: Short = 0x3001
    const val OBJECT_FORMAT_JPEG: Short = 0x3801
    const val OBJECT_FORMAT_TIFF: Short = 0x3802
    const val OBJECT_FORMAT_RAW: Short = 0x3806
    const val OBJECT_FORMAT_MPEG: Short = 0x380B

    // 默认端点
    const val DEFAULT_TIMEOUT_MS = 5000
    const val DEFAULT_SESSION_ID = 1
}
