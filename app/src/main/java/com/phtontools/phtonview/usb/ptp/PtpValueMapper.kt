package com.phtontools.phtonview.usb.ptp

import com.phtontools.phtonview.data.model.*
import kotlin.math.roundToInt

/**
 * Maps high-level UI values to standard PTP / MTP device property values.
 * Vendor-specific deviations are normalised to the standard PTP codes.
 */
object PtpValueMapper {

    fun isoToPtp(iso: Int): Int = iso.coerceIn(50, 204800)

    fun apertureToPtp(aperture: String): Int {
        val value = aperture.trimStart('f', '/', ' ').toFloatOrNull() ?: 5.6f
        return (value * 100).toInt()
    }

    fun ptpToAperture(ptpValue: Int): String {
        val value = ptpValue / 100f
        return if (value == value.toInt().toFloat()) "f/${value.toInt()}" else "f/$value"
    }

    /**
     * ExposureTime encoding.
     * For Nikon 0xD100 uses (x<<16)|y; for Nikon 0x500D uses 1/10000-second units
     * with Bulb/Time special values; otherwise standard PTP 1/10000-second units.
     */
    fun shutterToPtp(shutter: String, brand: CameraBrand, propertyCode: Short = PtpConstants.DEVICE_PROP_EXPOSURE_TIME): Int {
        return if (brand == CameraBrand.Nikon && propertyCode == PtpConstants.DEVICE_PROP_NIKON_EXPOSURE_TIME) {
            nikonShutterToPtp(shutter)
        } else {
            // ponytail: Nikon 0x500D 与标准 PTP ExposureTime 编码规则相同。
            shutterToPtp(shutter)
        }
    }

    /**
     * ExposureTime decoding.
     */
    fun ptpToShutter(ptpValue: Int, brand: CameraBrand, propertyCode: Short = PtpConstants.DEVICE_PROP_EXPOSURE_TIME): String {
        return if (brand == CameraBrand.Nikon && propertyCode == PtpConstants.DEVICE_PROP_NIKON_EXPOSURE_TIME) {
            nikonPtpToShutter(ptpValue)
        } else {
            // ponytail: Nikon 0x500D 与标准 PTP ExposureTime 解码规则相同。
            ptpToShutter(ptpValue)
        }
    }

    /**
     * Standard PTP ExposureTime (0x500D) encoding.
     * UINT32 value represents exposure time in 1/10000 second units.
     * Bulb is represented by 0xFFFFFFFF.
     */
    fun shutterToPtp(shutter: String): Int {
        val text = shutter.trim()
        return when {
            text.equals("bulb", ignoreCase = true) -> -1 // encodes to 0xFFFFFFFF
            text.contains('s', ignoreCase = true) -> {
                val seconds = text.trimEnd('s', 'S').toFloatOrNull() ?: 1f
                (seconds * 10000f).toInt()
            }
            text.contains('/') -> {
                val parts = text.split('/')
                val num = parts.getOrNull(0)?.toFloatOrNull() ?: 1f
                val den = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
                if (den != 0f) ((num / den) * 10000f).toInt() else 10000
            }
            else -> {
                val seconds = text.toFloatOrNull() ?: 1f
                (seconds * 10000f).toInt()
            }
        }
    }

    /**
     * Standard PTP ExposureTime decoding.
     */
    fun ptpToShutter(ptpValue: Int): String {
        if (ptpValue == -1 || ptpValue == 0xFFFFFFFF.toInt()) return "Bulb"
        if (ptpValue <= 0) return "1s"
        val seconds = ptpValue / 10000f
        return when {
            seconds >= 1f -> String.format("%ds", seconds.toInt())
            else -> {
                val den = (10000f / ptpValue).roundToInt()
                when {
                    den <= 1 -> "1s"
                    den >= 8000 -> "1/8000"
                    else -> "1/$den"
                }
            }
        }
    }

    /**
     * Nikon-specific ExposureTime (0xD100) encoding.
     * Nikon stores the value as (numerator << 16) | denominator.
     *   "1/2"  -> (1 << 16) | 2  = 0x00010002
     *   "1" / "1s" -> (1 << 16) | 1  = 0x00010001
     *   "2" / "2s" -> (2 << 16) | 1  = 0x00020001
     *   "Bulb" -> 0xFFFFFFFF
     *   "Time" -> 0xFFFFFFFD
     */
    fun nikonShutterToPtp(shutter: String): Int {
        val text = shutter.trim()
        return when {
            text.equals("bulb", ignoreCase = true) -> 0xFFFFFFFF.toInt()
            text.equals("time", ignoreCase = true) -> 0xFFFFFFFD.toInt()
            text.contains('/') -> {
                val parts = text.split('/')
                val x = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val y = parts.getOrNull(1)?.toIntOrNull() ?: 1
                (x shl 16) or y
            }
            else -> {
                // Accept both "2s" (UI) and "2" (libgphoto2 style)
                val seconds = text.trimEnd('s', 'S').toFloatOrNull() ?: 1f
                val x = seconds.toInt()
                (x shl 16) or 1
            }
        }
    }

