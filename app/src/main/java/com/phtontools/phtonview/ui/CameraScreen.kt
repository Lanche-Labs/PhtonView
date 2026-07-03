package com.phtontools.phtonview.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.local.UiMode
import com.phtontools.phtonview.data.model.AebSettings
import com.phtontools.phtonview.data.model.AfMode
import com.phtontools.phtonview.data.model.BulbSettings
import com.phtontools.phtonview.data.model.CameraSettings
import com.phtontools.phtonview.data.model.ConnectionState
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.data.model.ExposureSettings
import com.phtontools.phtonview.data.model.FocusMode
import com.phtontools.phtonview.data.model.GridType
import com.phtontools.phtonview.data.model.HistogramType
import com.phtontools.phtonview.data.model.IntervalometerSettings
import com.phtontools.phtonview.data.model.MeteringMode
import com.phtontools.phtonview.data.model.MeteringResult
import com.phtontools.phtonview.data.model.TimerSettings
import com.phtontools.phtonview.data.model.WhiteBalance
import com.phtontools.phtonview.data.model.ZebraPattern
import com.phtontools.phtonview.ui.components.CameraSettingsPanel
import com.phtontools.phtonview.ui.components.ErrorBanner
import com.phtontools.phtonview.ui.components.FocusPeakingProcessor
import com.phtontools.phtonview.ui.components.HistogramView
import com.phtontools.phtonview.ui.components.MeteringOverlay
import com.phtontools.phtonview.ui.components.ParamKind
import com.phtontools.phtonview.ui.components.ParamSelectorSheet
import com.phtontools.phtonview.ui.components.PhotoGallerySheet
import com.phtontools.phtonview.ui.components.CleanBottomControlPanel
import com.phtontools.phtonview.ui.components.ProBottomControlPanel
import com.phtontools.phtonview.ui.components.TopStatusBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    uiMode: UiMode,
    onOpenSettings: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val liveViewFrame by viewModel.liveViewFrame.collectAsStateWithLifecycle()
    val exposure by viewModel.exposureSettings.collectAsStateWithLifecycle()
    val cameraSettings by viewModel.cameraSettings.collectAsStateWithLifecycle()
    val metering by viewModel.meteringResult.collectAsStateWithLifecycle()
    val focusMode by viewModel.focusMode.collectAsStateWithLifecycle()
    val afMode by viewModel.afMode.collectAsStateWithLifecycle()
    val magnification by viewModel.focusMagnification.collectAsStateWithLifecycle()
    val peakingEnabled by viewModel.focusPeakingEnabled.collectAsStateWithLifecycle()
    val histogramType by viewModel.histogramType.collectAsStateWithLifecycle()
    val gridType by viewModel.gridType.collectAsStateWithLifecycle()
    val zebraPattern by viewModel.zebraPattern.collectAsStateWithLifecycle()
    val intervalometer by viewModel.intervalometer.collectAsStateWithLifecycle()
    val bulbSettings by viewModel.bulbSettings.collectAsStateWithLifecycle()
    val timerSettings by viewModel.timerSettings.collectAsStateWithLifecycle()
    val aebSettings by viewModel.aebSettings.collectAsStateWithLifecycle()
    val detectedUsb by viewModel.detectedUsbDevice.collectAsStateWithLifecycle()
    val wifiEnabled by viewModel.wifiExperimental.collectAsStateWithLifecycle()
    val liveViewEnabled by viewModel.liveViewEnabled.collectAsStateWithLifecycle()
    val burstRunning by viewModel.burstRunning.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val photosLoading by viewModel.photosLoading.collectAsStateWithLifecycle()

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selectedParam by remember { mutableStateOf<ParamKind?>(null) }
    var showGallery by remember { mutableStateOf(false) }

    val statusDialogText by viewModel.statusDialogText.collectAsStateWithLifecycle()
    val syncResultMessage by viewModel.syncResultMessage.collectAsStateWithLifecycle()
    val statusScrollState = rememberScrollState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxHeight(),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                CameraSettingsPanel(
                    exposure = exposure,
                    settings = cameraSettings,
                    metering = metering,
                    focusMode = focusMode,
                    afMode = afMode,
                    magnification = magnification,
                    peakingEnabled = peakingEnabled,
                    intervalometer = intervalometer,
                    bulbSettings = bulbSettings,
                    timerSettings = timerSettings,
                    aebSettings = aebSettings,
                    histogramType = histogramType,
                    gridType = gridType,
                    zebraPattern = zebraPattern,
                    liveViewEnabled = liveViewEnabled,
                    onFocusModeChange = viewModel::setFocusMode,
                    onAfModeChange = viewModel::setAfMode,
                    onMagnificationChange = viewModel::setFocusMagnification,
                    onPeakingChange = viewModel::setFocusPeakingEnabled,
                    onMeteringModeChange = viewModel::setMeteringMode,
                    onIsoChange = viewModel::setIso,
                    onApertureChange = viewModel::setAperture,
                    onShutterChange = viewModel::setShutter,
                    onEvChange = viewModel::setEv,
                    onWhiteBalanceChange = viewModel::setWhiteBalance,
                    onFlashModeChange = viewModel::setFlashMode,
                    onFlashCompensationChange = viewModel::setFlashCompensation,
                    onStorageTargetChange = viewModel::setStorageTarget,
                    onHistogramTypeChange = viewModel::setHistogramType,
                    onGridTypeChange = viewModel::setGridType,
                    onZebraPatternChange = viewModel::setZebraPattern,
                    onLiveViewEnabledChange = viewModel::setLiveViewEnabled,
                    onBurst = { viewModel.startBurstCapture(cameraSettings.burstCount) },
                    onBulb = { viewModel.startBulb(bulbSettings.durationSeconds) },
                    onTimer = { viewModel.captureWithTimer(timerSettings.delaySeconds) },
                    onIntervalometer = { viewModel.startIntervalometer(intervalometer) },
                    onAeb = { viewModel.captureAeb(aebSettings) },
                    onApplyPreset = viewModel::applyPreset,
                    onSyncDateTime = viewModel::syncDateTime,
                    onFetchStatus = viewModel::fetchCameraStatus,
                    onBulbChange = { viewModel.setBulbDuration(it.durationSeconds) },
                    onTimerChange = { viewModel.setTimerDelay(it.delaySeconds) },
                    onIntervalometerChange = viewModel::setIntervalometer,
                    onAebChange = viewModel::setAeb,
                    onBurstCountChange = viewModel::setBurstCount,
                    onBurstSpeedChange = viewModel::setBurstSpeed,
                    onResetToDefaults = viewModel::resetToDefaults
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isLandscape = maxWidth > maxHeight
                if (isLandscape) {
                    LandscapeLayout(
                        viewModel = viewModel,
                        uiMode = uiMode,
                        liveViewFrame = liveViewFrame,
                        metering = metering,
                        focusMode = focusMode,
                        connectionState = connectionState,
                        exposure = exposure,
                        cameraSettings = cameraSettings,
                        afMode = afMode,
                        magnification = magnification,
                        peakingEnabled = peakingEnabled,
                        histogramType = histogramType,
                        gridType = gridType,
                        zebraPattern = zebraPattern,
                        intervalometer = intervalometer,
                        bulbSettings = bulbSettings,
                        timerSettings = timerSettings,
                        aebSettings = aebSettings,
                        liveViewEnabled = liveViewEnabled,
                        detectedUsb = detectedUsb,
                        wifiEnabled = wifiEnabled,
                        scale = scale,
                        offset = offset,
                        onScaleChange = { scale = it },
                        onOffsetChange = { offset = it },
                        burstRunning = burstRunning,
                        onOpenGallery = { viewModel.listPhotos(); showGallery = true },
                onOpenSettings = onOpenSettings,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onSelectParam = { selectedParam = it }
            )
        } else {
            PortraitLayout(
                viewModel = viewModel,
                uiMode = uiMode,
                liveViewFrame = liveViewFrame,
                metering = metering,
                focusMode = focusMode,
                connectionState = connectionState,
                exposure = exposure,
                cameraSettings = cameraSettings,
                afMode = afMode,
                magnification = magnification,
                peakingEnabled = peakingEnabled,
                histogramType = histogramType,
                gridType = gridType,
                zebraPattern = zebraPattern,
                intervalometer = intervalometer,
                bulbSettings = bulbSettings,
                timerSettings = timerSettings,
                aebSettings = aebSettings,
                liveViewEnabled = liveViewEnabled,
                detectedUsb = detectedUsb,
                wifiEnabled = wifiEnabled,
                scale = scale,
                offset = offset,
                onScaleChange = { scale = it },
                onOffsetChange = { offset = it },
                burstRunning = burstRunning,
                onOpenGallery = { viewModel.listPhotos(); showGallery = true },
                onOpenSettings = onOpenSettings,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onSelectParam = { selectedParam = it }
            )
        }
    }

            selectedParam?.let { kind ->
                ParamSelectorSheet(
                    kind = kind,
                    exposure = exposure,
                    onDismiss = { selectedParam = null },
                    onIsoChange = viewModel::setIso,
                    onShutterChange = viewModel::setShutter,
                    onEvChange = viewModel::setEv,
                    onApertureChange = viewModel::setAperture
                )
            }

            if (showGallery) {
                PhotoGallerySheet(
                    photos = photos,
                    loading = photosLoading,
                    onDismiss = { showGallery = false },
                    onDownload = { photo, destination ->
                        viewModel.downloadPhotoAwait(photo, destination)
                    }
                )
            }

            statusDialogText?.let { text ->
                AlertDialog(
                    onDismissRequest = viewModel::dismissStatusDialog,
                    title = { Text("相机状态") },
                    text = {
                        Text(
                            text = text,
                            modifier = Modifier.verticalScroll(statusScrollState)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::dismissStatusDialog) {
                            Text("确定")
                        }
                    }
                )
            }

            syncResultMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = viewModel::dismissSyncResultDialog,
                    title = { Text("同步时间结果") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = viewModel::dismissSyncResultDialog) {
                            Text("确定")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PortraitLayout(
    viewModel: CameraViewModel,
    uiMode: UiMode,
    liveViewFrame: Bitmap?,
    metering: MeteringResult,
    focusMode: FocusMode,
    connectionState: ConnectionState,
    exposure: ExposureSettings,
    cameraSettings: CameraSettings,
    afMode: AfMode,
    magnification: Float,
    peakingEnabled: Boolean,
    histogramType: HistogramType,
    gridType: GridType,
    zebraPattern: ZebraPattern,
    intervalometer: IntervalometerSettings,
    bulbSettings: BulbSettings,
    timerSettings: TimerSettings,
    aebSettings: AebSettings,
    liveViewEnabled: Boolean,
    detectedUsb: String?,
    wifiEnabled: Boolean,
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    burstRunning: Boolean,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onSelectParam: (ParamKind) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopStatusBar(
            onOpenSettings = onOpenSettings,
            onOpenMenu = onOpenDrawer,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        ErrorBanner(
            connectionState = connectionState,
            onDismiss = viewModel::clearError
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background)
        ) {
            LiveViewLayer(
                    frame = liveViewFrame,
                    peakingEnabled = peakingEnabled,
                    metering = metering,
                    histogramType = histogramType,
                    connectionState = connectionState,
                    detectedUsb = detectedUsb,
                    scale = scale,
                    offset = offset,
                    magnification = magnification,
                    onScaleChange = onScaleChange,
                    onOffsetChange = onOffsetChange,
                    onTap = { x, y ->
                        if (metering.mode == MeteringMode.Spot) viewModel.setSpotMeteringPoint(x, y)
                        if (focusMode == FocusMode.AF) {
                            viewModel.setAfArea(x, y)
                            viewModel.triggerAf()
                        }
                    }
                )

        }

        if (uiMode == UiMode.PRO) {
            ProBottomControlPanel(
                exposure = exposure,
                metering = metering,
                isLandscape = false,
                peakingEnabled = peakingEnabled,
                gridType = gridType,
                histogramType = histogramType,
                zebraPattern = zebraPattern,
                liveViewEnabled = liveViewEnabled,
                burstRunning = burstRunning,
                bulbEnabled = bulbSettings.enabled,
                onSelectParam = onSelectParam,
                onCapture = { viewModel.captureImage() },
                onOpenGallery = onOpenGallery,
                onMeteringModeChange = viewModel::setMeteringMode,
                onTogglePeaking = { viewModel.setFocusPeakingEnabled(!peakingEnabled) },
                onToggleGrid = { viewModel.setGridType(if (gridType == GridType.None) GridType.RuleOfThirds else GridType.None) },
                onToggleHistogram = { viewModel.setHistogramType(if (histogramType == HistogramType.None) HistogramType.Luminance else HistogramType.None) },
                onToggleZebra = { viewModel.setZebraPattern(if (zebraPattern == ZebraPattern.None) ZebraPattern.Over else ZebraPattern.None) },
                onToggleLiveView = { viewModel.setLiveViewEnabled(!liveViewEnabled) },
                onBurst = { if (!burstRunning) viewModel.startBurstCapture(cameraSettings.burstCount) },
                onBulb = { if (bulbSettings.enabled) viewModel.stopBulb() else viewModel.startBulb(bulbSettings.durationSeconds) }
            )
        } else {
            CleanBottomControlPanel(
                exposure = exposure,
                metering = metering,
                isLandscape = false,
                onSelectParam = onSelectParam,
                onCapture = { viewModel.captureImage() },
                onOpenGallery = onOpenGallery,
                onMeteringModeChange = viewModel::setMeteringMode
            )
        }
    }
}

