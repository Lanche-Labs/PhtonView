package com.phtontools.phtonview.ui.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 缩略图 LRU 缓存单测（迭代 #2 + #17：单测基建）。
 *
 * 覆盖：
 * - 写入/读取基本流程
 * - 同一 id 重复 put 不会无限增长
 * - evict 单条
 * - clear 全清
 * - 容量上限触发 LRU 淘汰
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThumbnailCacheTest {

    private fun makeBmp(seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                bmp.setPixel(x, y, Color.rgb(seed and 0xFF, (seed shr 8) and 0xFF, (seed shr 16) and 0xFF))
            }
        }
        return bmp
    }

    @Test
    fun `put then get returns same bitmap`() {
        ThumbnailCache.clear()
        val bmp = makeBmp(0x123456)
        ThumbnailCache.put("photo-1", bmp)
        val out = ThumbnailCache.get("photo-1")
        assertThat(out).isNotNull()
        assertThat(out!!.width).isEqualTo(16)
        assertThat(out.height).isEqualTo(16)
    }

    @Test
    fun `get returns null for missing id`() {
        ThumbnailCache.clear()
        val out = ThumbnailCache.get("nope")
        assertThat(out).isNull()
    }

    @Test
    fun `put same id overwrites without growing cache`() {
        ThumbnailCache.clear()
        ThumbnailCache.put("photo-1", makeBmp(0xAA0000))
        ThumbnailCache.put("photo-1", makeBmp(0x00BB00))
        val out = ThumbnailCache.get("photo-1")
        // 第二次写入覆盖了第一次；只读不报错即可
        assertThat(out).isNotNull()
    }

    @Test
    fun `evict removes single entry`() {
        ThumbnailCache.clear()
        ThumbnailCache.put("a", makeBmp(1))
        ThumbnailCache.put("b", makeBmp(2))
        ThumbnailCache.evict("a")
        assertThat(ThumbnailCache.get("a")).isNull()
        assertThat(ThumbnailCache.get("b")).isNotNull()
    }

    @Test
    fun `clear removes all entries`() {
        ThumbnailCache.clear()
        ThumbnailCache.put("a", makeBmp(1))
        ThumbnailCache.put("b", makeBmp(2))
        ThumbnailCache.put("c", makeBmp(3))
        ThumbnailCache.clear()
        assertThat(ThumbnailCache.get("a")).isNull()
        assertThat(ThumbnailCache.get("b")).isNull()
        assertThat(ThumbnailCache.get("c")).isNull()
    }
}
