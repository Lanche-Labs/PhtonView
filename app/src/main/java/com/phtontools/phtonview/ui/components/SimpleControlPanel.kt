package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.model.ExposureSettings
import com.phtontools.phtonview.data.model.FocusMode

/**
 * 简洁模式底部控制面板，只显示最常用功能。
 */
@Composable
fun SimpleControlPanel(
    focusMode: FocusMode,
    exposure: ExposureSettings,
    isLandscape: Boolean = false,
    modifier: Modifier = Modifier,
    onFocusModeChange: (FocusMode) -> Unit,
    onIsoChange: (Int) -> Unit,
    onApertureChange: (String) -> Unit,
    onShutterChange: (String) -> Unit,
    onEvChange: (Float) -> Unit,
    onCapture: () -> Unit
) {
    BoxWithConstraints {
        val width = maxWidth
        val height = maxHeight
        val compact = width < 340.dp || height < 560.dp
        val padding = if (compact) 6.dp else 10.dp
        val spacerHeight = if (compact) 4.dp else 8.dp
        val buttonSize = if (compact) 52.dp else 64.dp
        val panelWidth = if (isLandscape) 220.dp else width
        val maxPanelHeight = if (isLandscape) height else (height * 0.35f).coerceAtMost(240.dp)

        Column(
            modifier = modifier
                .then(
                    if (isLandscape) {
                        Modifier
                            .width(panelWidth)
                            .fillMaxHeight()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxPanelHeight)
                    }
                )
                .verticalScroll(rememberScrollState())
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    if (isLandscape) {
                        RoundedCornerShape(
                            topStart = 20.dp,
                            bottomStart = 20.dp,
                            topEnd = 0.dp,
                            bottomEnd = 0.dp
                        )
                    } else {
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = 0.dp,
                            bottomEnd = 0.dp
                        )
                    }
                )
                .padding(padding)
        ) {
            // Focus / ISO / Aperture / Shutter quick row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactValueButton(
                    label = "ISO",
                    value = exposure.iso.toString(),
                    compact = compact,
                    onClick = { onIsoChange(cycleIso(exposure.iso)) }
                )
                CompactValueButton(
                    label = "F",
                    value = exposure.aperture,
                    compact = compact,
                    onClick = { onApertureChange(cycleAperture(exposure.aperture)) }
                )
                CompactValueButton(
                    label = "S",
                    value = exposure.shutter,
                    compact = compact,
                    onClick = { onShutterChange(cycleShutter(exposure.shutter)) }
                )
            }

            Spacer(modifier = Modifier.height(spacerHeight))

            CompactToggleButton(
                label = stringResource(
                    id = if (focusMode == FocusMode.AF) R.string.focus_mode_af else R.string.focus_mode_mf
                ),
                compact = compact,
                onClick = { onFocusModeChange(if (focusMode == FocusMode.AF) FocusMode.MF else FocusMode.AF) }
            )

            Spacer(modifier = Modifier.height(spacerHeight))

            // EV quick slider
            Text(
                text = String.format(stringResource(id = R.string.ev_comp_format), exposure.ev),
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            androidx.compose.material3.Slider(
                value = exposure.ev,
                onValueChange = onEvChange,
                valueRange = -3f..3f,
                steps = 11
            )

            Spacer(modifier = Modifier.height(spacerHeight))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FilledIconButton(
                    onClick = onCapture,
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(CircleShape),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = stringResource(id = R.string.capture),
                        modifier = Modifier.size(if (compact) 24.dp else 30.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactValueButton(
    label: String,
    value: String,
    compact: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        contentPadding = if (compact) {
            androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        } else androidx.compose.material3.ButtonDefaults.ContentPadding
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
            )
            Text(
                text = value,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun cycleIso(current: Int): Int {
    val values = listOf(100, 200, 400, 800, 1600, 3200, 6400)
    val index = values.indexOfFirst { it >= current }
    return if (index in 0 until values.lastIndex) values[index + 1] else values.first()
}

private fun cycleAperture(current: String): String {
    val values = listOf("f/1.8", "f/2.8", "f/4", "f/5.6", "f/8", "f/11", "f/16")
    val index = values.indexOf(current)
    return if (index in 0 until values.lastIndex) values[index + 1] else values.first()
}

private fun cycleShutter(current: String): String {
    val values = listOf("1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1s", "2s", "4s", "8s", "15s", "30s")
    val index = values.indexOf(current)
    return if (index in 0 until values.lastIndex) values[index + 1] else values.first()
}