@Composable
private fun LandscapeLayout(
    viewModel: CameraViewModel,
    uiMode: UiMode,
    liveViewFrame: Bitmap?,
    metering: MeteringResult,
    focusMode: FocusMode,
    connectionState: ConnectionState,
    exposure: ExposureSettings,
    cameraSettings: CameraSettings,
    afMode: AfMode,
    magnification: Float,
    peakingEnabled: Boolean,
    histogramType: HistogramType,
    gridType: GridType,
    zebraPattern: ZebraPattern,
    intervalometer: IntervalometerSettings,
    bulbSettings: BulbSettings,
    timerSettings: TimerSettings,
    aebSettings: AebSettings,
    liveViewEnabled: Boolean,
    detectedUsb: String?,
    wifiEnabled: Boolean,
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    burstRunning: Boolean,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onSelectParam: (ParamKind) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            TopStatusBar(
                onOpenSettings = onOpenSettings,
                onOpenMenu = onOpenDrawer,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            ErrorBanner(
                connectionState = connectionState,
                onDismiss = viewModel::clearError
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                LiveViewLayer(
                    frame = liveViewFrame,
                    peakingEnabled = peakingEnabled,
                    metering = metering,
                    histogramType = histogramType,
                    connectionState = connectionState,
                    detectedUsb = detectedUsb,
                    scale = scale,
                    offset = offset,
                    magnification = magnification,
                    onScaleChange = onScaleChange,
                    onOffsetChange = onOffsetChange,
                    onTap = { x, y ->
                        if (metering.mode == MeteringMode.Spot) viewModel.setSpotMeteringPoint(x, y)
                        if (focusMode == FocusMode.AF) {
                            viewModel.setAfArea(x, y)
                            viewModel.triggerAf()
                        }
                    }
                )

            }
        }

        if (uiMode == UiMode.PRO) {
            ProBottomControlPanel(
                exposure = exposure,
                metering = metering,
                isLandscape = true,
                peakingEnabled = peakingEnabled,
                gridType = gridType,
                histogramType = histogramType,
                zebraPattern = zebraPattern,
                liveViewEnabled = liveViewEnabled,
                burstRunning = burstRunning,
                bulbEnabled = bulbSettings.enabled,
                onSelectParam = onSelectParam,
                onCapture = { viewModel.captureImage() },
                onOpenGallery = onOpenGallery,
                onMeteringModeChange = viewModel::setMeteringMode,
                onTogglePeaking = { viewModel.setFocusPeakingEnabled(!peakingEnabled) },
                onToggleGrid = { viewModel.setGridType(if (gridType == GridType.None) GridType.RuleOfThirds else GridType.None) },
                onToggleHistogram = { viewModel.setHistogramType(if (histogramType == HistogramType.None) HistogramType.Luminance else HistogramType.None) },
                onToggleZebra = { viewModel.setZebraPattern(if (zebraPattern == ZebraPattern.None) ZebraPattern.Over else ZebraPattern.None) },
                onToggleLiveView = { viewModel.setLiveViewEnabled(!liveViewEnabled) },
                onBurst = { if (!burstRunning) viewModel.startBurstCapture(cameraSettings.burstCount) },
                onBulb = { if (bulbSettings.enabled) viewModel.stopBulb() else viewModel.startBulb(bulbSettings.durationSeconds) }
            )
        } else {
            CleanBottomControlPanel(
                exposure = exposure,
                metering = metering,
                isLandscape = true,
                onSelectParam = onSelectParam,
                onCapture = { viewModel.captureImage() },
                onOpenGallery = onOpenGallery,
                onMeteringModeChange = viewModel::setMeteringMode
            )
        }
    }
}

