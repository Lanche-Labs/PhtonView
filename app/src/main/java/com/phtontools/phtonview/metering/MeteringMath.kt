package com.phtontools.phtonview.metering

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 专业测光数学工具。
 *
 * **核心思路**：
 * 手机摄像头有 AE 系统，会自动调整快门/ISO 使画面亮度接近 18% 灰（Y≈118）。
 * 因此不能直接用 Y 值计算 EV，必须依赖手机的实际曝光参数。
 *
 * **EV 计算公式**：
 * EV = log2(N²/t) + log2(ISO/100)
 *
 * 其中 N=光圈，t=快门秒数，ISO=感光度
 */
object MeteringMath {

    /** 标准光圈档位 */
    val APERTURE_STOPS: DoubleArray = doubleArrayOf(
        1.0, 1.4, 1.8, 2.0, 2.8, 3.5, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0, 32.0
    )

    /** 标准快门档位（秒） */
    val SHUTTER_STOPS_SECONDS: DoubleArray = doubleArrayOf(
        1.0 / 8000, 1.0 / 4000, 1.0 / 2000, 1.0 / 1000, 1.0 / 500, 1.0 / 250, 1.0 / 125, 1.0 / 60,
        1.0 / 30, 1.0 / 15, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0
    )

    /** 标准 ISO 档位 */
    val ISO_STOPS: IntArray = intArrayOf(
        50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200
    )

    /**
     * 从手机曝光参数计算场景 EV（at ISO 100）。
     *
     * 公式：EV_100 = log2(N²/t) - log2(ISO/100)
     *
     * @param aperture 手机光圈值
     * @param shutterSeconds 手机快门秒数
     * @param iso 手机 ISO
     * @param meanLumaY Y 通道亮度（用于微调，可选）
     * @return EV at ISO 100
     */
    fun computeEvFromPhoneParams(
        aperture: Double,
        shutterSeconds: Double,
        iso: Int,
        meanLumaY: Double = 118.0
    ): Double {
        if (aperture <= 0 || shutterSeconds <= 0 || iso <= 0) {
            return 7.0  // 默认室内值
        }
        
        // 基础 EV：当前曝光参数对应的 EV
        val phoneEv = log2((aperture * aperture) / shutterSeconds)
        
        // ISO 归一化到 100
        val evAtIso100 = phoneEv - log2(iso / 100.0)
        
        // Y 值微调：如果画面偏离 18% 灰，说明 AE 还没完全收敛
        // Y>118 表示过曝（场景比 AE 认为的更亮），Y<118 表示欠曝
        val yCorrection = if (meanLumaY > 0 && meanLumaY != 118.0) {
            log2(meanLumaY / 118.0) * 0.5  // 50% 权重
        } else {
            0.0
        }
        
        return (evAtIso100 + yCorrection).coerceIn(-3.0, 20.0)
    }

    /**
     * 固定光圈时，计算推荐快门和 ISO。
     *
     * 公式：t = N² / 2^EV_at_ISO
     *
     * @param ev ISO 100 下的 EV
     * @param aperture 固定光圈值
     * @param targetIso 目标 ISO（用户选择）
     * @return 推荐快门秒数
     */
    fun computeShutterForAperture(ev: Double, aperture: Double, targetIso: Int): Double {
        // EV at target ISO = EV_100 + log2(ISO/100)
        val evAtIso = ev + log2(targetIso / 100.0)
        // t = N² / 2^EV
        val shutter = (aperture * aperture) / 2.0.pow(evAtIso)
        return snapShutter(shutter)
    }

    /**
     * 固定快门时，计算推荐光圈和 ISO。
     *
     * 公式：N = sqrt(t × 2^EV_at_ISO)
     *
     * @param ev ISO 100 下的 EV
     * @param shutterSeconds 固定快门秒数
     * @param targetIso 目标 ISO（用户选择）
     * @return 推荐光圈值
     */
    fun computeApertureForShutter(ev: Double, shutterSeconds: Double, targetIso: Int): Double {
        val evAtIso = ev + log2(targetIso / 100.0)
        // N = sqrt(t × 2^EV)
        val aperture = sqrt(shutterSeconds * 2.0.pow(evAtIso))
        return snapAperture(aperture)
    }

    /**
     * 固定光圈和快门时，计算推荐 ISO。
     *
     * 公式：ISO = 100 × 2^(EV_camera - EV_scene)
     *
     * @param ev ISO 100 下的 EV
     * @param aperture 固定光圈值
     * @param shutterSeconds 固定快门秒数
     * @return 推荐 ISO
     */
    fun computeIsoForApertureShutter(ev: Double, aperture: Double, shutterSeconds: Double): Int {
        // 当前参数的 EV
        val cameraEv = log2((aperture * aperture) / shutterSeconds)
        // ISO = 100 × 2^(cameraEv - sceneEv)
        val iso = 100 * 2.0.pow(cameraEv - ev)
        return snapIso(iso.roundToInt().coerceIn(ISO_STOPS.first(), ISO_STOPS.last()))
    }

    /**
     * 把数字光圈 snap 到最接近的标准档位。
     */
    fun snapAperture(target: Double): Double {
        if (target <= 0) return APERTURE_STOPS[0]
        return APERTURE_STOPS.minByOrNull { abs(it - target) } ?: target
    }

    /**
     * 把数字快门（秒）snap 到最接近的标准档位。
     */
    fun snapShutter(target: Double): Double {
        if (target <= 0) return SHUTTER_STOPS_SECONDS[0]
        return SHUTTER_STOPS_SECONDS.minByOrNull { abs(log2(it / target)) } ?: target
    }

    /**
     * 把 ISO snap 到最接近的标准档位。
     */
    fun snapIso(target: Int): Int {
        if (target <= ISO_STOPS[0]) return ISO_STOPS[0]
        return ISO_STOPS.minByOrNull { abs(it - target) } ?: target
    }

    /**
     * 把快门秒数格式化为机身 UI 用的字符串。
     */
    fun formatShutter(seconds: Double): String {
        if (seconds >= 1.0) {
            return if (seconds == seconds.toInt().toDouble()) "${seconds.toInt()}s" else "%.1fs".format(seconds)
        }
        val denom = (1.0 / seconds).roundToInt()
        if (denom <= 0) return "Bulb"
        return "1/$denom"
    }

    /**
     * 格式化光圈值。
     */
    fun formatAperture(aperture: Double): String {
        return "f/${String.format("%.1f", aperture)}"
    }

    /**
     * 解析光圈字符串（如 "f/5.6"）为数字。
     */
    fun parseApertureToNumber(s: String?): Double {
        if (s.isNullOrEmpty()) return 0.0
        return s.removePrefix("f/").trim().toDoubleOrNull() ?: 0.0
    }

    /**
     * 解析快门字符串（如 "1/125" 或 "2s"）为秒数。
     */
    fun parseShutterToSeconds(s: String?): Double {
        if (s.isNullOrEmpty()) return 0.0
        return when {
            s == "Bulb" -> 30.0
            s.endsWith("s") -> s.removeSuffix("s").toDoubleOrNull() ?: 0.0
            s.startsWith("1/") -> {
                val denom = s.removePrefix("1/").toIntOrNull() ?: 1
                1.0 / denom
            }
            else -> s.toDoubleOrNull() ?: 0.0
        }
    }
}