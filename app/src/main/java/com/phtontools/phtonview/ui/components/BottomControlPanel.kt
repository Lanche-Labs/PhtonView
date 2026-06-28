package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.phtontools.phtonview.data.model.AfMode
import com.phtontools.phtonview.data.model.ExposureSettings
import com.phtontools.phtonview.data.model.FocusMode
import com.phtontools.phtonview.data.model.MeteringMode
import com.phtontools.phtonview.data.model.MeteringResult

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BottomControlPanel(
    focusMode: FocusMode,
    afMode: AfMode,
    magnification: Float,
    peakingEnabled: Boolean,
    exposure: ExposureSettings,
    metering: MeteringResult,
    isLandscape: Boolean = false,
    modifier: Modifier = Modifier,
    onFocusModeChange: (FocusMode) -> Unit,
    onAfModeChange: (AfMode) -> Unit,
    onMagnificationChange: (Float) -> Unit,
    onPeakingChange: (Boolean) -> Unit,
    onMeteringModeChange: (MeteringMode) -> Unit,
    onExposureChange: (String?, String?, Int?, Float?) -> Unit,
    onCapture: () -> Unit,
    onBurst: () -> Unit,
    onBulb: () -> Unit = {},
    onTimer: () -> Unit = {},
    onIntervalometer: () -> Unit = {},
    onAeb: () -> Unit = {}
) {
    BoxWithConstraints {
        val width = maxWidth
        val height = maxHeight
        val compact = width < 340.dp || height < 560.dp
        val veryCompact = width < 300.dp || height < 500.dp
        val padding = if (veryCompact) 4.dp else if (compact) 6.dp else 10.dp
        val spacerHeight = if (veryCompact) 2.dp else if (compact) 4.dp else 6.dp
        val buttonSize = if (veryCompact) 44.dp else if (compact) 52.dp else 64.dp
        val iconSize = if (veryCompact) 18.dp else if (compact) 22.dp else 28.dp
        val panelWidth = if (isLandscape) 250.dp else width
        val maxPanelHeight = if (isLandscape) height else (height * 0.45f).coerceAtMost(300.dp)

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
            FocusControlRow(
                focusMode = focusMode,
                afMode = afMode,
                magnification = magnification,
                peakingEnabled = peakingEnabled,
                compact = compact,
                veryCompact = veryCompact,
                onFocusModeChange = onFocusModeChange,
                onAfModeChange = onAfModeChange,
                onMagnificationChange = onMagnificationChange,
                onPeakingChange = onPeakingChange
            )

            Spacer(modifier = Modifier.height(spacerHeight))

            if (!veryCompact) {
                ExposureControlRow(
                    exposure = exposure,
                    metering = metering,
                    compact = compact,
                    veryCompact = veryCompact,
                    onExposureChange = onExposureChange,
                    onMeteringModeChange = onMeteringModeChange
                )

                Spacer(modifier = Modifier.height(spacerHeight))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactButton(
                    label = stringResource(id = R.string.burst),
                    icon = Icons.Default.BurstMode,
                    onClick = onBurst,
                    compact = compact,
                    veryCompact = veryCompact,
                    color = MaterialTheme.colorScheme.primary
                )

                FilledIconButton(
                    onClick = onCapture,
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(CircleShape),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = stringResource(id = R.string.capture),
                        modifier = Modifier.size(iconSize)
                    )
                }

                CompactButton(
                    label = stringResource(id = R.string.timer_2s),
                    icon = Icons.Default.Timer,
                    onClick = onTimer,
                    compact = compact,
                    veryCompact = veryCompact,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(spacerHeight))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CompactButton(
                    label = stringResource(id = R.string.bulb),
                    onClick = onBulb,
                    compact = compact,
                    veryCompact = veryCompact,
                    color = MaterialTheme.colorScheme.primary
                )
                CompactButton(
                    label = stringResource(id = R.string.interval),
                    onClick = onIntervalometer,
                    compact = compact,
                    veryCompact = veryCompact,
                    color = MaterialTheme.colorScheme.primary
                )
                CompactButton(
                    label = stringResource(id = R.string.aeb),
                    onClick = onAeb,
                    compact = compact,
                    veryCompact = veryCompact,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
internal fun CompactButton(
    label: String,
    onClick: () -> Unit,
    compact: Boolean,
    veryCompact: Boolean,
    color: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val padding = when {
        veryCompact -> androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        compact -> androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        else -> ButtonDefaults.ContentPadding
    }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = padding
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
