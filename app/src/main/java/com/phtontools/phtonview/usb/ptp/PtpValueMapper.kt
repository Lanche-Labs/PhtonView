package com.phtontools.phtonview.usb.ptp

import com.phtontools.phtonview.data.model.*

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

    fun shutterToPtp(shutter: String): Int {
        // PTP ExposureTime is commonly stored as the denominator of a fraction with numerator 10000.
        val text = shutter.trim()
        return when {
            text.contains('s', ignoreCase = true) -> {
                val seconds = text.trimEnd('s', 'S').toFloatOrNull() ?: 1f
                if (seconds >= 1f) (10000f / seconds).toInt() else (10000f * (1f / seconds)).toInt()
            }
            text.contains('/') -> {
                val parts = text.split('/')
                val num = parts.getOrNull(0)?.toFloatOrNull() ?: 1f
                val den = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
                if (den != 0f) (10000f * num / den).toInt() else 10000
            }
            else -> {
                val seconds = text.toFloatOrNull() ?: 1f
                (10000f / seconds).toInt()
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
        FlashMode.Auto -> 1
        FlashMode.On -> 2
        FlashMode.Off -> 3
        FlashMode.RedEye -> 4
        FlashMode.SlowSync -> 5
        FlashMode.RearSync -> 6
    }

    fun shootingModeToPtp(mode: ShootingMode): Int = when (mode) {
        ShootingMode.M -> 1
        ShootingMode.P -> 2
        ShootingMode.A -> 3
        ShootingMode.S -> 4
        ShootingMode.Auto -> 5
        ShootingMode.Scene -> 6
    }
}