@Composable
private fun LiveViewLayer(
    frame: Bitmap?,
    peakingEnabled: Boolean,
    metering: MeteringResult,
    histogramType: HistogramType,
    connectionState: ConnectionState,
    detectedUsb: String?,
    scale: Float,
    offset: Offset,
    magnification: Float,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    onTap: (Float, Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    var processedFrame by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(frame, peakingEnabled) {
        val source = frame ?: return@LaunchedEffect
        if (!peakingEnabled) {
            processedFrame = source
            return@LaunchedEffect
        }
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                FocusPeakingProcessor.apply(source)
            }
            processedFrame = result
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange * magnification).coerceIn(1f, 8f)
        onScaleChange(newScale)
        onOffsetChange(offset + panChange)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .transformable(state = transformState)
            .pointerInput(Unit) {
                detectTapGestures { offsetPos ->
                    val x = (offsetPos.x / size.width).coerceIn(0f, 1f)
                    val y = (offsetPos.y / size.height).coerceIn(0f, 1f)
                    onTap(x, y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        processedFrame?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(id = R.string.live_view),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
        } ?: NoSignalMessage(
            connectionState = connectionState,
            detectedUsb = detectedUsb
        )

        MeteringOverlay(metering = metering)

        AnimatedVisibility(
            visible = histogramType != HistogramType.None,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(120.dp, 80.dp)
                .background(
                    MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                    CircleShape
                )
        ) {
            // HistogramView is placeholder until data is wired
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NoSignalMessage(
    connectionState: ConnectionState,
    detectedUsb: String?
) {
    val message = when {
        connectionState is ConnectionState.Connected -> stringResource(id = R.string.status_connected, connectionState.model)
        detectedUsb != null -> stringResource(id = R.string.camera_connected)
        else -> stringResource(id = R.string.waiting_for_camera)
    }
    val showProgress = connectionState !is ConnectionState.Connected && detectedUsb == null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}
