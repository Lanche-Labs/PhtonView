package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.model.ExposureSettings
import com.phtontools.phtonview.data.model.GridType
import com.phtontools.phtonview.data.model.HistogramType
import com.phtontools.phtonview.data.model.MeteringMode
import com.phtontools.phtonview.data.model.MeteringResult
import com.phtontools.phtonview.data.model.ZebraPattern

/**
 * PRO 模式底部控制面板。
 *
 * 布局参考华为专业模式与朋友设计的 UI：
 * 1. 底部常驻四个大参数按钮 ISO / S / EV / F；
 * 2. 居中大圆形快门，左侧图库入口；
 * 3. 右侧为测光模式切换（全局 / 对点 / 区域），方便单手操作。
 */
@Composable
fun CleanBottomControlPanel(
    exposure: ExposureSettings,
    metering: MeteringResult,
    isLandscape: Boolean = false,
    onSelectParam: (ParamKind) -> Unit,
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit = {},
    onMeteringModeChange: (MeteringMode) -> Unit
) {
    if (isLandscape) {
        CleanLandscapeControlColumn(
            exposure = exposure,
            metering = metering,
            onSelectParam = onSelectParam,
            onCapture = onCapture,
            onOpenGallery = onOpenGallery,
            onMeteringModeChange = onMeteringModeChange
        )
    } else {
        CleanPortraitControlColumn(
            exposure = exposure,
            metering = metering,
            onSelectParam = onSelectParam,
            onCapture = onCapture,
            onOpenGallery = onOpenGallery,
            onMeteringModeChange = onMeteringModeChange
        )
    }
}