    /**
     * Nikon-specific ExposureTime decoding.
     */
    fun nikonPtpToShutter(ptpValue: Int): String {
        return when {
            ptpValue == 0xFFFFFFFF.toInt() -> "Bulb"
            ptpValue == 0xFFFFFFFD.toInt() -> "Time"
            ptpValue == 0xFFFFFFFE.toInt() -> "x 200"
            else -> {
                val x = ptpValue ushr 16
                val y = ptpValue and 0xFFFF
                when (y) {
                    1 -> "${x}s"
                    else -> "$x/$y"
                }
            }
        }
    }

    fun evToPtp(ev: Float): Int = (ev * 1000).toInt()

    fun focusModeToPtp(mode: FocusMode): Int = when (mode) {
        FocusMode.AF -> 1
        FocusMode.MF -> 32770 // Manual
    }

    fun afModeToPtp(mode: AfMode): Int = when (mode) {
        AfMode.AF_S -> 1
        AfMode.AF_C -> 2
    }

    /**
     * Nikon AF area mode values (0xD163).
     * Maps the UI modes to the vendor-specific codes used by Nikon bodies.
     * Other brands will use their own strategy/property if available.
     */
    fun afAreaModeToPtp(mode: AfAreaMode): Int = when (mode) {
        AfAreaMode.SinglePoint -> 1    // Single-point AF
        AfAreaMode.Zone -> 2           // Dynamic-area AF / Zone
        AfAreaMode.Tracking -> 3       // 3D-tracking
        AfAreaMode.FaceDetection -> 4  // Auto-area AF with face detection
    }

    fun meteringModeToPtp(mode: MeteringMode): Int = when (mode) {
        MeteringMode.Matrix -> 1
        MeteringMode.CenterWeighted -> 2
        MeteringMode.Spot -> 3
    }

    fun whiteBalanceToPtp(wb: WhiteBalance): Int = when (wb) {
        WhiteBalance.Auto -> 2
        WhiteBalance.Daylight -> 4
        WhiteBalance.Cloudy -> 5
        WhiteBalance.Shade -> 6
        WhiteBalance.Fluorescent -> 7
        WhiteBalance.Tungsten -> 3
        WhiteBalance.Flash -> 8
        WhiteBalance.Custom -> 32770
        WhiteBalance.Kelvin -> 32771
    }

    fun flashModeToPtp(mode: FlashMode): Int = when (mode) {
        FlashMode.Auto -> 1      // Auto flash
        FlashMode.Off -> 2       // Flash off
        FlashMode.On -> 3        // Fill flash (force on)
        FlashMode.RedEye -> 4    // Red-eye auto
        FlashMode.SlowSync -> 5  // Red-eye fill (closest standard value)
        FlashMode.RearSync -> 6  // External sync (closest standard value)
    }

    /**
     * Nikon 私有 FlashMode (0xD064) 编码，参考 digiCamControl 的 NikonPtpCodec。
     * 注意 Nikon 枚举值与标准 PTP 0x501C 不一致，需要单独编码。
     */
    fun nikonFlashModeToPtp(mode: FlashMode): Int = when (mode) {
        FlashMode.Off -> 0       // iTTL Off
        FlashMode.Auto -> 1      // iTTL Auto
        FlashMode.On -> 2        // iTTL On (fill)
        FlashMode.RedEye -> 3    // iTTL Auto Red-eye
        FlashMode.SlowSync -> 4  // Slow sync
        FlashMode.RearSync -> 5  // Rear sync
    }

    fun nikonPtpToFlashMode(raw: Int): FlashMode = when (raw) {
        0 -> FlashMode.Off
        1 -> FlashMode.Auto
        2 -> FlashMode.On
        3 -> FlashMode.RedEye
        4 -> FlashMode.SlowSync
        5 -> FlashMode.RearSync
        else -> FlashMode.Off
    }

    /**
     * Nikon 私有 FlashExposureCompensation (0xD065) 编码，INT16 1/8 EV。
     * 与 evToPtp 1/1000 EV 不同，必须单独处理避免换算错误。
     */
    fun nikonFlashCompToPtp(ev: Float): Int = (ev * 8f).toInt().coerceIn(-30, 30)

    fun nikonPtpToFlashComp(raw: Int): Float = raw / 8f

    fun shootingModeToPtp(mode: ShootingMode): Int = when (mode) {
        ShootingMode.M -> 1
        ShootingMode.P -> 2
        ShootingMode.A -> 3
        ShootingMode.S -> 4
        ShootingMode.Auto -> 5
        ShootingMode.Scene -> 6
    }
}
