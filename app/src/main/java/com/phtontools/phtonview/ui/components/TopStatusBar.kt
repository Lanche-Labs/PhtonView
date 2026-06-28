package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.phtontools.phtonview.ui.theme.AccentGreen
import com.phtontools.phtonview.ui.theme.AccentYellow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.model.CameraBrand
import com.phtontools.phtonview.data.model.ConnectionState
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.data.model.ExposureSettings
import com.phtontools.phtonview.data.model.MeteringResult

@Composable
fun TopStatusBar(
    brand: CameraBrand,
    connectionType: ConnectionType,
    connectionState: ConnectionState,
    metering: MeteringResult,
    exposure: ExposureSettings,
    modifier: Modifier = Modifier
) {
    val (statusText, statusColor) = rememberStatusPair(connectionState)
    val brandText = if (brand == CameraBrand.Generic) stringResource(id = R.string.multi_brand) else brand.name
    val modeText = "$brandText · ${connectionType.name}"

    BoxWithConstraints(modifier = modifier) {
        val narrow = maxWidth < 320.dp
        val fontSize = if (narrow) 11.sp else 13.sp
        val padding = if (narrow) 6.dp else 10.dp
        val innerPaddingH = if (narrow) 8.dp else 12.dp
        val innerPaddingV = if (narrow) 6.dp else 8.dp

        if (narrow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = innerPaddingH, vertical = innerPaddingV)
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = modeText,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = infoText(metering, exposure),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = innerPaddingH, vertical = innerPaddingV)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = modeText,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = infoText(metering, exposure),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun infoText(metering: MeteringResult, exposure: ExposureSettings): String {
    return String.format(
        stringResource(id = R.string.camera_info_format),
        metering.ev,
        exposure.aperture,
        exposure.shutter,
        exposure.iso
    )
}

@Composable
private fun rememberStatusPair(connectionState: ConnectionState): Pair<String, Color> {
    return when (connectionState) {
        is ConnectionState.Connected -> Pair(
            stringResource(id = R.string.status_connected, connectionState.model),
            AccentGreen
        )
        is ConnectionState.Connecting -> Pair(
            stringResource(id = R.string.status_connecting),
            AccentYellow
        )
        is ConnectionState.Error -> Pair(
            stringResource(id = R.string.status_disconnected),
            AccentYellow
        )
        is ConnectionState.Disconnected -> Pair(
            stringResource(id = R.string.status_disconnected),
            AccentYellow
        )
    }
}