@Composable
private fun CleanPortraitControlColumn(
    exposure: ExposureSettings,
    metering: MeteringResult,
    onSelectParam: (ParamKind) -> Unit,
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit,
    onMeteringModeChange: (MeteringMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 顶部参数条：ISO S EV F
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ParamButton(
                kind = ParamKind.ISO,
                value = exposure.iso.toString(),
                onClick = { onSelectParam(ParamKind.ISO) }
            )
            ParamButton(
                kind = ParamKind.SHUTTER,
                value = exposure.shutter,
                onClick = { onSelectParam(ParamKind.SHUTTER) }
            )
            ParamButton(
                kind = ParamKind.EV,
                value = formatEv(exposure.ev),
                onClick = { onSelectParam(ParamKind.EV) }
            )
            ParamButton(
                kind = ParamKind.APERTURE,
                value = exposure.aperture,
                onClick = { onSelectParam(ParamKind.APERTURE) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 快门区 + 右侧测光模式
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：图库入口
            IconButton(
                onClick = onOpenGallery,
                modifier = Modifier.size(46.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = stringResource(id = R.string.gallery),
                    modifier = Modifier.size(22.dp)
                )
            }

            // 居中快门
            ShutterButton(onClick = onCapture)

            // 右侧：测光模式切换
            MeteringColumn(
                selected = metering.mode,
                onSelect = onMeteringModeChange
            )
        }
    }
}

@Composable
private fun CleanLandscapeControlColumn(
    exposure: ExposureSettings,
    metering: MeteringResult,
    onSelectParam: (ParamKind) -> Unit,
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit,
    onMeteringModeChange: (MeteringMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(120.dp)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 顶部：测光模式
        MeteringColumn(
            selected = metering.mode,
            onSelect = onMeteringModeChange
        )

        // 中间：参数按钮（横向滚动，避免EV/F被挤掉）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            LandscapeParamButton(
                kind = ParamKind.ISO,
                value = exposure.iso.toString(),
                onClick = { onSelectParam(ParamKind.ISO) }
            )
            LandscapeParamButton(
                kind = ParamKind.SHUTTER,
                value = exposure.shutter,
                onClick = { onSelectParam(ParamKind.SHUTTER) }
            )
            LandscapeParamButton(
                kind = ParamKind.EV,
                value = formatEv(exposure.ev),
                onClick = { onSelectParam(ParamKind.EV) }
            )
            LandscapeParamButton(
                kind = ParamKind.APERTURE,
                value = exposure.aperture,
                onClick = { onSelectParam(ParamKind.APERTURE) }
            )
        }

        // 底部：快门 + 图库
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShutterButton(size = 64.dp, onClick = onCapture)
            IconButton(
                onClick = onOpenGallery,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = stringResource(id = R.string.gallery),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 专业模式底部控制面板。
 *
 * 在简洁模式基础上，底部增加一排快捷功能入口（峰值、网格、直方图、斑马纹、
 * 实时取景、B门、定时器、间隔拍摄、AEB），方便一键调用。
 */
@Composable
fun ProBottomControlPanel(
    exposure: ExposureSettings,
    metering: MeteringResult,
    isLandscape: Boolean = false,
    peakingEnabled: Boolean,
    gridType: GridType,
    histogramType: HistogramType,
    zebraPattern: ZebraPattern,
    liveViewEnabled: Boolean,
    burstRunning: Boolean,
    bulbEnabled: Boolean,
    onSelectParam: (ParamKind) -> Unit,
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit = {},
    onMeteringModeChange: (MeteringMode) -> Unit,
    onTogglePeaking: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleLiveView: () -> Unit,
    onBurst: () -> Unit,
    onBulb: () -> Unit
) {
    if (isLandscape) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CleanLandscapeControlColumn(
                exposure = exposure,
                metering = metering,
                onSelectParam = onSelectParam,
                onCapture = onCapture,
                onOpenGallery = onOpenGallery,
                onMeteringModeChange = onMeteringModeChange
            )
            ProToolColumn(
                peakingEnabled = peakingEnabled,
                gridType = gridType,
                histogramType = histogramType,
                zebraPattern = zebraPattern,
                liveViewEnabled = liveViewEnabled,
                burstRunning = burstRunning,
                bulbEnabled = bulbEnabled,
                onTogglePeaking = onTogglePeaking,
                onToggleGrid = onToggleGrid,
                onToggleHistogram = onToggleHistogram,
                onToggleZebra = onToggleZebra,
                onToggleLiveView = onToggleLiveView,
                onBurst = onBurst,
                onBulb = onBulb
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            ProToolRow(
                peakingEnabled = peakingEnabled,
                gridType = gridType,
                histogramType = histogramType,
                zebraPattern = zebraPattern,
                liveViewEnabled = liveViewEnabled,
                burstRunning = burstRunning,
                bulbEnabled = bulbEnabled,
                onTogglePeaking = onTogglePeaking,
                onToggleGrid = onToggleGrid,
                onToggleHistogram = onToggleHistogram,
                onToggleZebra = onToggleZebra,
                onToggleLiveView = onToggleLiveView,
                onBurst = onBurst,
                onBulb = onBulb
            )
            CleanPortraitControlColumn(
                exposure = exposure,
                metering = metering,
                onSelectParam = onSelectParam,
                onCapture = onCapture,
                onOpenGallery = onOpenGallery,
                onMeteringModeChange = onMeteringModeChange
            )
        }
    }
}

@Composable
private fun ProToolRow(
    peakingEnabled: Boolean,
    gridType: GridType,
    histogramType: HistogramType,
    zebraPattern: ZebraPattern,
    liveViewEnabled: Boolean,
    burstRunning: Boolean,
    bulbEnabled: Boolean,
    onTogglePeaking: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleLiveView: () -> Unit,
    onBurst: () -> Unit,
    onBulb: () -> Unit
) {
    val tools = listOf(
        ToolItem(stringResource(id = R.string.focus_peaking), peakingEnabled, onTogglePeaking),
        ToolItem(stringResource(id = R.string.grid), gridType != GridType.None, onToggleGrid),
        ToolItem(stringResource(id = R.string.histogram), histogramType != HistogramType.None, onToggleHistogram),
        ToolItem(stringResource(id = R.string.zebra), zebraPattern != ZebraPattern.None, onToggleZebra),
        ToolItem(stringResource(id = R.string.live_view), liveViewEnabled, onToggleLiveView),
        ToolItem(stringResource(id = R.string.burst), burstRunning, onBurst),
        ToolItem(stringResource(id = R.string.bulb), bulbEnabled, onBulb)
    )

    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(tools) { tool ->
            ProToolChip(label = tool.label, selected = tool.selected, onClick = tool.onClick)
        }
    }
}

