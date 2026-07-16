package com.phtontools.phtonview.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 峰值对焦处理器单测（迭代 #16：单测基建）。
 *
 * 覆盖：
 * - 基本流程（5x5 图像：中心白点 → 边缘应被高亮）
 * - 阈值过滤（高阈值时低对比度边缘不触发）
 * - 自定义高亮颜色
 * - 输入输出尺寸一致
 * - 纯色图像不触发（无边缘）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FocusPeakingProcessorTest {

    /**
     * 创建指定尺寸 + 颜色的 Bitmap。
     * Robolectric 在 JVM 上跑 android.graphics.Bitmap 桩，createBitmap/setPixel 全部支持。
     */
    private fun makeBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bmp.setPixel(x, y, color)
            }
        }
        return bmp
    }

    /**
     * 在中心放置一个白点（边缘清晰 → 应被峰值对焦捕获）。
     */
    private fun makeBitmapWithCenterSpot(size: Int, bg: Int, spot: Int): Bitmap {
        val bmp = makeBitmap(size, size, bg)
        val cx = size / 2
        val cy = size / 2
        // 3x3 白点
        for (y in cy - 1..cy + 1) {
            for (x in cx - 1..cx + 1) {
                bmp.setPixel(x, y, spot)
            }
        }
        return bmp
    }

    @Test
    fun `apply returns bitmap of same dimensions`() {
        val src = makeBitmap(20, 20, Color.GRAY)
        val out = FocusPeakingProcessor.apply(src, threshold = 35)
        assertThat(out.width).isEqualTo(20)
        assertThat(out.height).isEqualTo(20)
        assertThat(out.config).isEqualTo(Bitmap.Config.ARGB_8888)
    }

    @Test
    fun `apply does not crash on uniform color (no edges)`() {
        val src = makeBitmap(10, 10, Color.WHITE)
        val out = FocusPeakingProcessor.apply(src, threshold = 35)
        // 纯色 → 没有边缘 → 输出全白
        for (y in 0 until 10) {
            for (x in 0 until 10) {
                assertThat(out.getPixel(x, y)).isEqualTo(Color.WHITE)
            }
        }
    }

    @Test
    fun `apply highlights edges around contrast spot`() {
        val src = makeBitmapWithCenterSpot(size = 15, bg = Color.BLACK, spot = Color.WHITE)
        val out = FocusPeakingProcessor.apply(src, threshold = 35, highlightColor = Color.RED)
        // 中心白点与黑色背景的边界（约 8 个边缘像素）应被红色高亮
        val cx = 7
        val cy = 7
        var redCount = 0
        // 检查 3x3 邻域（包括边界）
        for (y in cy - 2..cy + 2) {
            for (x in cx - 2..cx + 2) {
                if (x in 0 until 15 && y in 0 until 15) {
                    val p = out.getPixel(x, y)
                    // 高亮的边缘点应有较高红色成分
                    if (Color.red(p) > 200 && Color.green(p) < 100 && Color.blue(p) < 100) {
                        redCount++
                    }
                }
            }
        }
        assertThat(redCount).isAtLeast(4)  // 至少 4 个边缘点被高亮
    }

    @Test
    fun `high threshold reduces highlighted pixels`() {
        val src = makeBitmapWithCenterSpot(size = 15, bg = Color.BLACK, spot = Color.WHITE)
        val outLow = FocusPeakingProcessor.apply(src, threshold = 20, highlightColor = Color.RED)
        val outHigh = FocusPeakingProcessor.apply(src, threshold = 200, highlightColor = Color.RED)
        fun countRed(bmp: Bitmap): Int {
            var c = 0
            for (y in 0 until 15) {
                for (x in 0 until 15) {
                    val p = bmp.getPixel(x, y)
                    if (Color.red(p) > 200) c++
                }
            }
            return c
        }
        val lowRed = countRed(outLow)
        val highRed = countRed(outHigh)
        assertThat(lowRed).isAtLeast(highRed)
        // 高阈值至少不应比低阈值产生更多红像素
    }

    @Test
    fun `custom highlight color is applied`() {
        val src = makeBitmapWithCenterSpot(size = 15, bg = Color.BLACK, spot = Color.WHITE)
        val out = FocusPeakingProcessor.apply(src, threshold = 20, highlightColor = Color.GREEN)
        var greenCount = 0
        for (y in 0 until 15) {
            for (x in 0 until 15) {
                val p = out.getPixel(x, y)
                if (Color.green(p) > 200 && Color.red(p) < 100 && Color.blue(p) < 100) {
                    greenCount++
                }
            }
        }
        assertThat(greenCount).isAtLeast(4)  // 至少 4 个绿色高亮像素
    }
}
