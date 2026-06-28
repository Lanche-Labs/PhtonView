package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.model.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraSettingsPanel(
    exposure: ExposureSettings,
    settings: CameraSettings,
    intervalometer: IntervalometerSettings,
    bulbSettings: BulbSettings,
    timerSettings: TimerSettings,
    aebSettings: AebSettings,
    histogramType: HistogramType,
    gridType: GridType,
    zebraPattern: ZebraPattern,
    onIsoChange: (Int) -> Unit,
    onApertureChange: (String) -> Unit,
    onShutterChange: (String) -> Unit,
    onEvChange: (Float) -> Unit,
    onImageFormatChange: (ImageFormat) -> Unit,
    onImageSizeChange: (ImageSize) -> Unit,
    onBurstSpeedChange: (BurstSpeed) -> Unit,
    onShootingModeChange: (ShootingMode) -> Unit,
    onWhiteBalanceChange: (WhiteBalance, Int?) -> Unit,
    onFlashModeChange: (FlashMode) -> Unit,
    onFlashCompensationChange: (Float) -> Unit,
    onStorageTargetChange: (StorageTarget) -> Unit,
    onHistogramTypeChange: (HistogramType) -> Unit,
    onGridTypeChange: (GridType) -> Unit,
    onZebraPatternChange: (ZebraPattern) -> Unit,
    onIntervalometerChange: (IntervalometerSettings) -> Unit,
    onBulbChange: (BulbSettings) -> Unit,
    onTimerChange: (TimerSettings) -> Unit,
    onAebChange: (AebSettings) -> Unit,
    onApplyPreset: (ShootingPreset) -> Unit,
    onSyncDateTime: () -> Unit,
    onFetchStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints {
        val compact = maxWidth < 300.dp
        val padding = if (compact) 10.dp else 16.dp

        Column(
            modifier = modifier
                .fillMaxHeight()
                .widthIn(min = 260.dp, max = 360.dp)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surface)
                .padding(padding)
        ) {
            Text(
                text = stringResource(id = R.string.capture_settings),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            SettingSection(title = stringResource(id = R.string.shooting_mode)) {
                EnumSelector(
                    values = ShootingMode.entries,
                    selected = settings.shootingMode,
                    compact = compact,
                    label = { it.name },
                    onSelected = onShootingModeChange
                )
            }

            SettingSection(title = stringResource(id = R.string.iso)) {
                ValueSelector(
                    values = listOf("100", "200", "400", "800", "1600", "3200", "6400", "12800", "25600", "51200"),
                    selected = exposure.iso.toString(),
                    compact = compact,
                    onSelected = { onIsoChange(it.toIntOrNull() ?: 400) }
                )
            }

            SettingSection(title = stringResource(id = R.string.aperture)) {
                ValueSelector(
                    values = listOf("f/1.4", "f/1.8", "f/2.0", "f/2.8", "f/4", "f/5.6", "f/8", "f/11", "f/16", "f/22"),
                    selected = exposure.aperture,
                    compact = compact,
                    onSelected = onApertureChange
                )
            }

            SettingSection(title = stringResource(id = R.string.shutter)) {
                ValueSelector(
                    values = listOf("1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1s", "2s", "4s", "8s", "15s", "30s", "Bulb"),
                    selected = exposure.shutter,
                    compact = compact,
                    onSelected = onShutterChange
                )
            }

            SettingSection(title = stringResource(id = R.string.ev_comp)) {
                Text(
                    text = String.format("%.1f EV", exposure.ev),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = exposure.ev,
                    onValueChange = onEvChange,
                    valueRange = -3f..3f,
                    steps = 11
                )
            }

            SettingSection(title = stringResource(id = R.string.image_format)) {
                EnumSelector(
                    values = ImageFormat.entries,
                    selected = settings.imageFormat,
                    compact = compact,
                    label = { it.name.replace("_", "+") },
                    onSelected = onImageFormatChange
                )
            }

            SettingSection(title = stringResource(id = R.string.image_size)) {
                EnumSelector(
                    values = ImageSize.entries,
                    selected = settings.imageSize,
                    compact = compact,
                    label = { it.name },
                    onSelected = onImageSizeChange
                )
            }

            SettingSection(title = stringResource(id = R.string.burst_speed)) {
                EnumSelector(
                    values = BurstSpeed.entries,
                    selected = settings.burstSpeed,
                    compact = compact,
                    label = { "${it.framesPerSecond} fps" },
                    onSelected = onBurstSpeedChange
                )
            }

            SettingSection(title = stringResource(id = R.string.white_balance)) {
                EnumSelector(
                    values = WhiteBalance.entries,
                    selected = settings.whiteBalance,
                    compact = compact,
                    label = { it.name },
                    onSelected = { wb ->
                        val kelvin = if (wb == WhiteBalance.Kelvin) settings.kelvinValue else null
                        onWhiteBalanceChange(wb, kelvin)
                    }
                )
                if (settings.whiteBalance == WhiteBalance.Kelvin) {
                    Text(
                        text = "${settings.kelvinValue}K",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = settings.kelvinValue.toFloat(),
                        onValueChange = { onWhiteBalanceChange(WhiteBalance.Kelvin, it.toInt()) },
                        valueRange = 2500f..10000f,
                        steps = 74
                    )
                }
            }

            SettingSection(title = stringResource(id = R.string.flash_mode)) {
                EnumSelector(
                    values = FlashMode.entries,
                    selected = settings.flashMode,
                    compact = compact,
                    label = { it.name },
                    onSelected = onFlashModeChange
                )
            }

            SettingSection(title = stringResource(id = R.string.flash_compensation)) {
                Text(text = String.format("%.1f EV", settings.flashCompensation))
                Slider(
                    value = settings.flashCompensation,
                    onValueChange = onFlashCompensationChange,
                    valueRange = -2f..2f,
                    steps = 7
                )
            }

            SettingSection(title = stringResource(id = R.string.storage_target)) {
                EnumSelector(
                    values = StorageTarget.entries,
                    selected = settings.storageTarget,
                    compact = compact,
                    label = { it.name },
                    onSelected = onStorageTargetChange
                )
            }

            SettingSection(title = stringResource(id = R.string.live_view_assist)) {
                EnumSelector(
                    values = HistogramType.entries,
                    selected = histogramType,
                    compact = compact,
                    label = { it.name },
                    onSelected = onHistogramTypeChange
                )
                EnumSelector(
                    values = GridType.entries,
                    selected = gridType,
                    compact = compact,
                    label = { it.name },
                    onSelected = onGridTypeChange
                )
                EnumSelector(
                    values = ZebraPattern.entries,
                    selected = zebraPattern,
                    compact = compact,
                    label = { it.name },
                    onSelected = onZebraPatternChange
                )
            }

            SettingSection(title = stringResource(id = R.string.advanced_capture)) {
                Text(
                    text = stringResource(id = R.string.bulb_duration, bulbSettings.durationSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = bulbSettings.durationSeconds.toFloat(),
                    onValueChange = { onBulbChange(bulbSettings.copy(durationSeconds = it.toInt())) },
                    valueRange = 1f..300f,
                    steps = 59
                )

                Text(
                    text = stringResource(id = R.string.timer_delay, timerSettings.delaySeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = timerSettings.delaySeconds.toFloat(),
                    onValueChange = { onTimerChange(timerSettings.copy(delaySeconds = it.toInt())) },
                    valueRange = 1f..30f,
                    steps = 28
                )

                Text(
                    text = stringResource(id = R.string.intervalometer_info, intervalometer.intervalSeconds, intervalometer.totalShots),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = intervalometer.intervalSeconds.toFloat(),
                    onValueChange = { onIntervalometerChange(intervalometer.copy(intervalSeconds = it.toInt())) },
                    valueRange = 1f..60f,
                    steps = 59
                )
                Slider(
                    value = intervalometer.totalShots.toFloat(),
                    onValueChange = { onIntervalometerChange(intervalometer.copy(totalShots = it.toInt())) },
                    valueRange = 2f..300f,
                    steps = 98
                )

                Text(
                    text = stringResource(id = R.string.aeb_info, aebSettings.bracketCount, aebSettings.stepEv),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = aebSettings.bracketCount.toFloat(),
                    onValueChange = { onAebChange(aebSettings.copy(bracketCount = it.toInt())) },
                    valueRange = 3f..9f,
                    steps = 5
                )
                Slider(
                    value = aebSettings.stepEv,
                    onValueChange = { onAebChange(aebSettings.copy(stepEv = it)) },
                    valueRange = 0.3f..2f,
                    steps = 16
                )
            }

            SettingSection(title = stringResource(id = R.string.presets)) {
                EnumSelector(
                    values = ShootingPreset.entries,
                    selected = settings.preset,
                    compact = compact,
                    label = { it.name },
                    onSelected = onApplyPreset
                )
            }

            SettingSection(title = stringResource(id = R.string.camera_maintenance)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = onFetchStatus) {
                        Text(text = stringResource(id = R.string.fetch_status))
                    }
                    OutlinedButton(onClick = onSyncDateTime) {
                        Text(text = stringResource(id = R.string.sync_datetime))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValueSelector(
    values: List<String>,
    selected: String,
    compact: Boolean,
    onSelected: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        values.forEach { value ->
            SelectableChip(
                label = value,
                selected = value == selected,
                compact = compact,
                onClick = { onSelected(value) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> EnumSelector(
    values: List<T>,
    selected: T,
    compact: Boolean,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        values.forEach { value ->
            SelectableChip(
                label = label(value),
                selected = value == selected,
                compact = compact,
                onClick = { onSelected(value) }
            )
        }
    }
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val padding = if (compact) {
        androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    } else {
        androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    }
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            contentPadding = padding
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            contentPadding = padding
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