@Composable
private fun ProToolColumn(
    peakingEnabled: Boolean,
    gridType: GridType,
    histogramType: HistogramType,
    zebraPattern: ZebraPattern,
    liveViewEnabled: Boolean,
    burstRunning: Boolean,
    bulbEnabled: Boolean,
    onTogglePeaking: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleLiveView: () -> Unit,
    onBurst: () -> Unit,
    onBulb: () -> Unit
) {
    val tools = listOf(
        ToolItem(stringResource(id = R.string.focus_peaking), peakingEnabled, onTogglePeaking),
        ToolItem(stringResource(id = R.string.grid), gridType != GridType.None, onToggleGrid),
        ToolItem(stringResource(id = R.string.histogram), histogramType != HistogramType.None, onToggleHistogram),
        ToolItem(stringResource(id = R.string.zebra), zebraPattern != ZebraPattern.None, onToggleZebra),
        ToolItem(stringResource(id = R.string.live_view), liveViewEnabled, onToggleLiveView),
        ToolItem(stringResource(id = R.string.burst), burstRunning, onBurst),
        ToolItem(stringResource(id = R.string.bulb), bulbEnabled, onBulb)
    )

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tools.forEach { tool ->
            ProToolChip(label = tool.label, selected = tool.selected, onClick = tool.onClick)
        }
    }
}

private data class ToolItem(val label: String, val selected: Boolean, val onClick: () -> Unit)

@Composable
private fun ProToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    UnifiedChip(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 32.dp)
    )
}

@Composable
private fun ParamButton(
    kind: ParamKind,
    value: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = kind.label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LandscapeParamButton(
    kind: ParamKind,
    value: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = kind.label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ShutterButton(
    size: androidx.compose.ui.unit.Dp = 72.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .padding(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size - 16.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun MeteringColumn(
    selected: MeteringMode,
    onSelect: (MeteringMode) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MeteringButton(
            mode = MeteringMode.Matrix,
            selected = selected == MeteringMode.Matrix,
            onClick = { onSelect(MeteringMode.Matrix) }
        )
        MeteringButton(
            mode = MeteringMode.Spot,
            selected = selected == MeteringMode.Spot,
            onClick = { onSelect(MeteringMode.Spot) }
        )
        MeteringButton(
            mode = MeteringMode.CenterWeighted,
            selected = selected == MeteringMode.CenterWeighted,
            onClick = { onSelect(MeteringMode.CenterWeighted) }
        )
    }
}

@Composable
private fun MeteringButton(
    mode: MeteringMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    UnifiedChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.size(42.dp)
    ) {
        MeteringIcon(mode = mode, color = LocalContentColor.current)
    }
}

@Composable
private fun MeteringIcon(
    mode: MeteringMode,
    color: Color
) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = 1.5.dp.toPx()
        val center = this.center
        val radius = size.minDimension / 2 - stroke
        when (mode) {
            MeteringMode.Matrix -> {
                // 全局测光：外圈 + 中心点 + 四角小点
                drawCircle(color = color, radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawCircle(color = color, radius = 3.dp.toPx())
                val offset = radius * 0.6f
                listOf(
                    androidx.compose.ui.geometry.Offset(-offset, -offset),
                    androidx.compose.ui.geometry.Offset(offset, -offset),
                    androidx.compose.ui.geometry.Offset(-offset, offset),
                    androidx.compose.ui.geometry.Offset(offset, offset)
                ).forEach {
                    drawCircle(color = color, radius = 1.8.dp.toPx(), center = center + it)
                }
            }
            MeteringMode.Spot -> {
                // 点测光：中心实心点 + 外圈细环
                drawCircle(color = color, radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawCircle(color = color, radius = 5.dp.toPx())
            }
            MeteringMode.CenterWeighted -> {
                // 区域测光：外圈 + 中心实心圆
                drawCircle(color = color, radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawCircle(color = color, radius = 6.dp.toPx())
            }
        }
    }
}

private fun formatEv(ev: Float): String {
    val sign = when {
        ev > 0 -> "+"
        ev < 0 -> ""
        else -> ""
    }
    return "$sign${ev}"
}

enum class ParamKind(val label: String) {
    ISO("ISO"),
    SHUTTER("S"),
    EV("EV"),
    APERTURE("F")
}

/**
 * 标准模式底部控制面板。
 *
 * 画面优先：只保留居中大快门和左侧图库入口，不显示专业参数条。
 */
@Composable
fun SimpleBottomControlPanel(
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenGallery,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = stringResource(id = R.string.gallery),
                modifier = Modifier.size(22.dp)
            )
        }

        ShutterButton(size = 64.dp, onClick = onCapture)

        // 占位，保持快门居中
        Spacer(modifier = Modifier.size(44.dp))
    }
}

/**
 * 标准模式横屏侧边控制面板。
 */
@Composable
fun SimpleSideControlPanel(
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = onOpenGallery,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = stringResource(id = R.string.gallery),
                modifier = Modifier.size(20.dp)
            )
        }

        ShutterButton(size = 56.dp, onClick = onCapture)

        Spacer(modifier = Modifier.size(40.dp))
    }
}
