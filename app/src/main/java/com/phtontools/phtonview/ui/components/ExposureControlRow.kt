package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.model.ExposureSettings
import com.phtontools.phtonview.data.model.MeteringMode
import com.phtontools.phtonview.data.model.MeteringResult

@Composable
fun ExposureControlRow(
    exposure: ExposureSettings,
    metering: MeteringResult,
    compact: Boolean,
    veryCompact: Boolean = false,
    onExposureChange: (String?, String?, Int?, Float?) -> Unit,
    onMeteringModeChange: (MeteringMode) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = String.format(stringResource(id = R.string.ev_comp_format), exposure.ev),
                color = MaterialTheme.colorScheme.onSurface,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
            )
            if (!veryCompact) {
                Text(
                    text = stringResource(id = R.string.metering_mode),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
                )
            }
        }

        Slider(
            value = exposure.ev,
            onValueChange = { onExposureChange(null, null, null, it) },
            valueRange = -3f..3f,
            steps = 11,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )

        MeteringSelector(
            currentMode = metering.mode,
            compact = compact,
            veryCompact = veryCompact,
            onModeSelected = onMeteringModeChange
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeteringSelector(
    currentMode: MeteringMode,
    compact: Boolean,
    veryCompact: Boolean = false,
    onModeSelected: (MeteringMode) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MeteringButton(
            mode = MeteringMode.Matrix,
            currentMode = currentMode,
            label = stringResource(id = R.string.metering_matrix),
            compact = compact,
            veryCompact = veryCompact,
            onClick = onModeSelected
        )
        MeteringButton(
            mode = MeteringMode.CenterWeighted,
            currentMode = currentMode,
            label = stringResource(id = R.string.metering_center),
            compact = compact,
            veryCompact = veryCompact,
            onClick = onModeSelected
        )
        MeteringButton(
            mode = MeteringMode.Spot,
            currentMode = currentMode,
            label = stringResource(id = R.string.metering_spot),
            compact = compact,
            veryCompact = veryCompact,
            onClick = onModeSelected
        )
    }
}

@Composable
private fun MeteringButton(
    mode: MeteringMode,
    currentMode: MeteringMode,
    label: String,
    compact: Boolean,
    veryCompact: Boolean = false,
    onClick: (MeteringMode) -> Unit
) {
    val padding = when {
        veryCompact -> androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        compact -> androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        else -> ButtonDefaults.ContentPadding
    }
    val selected = mode == currentMode
    Button(
        onClick = { onClick(mode) },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = padding
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
        )
    }
}
