package com.phtontools.phtonview.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.get
import androidx.core.graphics.set
import kotlin.math.abs

/**
 * 峰值对焦处理器。
 * 通过简单的边缘检测算法，在合焦边缘叠加高亮颜色。
 */
object FocusPeakingProcessor {

    /**
     * 对输入 Bitmap 应用峰值对焦效果。
     * @param source 原始取景画面
     * @param threshold 边缘强度阈值，越大越严格
     * @param highlightColor 高亮颜色，默认红色
     */
    fun apply(
        source: Bitmap,
        threshold: Int = 35,
        highlightColor: Int = Color.RED
    ): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(IntArray(width * height) { source.getPixel(it % width, it / width) }, 0, width, 0, 0, width, height)

        val sobelX = arrayOf(intArrayOf(-1, 0, 1), intArrayOf(-2, 0, 2), intArrayOf(-1, 0, 1))
        val sobelY = arrayOf(intArrayOf(-1, -2, -1), intArrayOf(0, 0, 0), intArrayOf(1, 2, 1))

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var gx = 0
                var gy = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val gray = grayscale(source[x + kx, y + ky])
                        gx += gray * sobelX[ky + 1][kx + 1]
                        gy += gray * sobelY[ky + 1][kx + 1]
                    }
                }
                val magnitude = abs(gx) + abs(gy)
                if (magnitude > threshold) {
                    output[x, y] = blend(output[x, y], highlightColor, 0.7f)
                }
            }
        }
        return output
    }

    private fun grayscale(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    private fun blend(base: Int, overlay: Int, ratio: Float): Int {
        val inv = 1 - ratio
        val r = (Color.red(base) * inv + Color.red(overlay) * ratio).toInt()
        val g = (Color.green(base) * inv + Color.green(overlay) * ratio).toInt()
        val b = (Color.blue(base) * inv + Color.blue(overlay) * ratio).toInt()
        val a = Color.alpha(base)
        return Color.argb(a, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}
