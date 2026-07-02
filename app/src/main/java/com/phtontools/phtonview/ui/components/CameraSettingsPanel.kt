package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.model.*

/**
 * 侧滑选单：收起不常用的拍摄与辅助功能。
 *
 * 采用类似系统设置的风格：
 * - 每个分类可折叠；
 * - 枚举项使用横向滚动的选项条（左右滑动选择）；
 * - 高级参数使用列表项展开，避免选单过长。
 */
@Composable
fun CameraSettingsPanel(
    exposure: ExposureSettings,
    settings: CameraSettings,
    metering: MeteringResult,
    focusMode: FocusMode,
    afMode: AfMode,
    magnification: Float,
    peakingEnabled: Boolean,
    intervalometer: IntervalometerSettings,
    bulbSettings: BulbSettings,
    timerSettings: TimerSettings,
    aebSettings: AebSettings,
    histogramType: HistogramType,
    gridType: GridType,
    zebraPattern: ZebraPattern,
    liveViewEnabled: Boolean,
    onFocusModeChange: (FocusMode) -> Unit,
    onAfModeChange: (AfMode) -> Unit,
    onMagnificationChange: (Float) -> Unit,
    onPeakingChange: (Boolean) -> Unit,
    onMeteringModeChange: (MeteringMode) -> Unit,
    onIsoChange: (Int) -> Unit,
    onApertureChange: (String) -> Unit,
    onShutterChange: (String) -> Unit,
    onEvChange: (Float) -> Unit,
    onWhiteBalanceChange: (WhiteBalance, Int?) -> Unit,
    onFlashModeChange: (FlashMode) -> Unit,
    onFlashCompensationChange: (Float) -> Unit,
    onStorageTargetChange: (StorageTarget) -> Unit,
    onHistogramTypeChange: (HistogramType) -> Unit,
    onGridTypeChange: (GridType) -> Unit,
    onZebraPatternChange: (ZebraPattern) -> Unit,
    onLiveViewEnabledChange: (Boolean) -> Unit,
    onBurst: () -> Unit,
    onBulb: () -> Unit,
    onTimer: () -> Unit,
    onIntervalometer: () -> Unit,
    onAeb: () -> Unit,
    onApplyPreset: (ShootingPreset) -> Unit,
    onSyncDateTime: () -> Unit,
    onFetchStatus: () -> Unit,
    onBulbChange: (BulbSettings) -> Unit,
    onTimerChange: (TimerSettings) -> Unit,
    onIntervalometerChange: (IntervalometerSettings) -> Unit,
    onAebChange: (AebSettings) -> Unit,
    onBurstCountChange: (Int) -> Unit,
    onBurstSpeedChange: (BurstSpeed) -> Unit,
    onResetToDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints {
        val compact = maxWidth < 300.dp
        val padding = if (compact) 10.dp else 16.dp

        Column(
            modifier = modifier
                .fillMaxHeight()
                .widthIn(min = 300.dp, max = 420.dp)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surface)
                .padding(padding)
        ) {
            // 标题 + 恢复默认
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.capture_settings),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedButton(
                    onClick = onResetToDefaults,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(text = stringResource(id = R.string.reset_to_defaults))
                }
            }

            // 对焦
            ExpandableSection(title = stringResource(id = R.string.focus)) {
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.focus_mode))
                    HorizontalEnumSelector(
                        values = FocusMode.entries,
                        selected = focusMode,
                        label = { it.displayName() },
                        onSelected = onFocusModeChange
                    )
                }
                if (focusMode == FocusMode.AF) {
                    SectionItem {
                        SectionLabel(text = stringResource(id = R.string.af_mode))
                        HorizontalEnumSelector(
                            values = AfMode.entries,
                            selected = afMode,
                            label = { it.displayName() },
                            onSelected = onAfModeChange
                        )
                    }
                } else {
                    SectionItem {
                        SliderWithLabel(
                            label = stringResource(id = R.string.magnification_format, magnification),
                            value = magnification,
                            onValueChange = onMagnificationChange,
                            valueRange = 1f..8f,
                            steps = 6
                        )
                    }
                }
                SectionItem {
                    ToggleRow(
                        label = stringResource(id = R.string.focus_peaking),
                        checked = peakingEnabled,
                        onCheckedChange = onPeakingChange
                    )
                }
            }

            // 测光
            ExpandableSection(title = stringResource(id = R.string.metering_mode)) {
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.metering_mode))
                    HorizontalEnumSelector(
                        values = MeteringMode.entries,
                        selected = metering.mode,
                        label = { it.displayName() },
                        onSelected = onMeteringModeChange
                    )
                }
            }

            // 白平衡
            ExpandableSection(title = stringResource(id = R.string.white_balance)) {
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.white_balance))
                    HorizontalEnumSelector(
                        values = WhiteBalance.entries,
                        selected = settings.whiteBalance,
                        label = { it.displayName() },
                        onSelected = { wb ->
                            val kelvin = if (wb == WhiteBalance.Kelvin) settings.kelvinValue else null
                            onWhiteBalanceChange(wb, kelvin)
                        }
                    )
                }
                if (settings.whiteBalance == WhiteBalance.Kelvin) {
                    SectionItem {
                        SliderWithLabel(
                            label = "${settings.kelvinValue}K",
                            value = settings.kelvinValue.toFloat(),
                            onValueChange = { onWhiteBalanceChange(WhiteBalance.Kelvin, it.toInt()) },
                            valueRange = 2500f..10000f,
                            steps = 74
                        )
                    }
                }
            }

            // 闪光灯
            ExpandableSection(title = stringResource(id = R.string.flash_mode)) {
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.flash_mode))
                    HorizontalEnumSelector(
                        values = FlashMode.entries,
                        selected = settings.flashMode,
                        label = { it.displayName() },
                        onSelected = onFlashModeChange
                    )
                }
                SectionItem {
                    SliderWithLabel(
                        label = String.format("Flash Compensation %.1f EV", settings.flashCompensation),
                        value = settings.flashCompensation,
                        onValueChange = onFlashCompensationChange,
                        valueRange = -2f..2f,
                        steps = 7
                    )
                }
            }

            // 实时取景辅助
            ExpandableSection(title = stringResource(id = R.string.live_view_assist)) {
                SectionItem {
                    ToggleRow(
                        label = stringResource(id = R.string.live_view),
                        checked = liveViewEnabled,
                        onCheckedChange = onLiveViewEnabledChange
                    )
                }
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.histogram))
                    HorizontalEnumSelector(
                        values = HistogramType.entries,
                        selected = histogramType,
                        label = { it.displayName() },
                        onSelected = onHistogramTypeChange
                    )
                }
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.grid))
                    HorizontalEnumSelector(
                        values = GridType.entries,
                        selected = gridType,
                        label = { it.displayName() },
                        onSelected = onGridTypeChange
                    )
                }
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.zebra))
                    HorizontalEnumSelector(
                        values = ZebraPattern.entries,
                        selected = zebraPattern,
                        label = { it.displayName() },
                        onSelected = onZebraPatternChange
                    )
                }
            }

            // 高级拍摄
            ExpandableSection(title = stringResource(id = R.string.advanced_capture)) {
                SectionItem {
                    AdvancedCaptureRow(
                        onBurst = onBurst,
                        onBulb = onBulb,
                        onTimer = onTimer,
                        onIntervalometer = onIntervalometer,
                        onAeb = onAeb
                    )
                }
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.burst_speed))
                    HorizontalEnumSelector(
                        values = BurstSpeed.entries,
                        selected = settings.burstSpeed,
                        label = { it.displayName() },
                        onSelected = onBurstSpeedChange
                    )
                }
                SectionItem {
                    SliderWithLabel(
                        label = stringResource(id = R.string.burst_count) + ": ${settings.burstCount}",
                        value = settings.burstCount.toFloat(),
                        onValueChange = { onBurstCountChange(it.toInt()) },
                        valueRange = 2f..50f,
                        steps = 48
                    )
                }
                SectionItem {
                    SliderWithLabel(
                        label = stringResource(id = R.string.bulb_duration, bulbSettings.durationSeconds),
                        value = bulbSettings.durationSeconds.toFloat(),
                        onValueChange = { onBulbChange(bulbSettings.copy(durationSeconds = it.toInt())) },
                        valueRange = 1f..300f,
                        steps = 59
                    )
                }
                SectionItem {
                    SliderWithLabel(
                        label = stringResource(id = R.string.timer_delay, timerSettings.delaySeconds),
                        value = timerSettings.delaySeconds.toFloat(),
                        onValueChange = { onTimerChange(timerSettings.copy(delaySeconds = it.toInt())) },
                        valueRange = 1f..30f,
                        steps = 28
                    )
                }
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.intervalometer_info, intervalometer.intervalSeconds, intervalometer.totalShots))
                    Slider(
                        value = intervalometer.intervalSeconds.toFloat(),
                        onValueChange = { onIntervalometerChange(intervalometer.copy(intervalSeconds = it.toInt())) },
                        valueRange = 1f..60f,
                        steps = 59,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Slider(
                        value = intervalometer.totalShots.toFloat(),
                        onValueChange = { onIntervalometerChange(intervalometer.copy(totalShots = it.toInt())) },
                        valueRange = 2f..300f,
                        steps = 98,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.aeb_info, aebSettings.bracketCount, aebSettings.stepEv))
                    Slider(
                        value = aebSettings.bracketCount.toFloat(),
                        onValueChange = { onAebChange(aebSettings.copy(bracketCount = it.toInt())) },
                        valueRange = 3f..9f,
                        steps = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Slider(
                        value = aebSettings.stepEv,
                        onValueChange = { onAebChange(aebSettings.copy(stepEv = it)) },
                        valueRange = 0.3f..2f,
                        steps = 16,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 预设
            ExpandableSection(title = stringResource(id = R.string.presets)) {
                SectionItem {
                    SectionLabel(text = stringResource(id = R.string.presets))
                    HorizontalEnumSelector(
                        values = ShootingPreset.entries,
                        selected = settings.preset,
                        label = { it.displayName() },
                        onSelected = onApplyPreset
                    )
                }
            }

            // 相机维护
            ExpandableSection(title = stringResource(id = R.string.camera_maintenance)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(onClick = onFetchStatus, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(id = R.string.fetch_status))
                    }
                    OutlinedButton(onClick = onSyncDateTime, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(id = R.string.sync_datetime))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 20.dp)
            ) {
                content()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    }
}

