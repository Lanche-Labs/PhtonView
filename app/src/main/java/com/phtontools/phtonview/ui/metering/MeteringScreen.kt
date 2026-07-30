package com.phtontools.phtonview.ui.metering

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phtontools.phtonview.R
import com.phtontools.phtonview.metering.MeteringMath
import com.phtontools.phtonview.metering.PhoneCameraMeter

/**
 * 专业测光界面（横屏优化）。
 *
 * 布局：
 * - 竖屏：顶栏 + 预览 + 参数面板（垂直）
 * - 横屏：顶栏 + 左侧预览 + 右侧参数面板（水平）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeteringScreen(
    onBack: () -> Unit,
    viewModel: MeteringViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val recommendation by viewModel.recommendation.collectAsState()
    val applyMessage by viewModel.applyMessage.collectAsState()
    val pattern by viewModel.pattern.collectAsState()
    val spotPoint by viewModel.spotPoint.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val fixedAperture by viewModel.fixedAperture.collectAsState()
    val fixedShutter by viewModel.fixedShutter.collectAsState()
    val fixedIso by viewModel.fixedIso.collectAsState()

    var hasPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        viewModel.requestPermission()
        if (granted) viewModel.startMeter()
    }

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.requestPermission()
        if (hasPermission) viewModel.startMeter()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (hasPermission) viewModel.startMeter()
                Lifecycle.Event.ON_STOP -> viewModel.stopMeter()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.metering_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLandscape) {
            // 横屏布局：左侧预览 + 右侧参数
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 左侧：预览区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp)
                ) {
                    PhonePreviewArea(
                        hasPermission = hasPermission,
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        pattern = pattern,
                        spotPoint = spotPoint,
                        onSurface = { surface -> viewModel.onPhonePreviewSurfaceAvailable(surface) },
                        onTap = { x, y -> viewModel.setSpotPoint(x to y) }
                    )
                }
                
                // 右侧：参数面板
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // EV 显示
                    EvDisplay(recommendation?.ev)
                    
                    // 模式选择
                    ModeSelector(mode, viewModel::setMode)
                    
                    // 参数选择器
                    ParameterSelector(
                        mode = mode,
                        fixedAperture = fixedAperture,
                        fixedShutter = fixedShutter,
                        fixedIso = fixedIso,
                        onApertureChange = viewModel::setFixedAperture,
                        onShutterChange = viewModel::setFixedShutter,
                        onIsoChange = viewModel::setFixedIso
                    )
                    
                    // 推荐参数
                    RecommendationDisplay(recommendation)
                    
                    // 应用按钮
                    Button(
                        onClick = { viewModel.applyRecommendation() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("应用到机身")
                    }
                }
            }
        } else {
            // 竖屏布局：顶部预览 + 底部参数（可滚动）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 测光模式选择（评价/中心/点测）- 紧凑
                MeteringPatternSelector(
                    current = pattern,
                    onSelect = viewModel::setPattern
                )

                // 预览区域 - 固定高度
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    PhonePreviewArea(
                        hasPermission = hasPermission,
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        pattern = pattern,
                        spotPoint = spotPoint,
                        onSurface = { surface -> viewModel.onPhonePreviewSurfaceAvailable(surface) },
                        onTap = { x, y -> viewModel.setSpotPoint(x to y) }
                    )
                }

                // 参数面板 - 可滚动
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val rec = recommendation
                    // EV 显示 + 模式选择（横向排列）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // EV 显示
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("EV", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (rec != null && !rec.ev.isNaN()) 
                                           String.format("%.1f", rec.ev) else "--",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        // 模式选择
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.weight(1f)
                        ) {
                            SegmentedButton(
                                selected = mode == MeteringViewModel.MeteringMode.AperturePriority,
                                onClick = { viewModel.setMode(MeteringViewModel.MeteringMode.AperturePriority) },
                                shape = SegmentedButtonDefaults.itemShape(0, 3)
                            ) { Text("A", fontSize = 12.sp) }
                            SegmentedButton(
                                selected = mode == MeteringViewModel.MeteringMode.ShutterPriority,
                                onClick = { viewModel.setMode(MeteringViewModel.MeteringMode.ShutterPriority) },
                                shape = SegmentedButtonDefaults.itemShape(1, 3)
                            ) { Text("S", fontSize = 12.sp) }
                            SegmentedButton(
                                selected = mode == MeteringViewModel.MeteringMode.Manual,
                                onClick = { viewModel.setMode(MeteringViewModel.MeteringMode.Manual) },
                                shape = SegmentedButtonDefaults.itemShape(2, 3)
                            ) { Text("M", fontSize = 12.sp) }
                        }
                    }
                    
                    // 参数选择器
                    ParameterSelector(
                        mode = mode,
                        fixedAperture = fixedAperture,
                        fixedShutter = fixedShutter,
                        fixedIso = fixedIso,
                        onApertureChange = viewModel::setFixedAperture,
                        onShutterChange = viewModel::setFixedShutter,
                        onIsoChange = viewModel::setFixedIso
                    )
                    
                    // 推荐参数
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("推荐参数", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("光圈", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(
                                        if (rec != null) MeteringMath.formatAperture(rec.aperture) else "--",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("快门", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(
                                        if (rec != null) MeteringMath.formatShutter(rec.shutterSeconds) else "--",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("ISO", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(
                                        if (rec != null) rec.iso.toString() else "--",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    
                    // 应用按钮
                    Button(
                        onClick = { viewModel.applyRecommendation() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("应用到机身", fontSize = 12.sp)
                    }
                    
                    // 底部留白，确保滚动到底时有空间
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // 应用结果提示
        applyMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = viewModel::dismissApplyMessage,
                title = { Text(stringResource(id = R.string.metering_apply_result)) },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissApplyMessage) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

/** EV 值显示 */
@Composable
private fun EvDisplay(ev: Double?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("EV", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (ev != null && !ev.isNaN()) String.format("%.1f", ev) else "--",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 测光模式选择（A/S/M） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(
    mode: MeteringViewModel.MeteringMode,
    onModeChange: (MeteringViewModel.MeteringMode) -> Unit
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        SegmentedButton(
            selected = mode == MeteringViewModel.MeteringMode.AperturePriority,
            onClick = { onModeChange(MeteringViewModel.MeteringMode.AperturePriority) },
            shape = SegmentedButtonDefaults.itemShape(0, 3)
        ) { Text("A") }
        SegmentedButton(
            selected = mode == MeteringViewModel.MeteringMode.ShutterPriority,
            onClick = { onModeChange(MeteringViewModel.MeteringMode.ShutterPriority) },
            shape = SegmentedButtonDefaults.itemShape(1, 3)
        ) { Text("S") }
        SegmentedButton(
            selected = mode == MeteringViewModel.MeteringMode.Manual,
            onClick = { onModeChange(MeteringViewModel.MeteringMode.Manual) },
            shape = SegmentedButtonDefaults.itemShape(2, 3)
        ) { Text("M") }
    }
}

/** 参数选择器（光圈/快门/ISO） */
@Composable
private fun ParameterSelector(
    mode: MeteringViewModel.MeteringMode,
    fixedAperture: Double,
    fixedShutter: Double,
    fixedIso: Int,
    onApertureChange: (Double) -> Unit,
    onShutterChange: (Double) -> Unit,
    onIsoChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ISO 选择器（所有模式都可调）
        ParameterSlider(
            label = "ISO",
            value = fixedIso,
            values = MeteringMath.ISO_STOPS.toList(),
            enabled = true,
            onValueChange = onIsoChange,
            formatValue = { it.toString() }
        )
        
        // 光圈选择器（所有模式都可调）
        ParameterSlider(
            label = "光圈",
            value = fixedAperture,
            values = MeteringMath.APERTURE_STOPS.toList(),
            enabled = true,
            onValueChange = onApertureChange,
            formatValue = { MeteringMath.formatAperture(it) }
        )
        
        // 快门选择器（所有模式都可调）
        ParameterSlider(
            label = "快门",
            value = fixedShutter,
            values = MeteringMath.SHUTTER_STOPS_SECONDS.toList(),
            enabled = true,
            onValueChange = onShutterChange,
            formatValue = { MeteringMath.formatShutter(it) }
        )
    }
}

