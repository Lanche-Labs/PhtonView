package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.phtontools.phtonview.data.model.HistogramData
import kotlin.math.max

/**
 * RGB 直方图绘制组件。
 */
@Composable
fun HistogramView(
    histogram: HistogramData,
    modifier: Modifier = Modifier,
    drawRed: Boolean = true,
    drawGreen: Boolean = true,
    drawBlue: Boolean = true
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val step = width / 256f

        val maxValue = max(
            1,
            histogram.red.maxOrNull() ?: 1,
            histogram.green.maxOrNull() ?: 1,
            histogram.blue.maxOrNull() ?: 1
        )

        if (drawRed) {
            drawHistogramChannel(histogram.red, Color.Red, step, height, maxValue)
        }
        if (drawGreen) {
            drawHistogramChannel(histogram.green, Color.Green, step, height, maxValue)
        }
        if (drawBlue) {
            drawHistogramChannel(histogram.blue, Color.Blue, step, height, maxValue)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogramChannel(
    values: IntArray,
    color: Color,
    step: Float,
    height: Float,
    maxValue: Int
) {
    for (i in values.indices) {
        val h = (values[i].toFloat() / maxValue) * height
        drawRect(
            color = color,
            topLeft = Offset(i * step, height - h),
            size = Size(step.coerceAtLeast(1f), h)
        )
    }
}

private fun max(a: Int, b: Int, c: Int, d: Int): Int = kotlin.math.max(kotlin.math.max(a, b), kotlin.math.max(c, d))
