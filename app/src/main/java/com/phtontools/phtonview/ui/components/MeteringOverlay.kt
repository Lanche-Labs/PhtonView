package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import com.phtontools.phtonview.data.model.MeteringMode
import com.phtontools.phtonview.data.model.MeteringResult

@Composable
fun MeteringOverlay(metering: MeteringResult) {
    val accentColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val point = metering.spotPoint
        if (metering.mode == MeteringMode.Spot && point != null) {
            val center = Offset(point.first * size.width, point.second * size.height)
            drawCircle(
                color = accentColor,
                radius = 24f,
                center = center,
                style = Stroke(width = 2f)
            )
            drawCircle(
                color = accentColor,
                radius = 4f,
                center = center
            )
        } else if (metering.mode == MeteringMode.CenterWeighted) {
            val cx = size.width / 2
            val cy = size.height / 2
            drawCircle(
                color = accentColor,
                radius = size.minDimension * 0.15f,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
        }
    }
}