/** 参数滑块选择器 */
@Composable
private fun <T> ParameterSlider(
    label: String,
    value: T,
    values: List<T>,
    enabled: Boolean,
    onValueChange: (T) -> Unit,
    formatValue: (T) -> String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(formatValue(value), fontWeight = FontWeight.Bold)
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(values) { v ->
                Surface(
                    modifier = Modifier.size(width = 48.dp, height = 36.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = if (v == value && enabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { if (enabled) onValueChange(v) }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            formatValue(v),
                            fontSize = 10.sp,
                            color = if (v == value && enabled) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 推荐参数显示 */
@Composable
private fun RecommendationDisplay(rec: MeteringViewModel.Recommendation?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text("推荐参数", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("光圈", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        if (rec != null) MeteringMath.formatAperture(rec.aperture) else "--",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("快门", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        if (rec != null) MeteringMath.formatShutter(rec.shutterSeconds) else "--",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ISO", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        if (rec != null) rec.iso.toString() else "--",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** 测光模式选择（评价/中心/点测） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeteringPatternSelector(
    current: PhoneCameraMeter.MeteringPattern,
    onSelect: (PhoneCameraMeter.MeteringPattern) -> Unit
) {
    val options = listOf(
        PhoneCameraMeter.MeteringPattern.Matrix to R.string.metering_matrix,
        PhoneCameraMeter.MeteringPattern.CenterWeighted to R.string.metering_center,
        PhoneCameraMeter.MeteringPattern.Spot to R.string.metering_spot
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        options.forEachIndexed { index, (pat, label) ->
            SegmentedButton(
                selected = current == pat,
                onClick = { onSelect(pat) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size)
            ) {
                Text(stringResource(id = label), fontSize = 12.sp)
            }
        }
    }
}

/** 手机摄像头预览区域 */
@Composable
private fun PhonePreviewArea(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    pattern: PhoneCameraMeter.MeteringPattern,
    spotPoint: Pair<Float, Float>?,
    onSurface: (android.view.Surface?) -> Unit,
    onTap: (Float, Float) -> Unit
) {
    if (!hasPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(id = R.string.metering_permission_required),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequestPermission) {
                    Text(stringResource(id = R.string.metering_grant_permission))
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier.aspectRatio(4f / 3f)
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .pointerInput(pattern, widthPx, heightPx) {
                        if (pattern == PhoneCameraMeter.MeteringPattern.Spot) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val nx = (offset.x / widthPx).coerceIn(0f, 1f)
                                    val ny = (offset.y / heightPx).coerceIn(0f, 1f)
                                    onTap(nx, ny)
                                },
                                onDrag = { change, _ ->
                                    val nx = (change.position.x / widthPx).coerceIn(0f, 1f)
                                    val ny = (change.position.y / heightPx).coerceIn(0f, 1f)
                                    onTap(nx, ny)
                                    change.consume()
                                }
                            )
                        }
                    },
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurface(holder.surface)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurface(null)
                            }
                        })
                    }
                }
            )

            if (pattern == PhoneCameraMeter.MeteringPattern.Spot) {
                val (nx, ny) = spotPoint ?: (0.5f to 0.5f)
                SpotIndicator(nx, ny, widthPx, heightPx)
            }
        }
    }
}

/** 点测指示器 */
@Composable
private fun SpotIndicator(nx: Float, ny: Float, parentWidthPx: Float, parentHeightPx: Float) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .offsetPx(nx * parentWidthPx, ny * parentHeightPx)
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(20.dp, 2.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Box(
            modifier = Modifier
                .size(2.dp, 20.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** offsetPx 修饰符 */
private fun Modifier.offsetPx(x: Float, y: Float) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(
            x = (x - placeable.width / 2).toInt(),
            y = (y - placeable.height / 2).toInt()
        )
    }
}