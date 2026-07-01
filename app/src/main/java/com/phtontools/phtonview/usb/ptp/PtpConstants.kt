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
    const val OPERATION_DEVICE_PROP_DESC: Short = 0x1014
    const val OPERATION_DEVICE_PROP_VALUE_GET: Short = 0x1015
    const val OPERATION_DEVICE_PROP_VALUE_SET: Short = 0x1016

    // 尼康厂商扩展操作码
    const val NIKON_OPERATION_AF_DRIVE: Short = 0x90C1.toShort()
    const val NIKON_OPERATION_MFDRIVE: Short = 0x9204.toShort()
    const val NIKON_OPERATION_START_LIVEVIEW: Short = 0x9201.toShort()
    const val NIKON_OPERATION_STOP_LIVEVIEW: Short = 0x9202.toShort()
    const val NIKON_OPERATION_GET_LIVEVIEW_IMAGE: Short = 0x9203.toShort()
    const val NIKON_OPERATION_CHANGE_AF_AREA: Short = 0x9205.toShort()
    const val NIKON_OPERATION_AFDRIVE_D850: Short = 0x90C1.toShort()
    const val NIKON_OPERATION_CHANGE_CAMERA_MODE: Short = 0x90C2.toShort()
    const val NIKON_OPERATION_DEVICE_READY: Short = 0x90C8.toShort()
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

    // Nikon vendor-specific device properties
    const val DEVICE_PROP_NIKON_EXPOSURE_TIME: Short = 0xD100.toShort() // Shutter Speed (Nikon-specific)
    const val DEVICE_PROP_NIKON_LIVE_VIEW_STATUS: Short = 0xD1A2.toShort()
    const val DEVICE_PROP_NIKON_LIVE_VIEW_PROHIBIT_CONDITION: Short = 0xD1A4.toShort()
    const val DEVICE_PROP_NIKON_RECORDING_MEDIA: Short = 0xD10B.toShort()

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
