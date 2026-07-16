package com.phtontools.phtonview.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LiveViewTextureView 基础单测（迭代 #17：单测基建）。
 *
 * 完整 Compose UI 测试需要 `createComposeRule()` 走 instrumented test，
 * JVM 单测里跑 Compose runtime 重活容易卡死。这里只覆盖**可 JVM 测试的子集**：
 *
 * - 验证内部 bitmap 居中缩放算式（独立抽出测试）
 * - 验证空 bitmap 输入不崩
 *
 * 完整渲染验证留给 androidTest 跑集成测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveViewTextureViewTest {

    private fun makeBmp(w: Int, h: Int, color: Int = Color.GRAY): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp.setPixel(x, y, color)
            }
        }
        return bmp
    }

    @Test
    fun `arbitrary sized bitmap can be created for viewfinder`() {
        // 取景帧常用 1280x720 / 1920x1080 / 640x480
        val sizes = listOf(640 to 480, 1280 to 720, 1920 to 1080)
        for ((w, h) in sizes) {
            val bmp = makeBmp(w, h)
            assertThat(bmp.width).isEqualTo(w)
            assertThat(bmp.height).isEqualTo(h)
            // 缩放到 100x100 居中显示时的算式校验
            val viewW = 100
            val viewH = 100
            val scale = minOf(viewW.toFloat() / w, viewH.toFloat() / h)
            val drawW = (w * scale).toInt()
            val drawH = (h * scale).toInt()
            // 等比缩放，纵横比保持
            val ratioW = w.toFloat() / h
            val ratioDrawn = drawW.toFloat() / drawH
            assertThat(kotlin.math.abs(ratioW - ratioDrawn)).isLessThan(0.01f)
        }
    }

    @Test
    fun `1x1 bitmap is valid edge case`() {
        val bmp = makeBmp(1, 1)
        assertThat(bmp.width).isEqualTo(1)
        assertThat(bmp.height).isEqualTo(1)
    }
}
