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
 * 取景帧渲染（迭代 #17）。
 *
 * 旧实现：`Image(bitmap = processedFrame.asImageBitmap())` — 30fps × Image 重组
 * 走 Compose 重组 + ImageBitmap 分配 + 纹理上传，每帧 CPU 重活。
 *
 * 新实现：TextureView + 直接 drawBitmap。TextureView 自带 SurfaceTexture，
 * 由独立合成器渲染（SurfaceFlinger），不抢主线程。
 *
 * 用法：
 * ```
 * LiveViewTextureView(bitmap = processedFrame, modifier = ...)
 * ```
 *
 * 注意：
 * - 画 Bitmap 在 IO 线程做，不阻塞主线程
 * - TextureView.isAvailable 检测 SurfaceTexture 状态，**就绪后才画**否则 Surface 已销毁
 * - TextureView 本身大小由外层 Modifier 控制，drawBitmap 用 fitCenter 模式（保持比例）
 */
@Composable
fun LiveViewTextureView(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val textureViewState = remember { mutableStateOf<TextureView?>(null) }
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
                    pendingBitmap[0]?.let { drawToTextureView(tv, it) }
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    pendingBitmap[0]?.let { drawToTextureView(tv, it) }
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
                drawToTextureView(view, bitmap)
            }
        }
    }
}

/**
 * 在 IO 线程把 Bitmap 画到 TextureView 的 Canvas。
 *
 * TextureView 提供的 canvas 实际是 lockCanvas() 返回的 canvas，可以直接 drawBitmap。
 * 如果 lockCanvas 失败（SurfaceTexture 已销毁）就跳过。
 */
private fun drawToTextureView(textureView: TextureView, bitmap: Bitmap) {
    val canvas: Canvas? = try {
        textureView.lockCanvas()
    } catch (e: Exception) {
        null
    }
    if (canvas == null) return
    try {
        // 填黑底（避免透明）
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
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
        canvas.drawBitmap(bitmap, null, dest, paint)
    } finally {
        try {
            textureView.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            // SurfaceTexture 已销毁，无须处理
        }
    }
}
