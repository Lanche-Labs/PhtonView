package com.phtontools.phtonview.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 取景帧渲染（迭代 #18 修复 #188）。
 *
 * 使用 TextureView + lockCanvas/drawBitmap。
 *
 * 为什么不用 SurfaceView：
 * SurfaceView 创建独立的 Surface 窗口，由 SurfaceFlinger 直接合成，
 * 其 z-order 不受 Compose View 层级控制。在 CameraScreen 中会导致
 * TopStatusBar / 菜单按钮等 Compose UI 元素被 SurfaceView 表面遮挡
 * 无法显示（issue #188）。TextureView 在 View 层级内渲染，与其他
 * Compose 元素正确叠放。
 *
 * 性能说明：
 * - TextureView 的 SurfaceTexture 走 GPU 纹理路径，延迟比 SurfaceView
 *   多约 1 帧（~16ms），但保证 UI 叠层正确
 * - Paint 对象预分配复用，避免每帧 new Paint() 的 GC 压力
 * - 渲染在 IO 线程执行，不阻塞主线程
 */
@Composable
fun LiveViewTextureView(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val textureViewState = remember { mutableStateOf<TextureView?>(null) }
    // 预分配 Paint 对象，避免每帧 new Paint() 的 GC 压力
    val paint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            isDither = true
        }
    }
    // 标记当前需要画的 bitmap（用于 surface texture 重新可用时重新画）
    val pendingBitmap = remember { arrayOf<Bitmap?>(null) }
    pendingBitmap[0] = bitmap

    AndroidView(
        factory = { ctx ->
            val tv = TextureView(ctx)
            tv.isOpaque = true
            textureViewState.value = tv
            // 当 SurfaceTexture 重新可用（旋转、回到前台），重画最后一帧
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    pendingBitmap[0]?.let { drawToTextureView(tv, it, paint) }
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    pendingBitmap[0]?.let { drawToTextureView(tv, it, paint) }
                }
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
            }
            tv
        },
        modifier = modifier
    )

    // 当 bitmap 变化时异步画到 TextureView
    LaunchedEffect(bitmap) {
        val view = textureViewState.value ?: return@LaunchedEffect
        if (bitmap != null && view.isAvailable) {
            withContext(Dispatchers.IO) {
                drawToTextureView(view, bitmap, paint)
            }
        }
    }
}

/**
 * 在 IO 线程把 Bitmap 画到 TextureView 的 Canvas。
 *
 * TextureView.lockCanvas() 返回的 Canvas 直接对应 SurfaceTexture 的
 * 图形缓冲区。drawBitmap 在 GPU 纹理上执行（TextureView 的 Canvas 是
 * 硬件加速的），配合 ALLOCATOR_SOFTWARE 的 Bitmap 走 CPU→GPU 上传路径。
 */
private fun drawToTextureView(textureView: TextureView, bitmap: Bitmap, paint: Paint) {
    val canvas: Canvas? = try {
        textureView.lockCanvas()
    } catch (e: Exception) {
        null
    }
    if (canvas == null) return
    try {
        // 填黑底（避免透明残留）
        canvas.drawColor(Color.BLACK)
        // 等比缩放居中
        val viewW = canvas.width
        val viewH = canvas.height
        val bmpW = bitmap.width
        val bmpH = bitmap.height
        if (bmpW <= 0 || bmpH <= 0) return
        val scale = minOf(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH)
        val drawW = (bmpW * scale).toInt()
        val drawH = (bmpH * scale).toInt()
        val left = (viewW - drawW) / 2
        val top = (viewH - drawH) / 2
        val dest = android.graphics.Rect(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bitmap, null, dest, paint)
    } finally {
        try {
            textureView.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            // SurfaceTexture 已销毁，无须处理
        }
    }
}
