package com.phtontools.phtonview.ui.util

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 缩略图 LRU 缓存（迭代 #2）。
 *
 * 旧实现：PhotoGallerySheet 每次打开图库都重新 `BitmapFactory.decodeFile`，
 * 100 张照片触发 100 次 JPEG 解码 + Bitmap 分配，体感"卡"。
 *
 * 新实现：第一次解码后写入 LruCache，第二次进入图库直接命中，秒开。
 *
 * - Key：`PhotoItem.id`（相机返回的 object handle，相机里唯一稳定）
 * - Value：缩略图 Bitmap
 * - 容量：maxMemory / 8，约 16MB-32MB（容纳 200+ 张 ~100KB 缩略图）
 *
 * 线程安全：LruCache 内部已同步；Bitmap 引用计数由 GC 处理。
 */
object ThumbnailCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 8)
        .toInt()
        .coerceIn(4 * 1024 * 1024, 64 * 1024 * 1024)
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(id: String): Bitmap? = cache.get(id)

    fun put(id: String, bitmap: Bitmap) {
        cache.put(id, bitmap)
    }

    fun evict(id: String) {
        cache.remove(id)
    }

    fun clear() {
        cache.evictAll()
    }

    /** 调试用：当前缓存条目数。 */
    fun size(): Int = cache.size()
}