/**
 * 选单中单个设置项容器。
 *
 * 保证开关、滑块、按钮等控件各自独占一行，互不重叠。
 */
@Composable
private fun SectionItem(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    ) {
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun <T> HorizontalEnumSelector(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 12.dp)
    ) {
        values.forEach { value ->
            SelectableChip(
                label = label(value),
                selected = value == selected,
                onClick = { onSelected(value) }
            )
        }
    }
}

@Composable
private fun AdvancedCaptureRow(
    onBurst: () -> Unit,
    onBulb: () -> Unit,
    onTimer: () -> Unit,
    onIntervalometer: () -> Unit,
    onAeb: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AdvancedButton(label = stringResource(id = R.string.burst), summary = stringResource(id = R.string.burst_summary), onClick = onBurst)
        AdvancedButton(label = stringResource(id = R.string.bulb), summary = stringResource(id = R.string.bulb_summary), onClick = onBulb)
        AdvancedButton(label = stringResource(id = R.string.timer_2s), summary = stringResource(id = R.string.timer_summary), onClick = onTimer)
        AdvancedButton(label = stringResource(id = R.string.interval), summary = stringResource(id = R.string.interval_summary), onClick = onIntervalometer)
        AdvancedButton(label = stringResource(id = R.string.aeb), summary = stringResource(id = R.string.aeb_summary), onClick = onAeb)
    }
}

