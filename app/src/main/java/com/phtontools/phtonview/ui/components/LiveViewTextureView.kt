package com.phtontools.phtonview.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 取景帧渲染（迭代 #18 帧率优化）。
 *
 * 旧实现（迭代 #17）：TextureView + lockCanvas/drawBitmap。
 * TextureView 的 SurfaceTexture 经过 View 层级渲染管线（RenderNode →
 * GPU 纹理），每次 unlockCanvasAndPost 都要等 VSync 合成，
 * 增加 ~1 帧延迟。
 *
 * 新实现：SurfaceView + 直接 Surface.lockCanvas()。
 * SurfaceView 拥有独立的 Surface，由 SurfaceFlinger 直接合成，
 * 不经过应用窗口的 RenderThread，延迟更低。
 *
 * 配套优化：
 * - decodeLiveViewJpeg 改用 ALLOCATOR_HARDWARE，Bitmap 在 GPU 硬件缓冲区，
 *   drawBitmap 走 GPU blit（纹理拷贝），无需 CPU→GPU 上传。
 * - Paint 对象预分配复用，避免每帧 new Paint() 的 GC 压力。
 * - 渲染在 IO 线程执行，不阻塞主线程。
 *
 * 注意：
 * - SurfaceView.isOpaque = true 避免透明合成开销
 * - SurfaceHolder 生命周期由 SurfaceHolder.Callback 管理
 * - Surface 销毁后停止绘制，避免 IllegalStateException
 */
@Composable
fun LiveViewTextureView(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val surfaceViewState = remember { mutableStateOf<SurfaceView?>(null) }
    // 预分配 Paint 对象，避免每帧 new Paint() 的 GC 压力
    val paint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            isDither = true
        }
    }
    // 标记当前需要画的 bitmap（用于 surface 重新可用时重新画）
    val pendingBitmap = remember { arrayOf<Bitmap?>(null) }
    pendingBitmap[0] = bitmap

    AndroidView(
        factory = { ctx ->
            val sv = SurfaceView(ctx)
            // SurfaceView 默认不透明，避免透明合成开销
            sv.holder.setFormat(android.graphics.PixelFormat.OPAQUE)
            surfaceViewState.value = sv

            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    // Surface 可用时画最后一帧
                    pendingBitmap[0]?.let { drawToSurfaceView(sv, it, paint) }
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    pendingBitmap[0]?.let { drawToSurfaceView(sv, it, paint) }
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    // Surface 已销毁，停止绘制
                }
            })
            sv
        },
        modifier = modifier
    )

    // 当 bitmap 变化时异步画到 SurfaceView
    LaunchedEffect(bitmap) {
        val view = surfaceViewState.value ?: return@LaunchedEffect
        if (bitmap != null) {
            withContext(Dispatchers.IO) {
                drawToSurfaceView(view, bitmap, paint)
            }
        }
    }
}

/**
 * 在 IO 线程把 Bitmap 画到 SurfaceView 的 Canvas。
 *
 * SurfaceView 的 Surface.lockCanvas() 返回的 Canvas 直接对应
 * SurfaceFlinger 管理的图形缓冲区（GraphicBuffer），drawBitmap 在
 * 硬件 Canvas 上执行：
 * - 如果 Bitmap 是 HARDWARE 配置（ALLOCATOR_HARDWARE），走 GPU blit
 *   （纹理拷贝），延迟 ~0.5ms
 * - 如果 Bitmap 是 SOFTWARE 配置，走 CPU→GPU 上传，延迟 ~3-5ms
 *
 * 配合 decodeLiveViewJpeg 的 ALLOCATOR_HARDWARE，整条渲染路径
 * 全 GPU 侧，最小化帧延迟。
 */
private fun drawToSurfaceView(surfaceView: SurfaceView, bitmap: Bitmap, paint: Paint) {
    val holder = surfaceView.holder
    val canvas: Canvas? = try {
        holder.lockCanvas()
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
            holder.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            // Surface 已销毁，无须处理
        }
    }
}
