package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.model.AfAreaMode
import com.phtontools.phtonview.data.model.AfMode
import com.phtontools.phtonview.data.model.FocusMode

@Composable
fun FocusControlRow(
    focusMode: FocusMode,
    afMode: AfMode,
    afAreaMode: AfAreaMode,
    magnification: Float,
    peakingEnabled: Boolean,
    compact: Boolean,
    veryCompact: Boolean = false,
    onFocusModeChange: (FocusMode) -> Unit,
    onAfModeChange: (AfMode) -> Unit,
    onAfAreaModeChange: (AfAreaMode) -> Unit,
    onMagnificationChange: (Float) -> Unit,
    onPeakingChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactToggleButton(
            label = stringResource(
                id = if (focusMode == FocusMode.AF) R.string.focus_mode_af else R.string.focus_mode_mf
            ),
            compact = compact,
            veryCompact = veryCompact,
            onClick = { onFocusModeChange(if (focusMode == FocusMode.AF) FocusMode.MF else FocusMode.AF) }
        )

        if (focusMode == FocusMode.AF) {
            CompactToggleButton(
                label = stringResource(
                    id = if (afMode == AfMode.AF_S) R.string.af_single else R.string.af_continuous
                ),
                compact = compact,
                veryCompact = veryCompact,
                onClick = {
                    onAfModeChange(if (afMode == AfMode.AF_S) AfMode.AF_C else AfMode.AF_S)
                }
            )

            val nextAreaMode = when (afAreaMode) {
                AfAreaMode.SinglePoint -> AfAreaMode.Zone
                AfAreaMode.Zone -> AfAreaMode.Tracking
                AfAreaMode.Tracking -> AfAreaMode.FaceDetection
                AfAreaMode.FaceDetection -> AfAreaMode.SinglePoint
            }
            CompactToggleButton(
                label = stringResource(id = afAreaModeStringRes(afAreaMode)),
                compact = compact,
                veryCompact = veryCompact,
                onClick = { onAfAreaModeChange(nextAreaMode) }
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!veryCompact) {
                    CompactIconButton(
                        icon = Icons.Default.ZoomIn,
                        onClick = {
                            val values = listOf(1f, 2f, 4f, 8f)
                            val next = values.firstOrNull { it > magnification } ?: 1f
                            onMagnificationChange(next)
                        },
                        compact = compact
                    )
                }

                CompactIconButton(
                    icon = Icons.Default.CenterFocusStrong,
                    onClick = { onPeakingChange(!peakingEnabled) },
                    compact = compact,
                    tint = if (peakingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                if (!veryCompact) {
                    Text(
                        text = "${magnification.toInt()}x",
                        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun afAreaModeStringRes(mode: AfAreaMode): Int = when (mode) {
    AfAreaMode.SinglePoint -> R.string.af_area_single
    AfAreaMode.Zone -> R.string.af_area_zone
    AfAreaMode.Tracking -> R.string.af_area_tracking
    AfAreaMode.FaceDetection -> R.string.af_area_face
}

@Composable
internal fun CompactToggleButton(
    label: String,
    compact: Boolean,
    veryCompact: Boolean = false,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val padding = when {
        veryCompact -> androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        compact -> androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        else -> ButtonDefaults.ContentPadding
    }
    Button(
        onClick = onClick,
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
            overflow = TextOverflow.Ellipsis,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun CompactIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    compact: Boolean,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    val size = if (compact) 32.dp else 40.dp
    val iconSize = if (compact) 18.dp else 22.dp
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = Modifier.size(size)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = tint
        )
    }
}