@Composable
private fun AdvancedButton(label: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SliderWithLabel(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 12.dp)
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    UnifiedSwitchRow(
        label = label,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    UnifiedChip(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.widthIn(min = 72.dp)
    )
}

// 显示名称：优先使用字符串资源，避免直接显示枚举名
@Composable
private fun FocusMode.displayName(): String = stringResource(
    id = when (this) {
        FocusMode.MF -> R.string.focus_mf
        FocusMode.AF -> R.string.focus_af
    }
)

@Composable
private fun AfMode.displayName(): String = stringResource(
    id = when (this) {
        AfMode.AF_S -> R.string.af_s
        AfMode.AF_C -> R.string.af_c
    }
)

@Composable
private fun MeteringMode.displayName(): String = stringResource(
    id = when (this) {
        MeteringMode.Matrix -> R.string.metering_matrix
        MeteringMode.CenterWeighted -> R.string.metering_center
        MeteringMode.Spot -> R.string.metering_spot
    }
)

@Composable
private fun WhiteBalance.displayName(): String = stringResource(
    id = when (this) {
        WhiteBalance.Auto -> R.string.wb_auto
        WhiteBalance.Daylight -> R.string.wb_daylight
        WhiteBalance.Cloudy -> R.string.wb_cloudy
        WhiteBalance.Shade -> R.string.wb_shade
        WhiteBalance.Tungsten -> R.string.wb_tungsten
        WhiteBalance.Fluorescent -> R.string.wb_fluorescent
        WhiteBalance.Kelvin -> R.string.wb_kelvin
        WhiteBalance.Flash -> R.string.wb_flash
        WhiteBalance.Custom -> R.string.wb_custom
    }
)

@Composable
private fun FlashMode.displayName(): String = stringResource(
    id = when (this) {
        FlashMode.Off -> R.string.flash_off
        FlashMode.On -> R.string.flash_on
        FlashMode.Auto -> R.string.flash_auto
        FlashMode.SlowSync -> R.string.flash_slow_sync
        FlashMode.RedEye -> R.string.flash_red_eye
        FlashMode.RearSync -> R.string.flash_rear_sync
    }
)

@Composable
private fun HistogramType.displayName(): String = stringResource(
    id = when (this) {
        HistogramType.None -> R.string.off
        HistogramType.Luminance -> R.string.histogram_luminance
        HistogramType.RGB -> R.string.histogram_rgb
    }
)

@Composable
private fun GridType.displayName(): String = stringResource(
    id = when (this) {
        GridType.None -> R.string.off
        GridType.RuleOfThirds -> R.string.grid_rule_of_thirds
        GridType.GoldenRatio -> R.string.grid_golden_ratio
        GridType.Center -> R.string.grid_center
        GridType.Diagonal -> R.string.grid_diagonal
    }
)

@Composable
private fun ZebraPattern.displayName(): String = stringResource(
    id = when (this) {
        ZebraPattern.None -> R.string.off
        ZebraPattern.Over -> R.string.zebra_over
        ZebraPattern.Under -> R.string.zebra_under
        ZebraPattern.Both -> R.string.zebra_both
    }
)

@Composable
private fun ShootingPreset.displayName(): String = stringResource(
    id = when (this) {
        ShootingPreset.Portrait -> R.string.preset_portrait
        ShootingPreset.Landscape -> R.string.preset_landscape
        ShootingPreset.Sports -> R.string.preset_sports
        ShootingPreset.Night -> R.string.preset_night
        ShootingPreset.Macro -> R.string.preset_macro
        ShootingPreset.Studio -> R.string.preset_studio
        ShootingPreset.User1 -> R.string.preset_user1
        ShootingPreset.User2 -> R.string.preset_user2
    }
)

@Composable
private fun BurstSpeed.displayName(): String = when (this) {
    BurstSpeed.Low -> "1 fps"
    BurstSpeed.Medium -> "3 fps"
    BurstSpeed.High -> "5 fps"
    BurstSpeed.Max -> "8 fps"
}
