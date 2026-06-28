package com.phtontools.phtonview.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import com.phtontools.phtonview.ui.components.ErrorBanner
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phtontools.phtonview.R
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
import com.phtontools.phtonview.data.model.ZebraPattern
import com.phtontools.phtonview.data.local.UiMode
import com.phtontools.phtonview.ui.components.BottomControlPanel
import com.phtontools.phtonview.ui.components.CameraSettingsPanel
import com.phtontools.phtonview.ui.components.ConnectionHintBanner
import com.phtontools.phtonview.ui.components.FocusPeakingProcessor
import com.phtontools.phtonview.ui.components.MeteringOverlay
import com.phtontools.phtonview.ui.components.SimpleControlPanel
import com.phtontools.phtonview.ui.components.TopStatusBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
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

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val bgColor = MaterialTheme.colorScheme.background

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
                    intervalometer = intervalometer,
                    bulbSettings = bulbSettings,
                    timerSettings = timerSettings,
                    aebSettings = aebSettings,
                    histogramType = histogramType,
                    gridType = gridType,
                    zebraPattern = zebraPattern,
                    onIsoChange = viewModel::setIso,
                    onApertureChange = viewModel::setAperture,
                    onShutterChange = viewModel::setShutter,
                    onEvChange = viewModel::setEv,
                    onImageFormatChange = viewModel::setImageFormat,
                    onImageSizeChange = viewModel::setImageSize,
                    onBurstSpeedChange = viewModel::setBurstSpeed,
                    onShootingModeChange = viewModel::setShootingMode,
                    onWhiteBalanceChange = viewModel::setWhiteBalance,
                    onFlashModeChange = viewModel::setFlashMode,
                    onFlashCompensationChange = viewModel::setFlashCompensation,
                    onStorageTargetChange = viewModel::setStorageTarget,
                    onHistogramTypeChange = viewModel::setHistogramType,
                    onGridTypeChange = viewModel::setGridType,
                    onZebraPatternChange = viewModel::setZebraPattern,
                    onIntervalometerChange = { viewModel.setIntervalometer(it) },
                    onBulbChange = { viewModel.setBulbDuration(it.durationSeconds) },
                    onTimerChange = { viewModel.setTimerDelay(it.delaySeconds) },
                    onAebChange = { viewModel.setAeb(it) },
                    onApplyPreset = viewModel::applyPreset,
                    onSyncDateTime = viewModel::syncDateTime,
                    onFetchStatus = viewModel::fetchCameraStatus
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .safeDrawingPadding()
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isLandscape = maxWidth > maxHeight
                if (isLandscape) {
                    LandscapeLayout(
                        viewModel = viewModel,
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
                        scale = scale,
                        offset = offset,
                        onScaleChange = { scale = it },
                        onOffsetChange = { offset = it },
                        onOpenSettings = onOpenSettings,
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                } else {
                    PortraitLayout(
                        viewModel = viewModel,
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
                        scale = scale,
                        offset = offset,
                        onScaleChange = { scale = it },
                        onOffsetChange = { offset = it },
                        onOpenSettings = onOpenSettings,
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitLayout(
    viewModel: CameraViewModel,
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
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    val wifiEnabled by viewModel.wifiExperimental.collectAsStateWithLifecycle()
    val detectedUsb by viewModel.detectedUsbDevice.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopStatusBar(
            brand = cameraSettings.brand,
            connectionType = cameraSettings.connectionType,
            connectionState = connectionState,
            metering = metering,
            exposure = exposure,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        ErrorBanner(
            connectionState = connectionState,
            onDismiss = viewModel::clearError
        )

        ConnectionHintBanner(
            connectionState = connectionState,
            detectedUsbDevice = detectedUsb,
            wifiExperimental = wifiEnabled,
            onPairWifi = { address ->
                viewModel.setConnectionType(ConnectionType.WiFi)
                viewModel.pairWifi(address)
                viewModel.connect()
            },
            onSwitchToUsb = {
                viewModel.setConnectionType(ConnectionType.USB)
                viewModel.connect()
            }
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

            FloatingMenu(
                onOpenSettings = onOpenSettings,
                onOpenDrawer = onOpenDrawer,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }

        val uiMode by viewModel.uiMode.collectAsStateWithLifecycle()
        if (uiMode == UiMode.PRO) {
            BottomControlPanel(
                focusMode = focusMode,
                afMode = afMode,
                magnification = magnification,
                peakingEnabled = peakingEnabled,
                exposure = exposure,
                metering = metering,
                isLandscape = false,
                onFocusModeChange = viewModel::setFocusMode,
                onAfModeChange = viewModel::setAfMode,
                onMagnificationChange = viewModel::setFocusMagnification,
                onPeakingChange = viewModel::setFocusPeakingEnabled,
                onMeteringModeChange = viewModel::setMeteringMode,
                onExposureChange = { a, s, i, e -> viewModel.setExposure(a, s, i, e) },
                onCapture = { viewModel.captureImage() },
                onBurst = { viewModel.startBurstCapture(cameraSettings.burstCount) },
                onBulb = { viewModel.startBulb(bulbSettings.durationSeconds) },
                onTimer = { viewModel.captureWithTimer(timerSettings.delaySeconds) },
                onIntervalometer = { viewModel.startIntervalometer(intervalometer) },
                onAeb = { viewModel.captureAeb(aebSettings) }
            )
        } else {
            SimpleControlPanel(
                focusMode = focusMode,
                exposure = exposure,
                isLandscape = false,
                onFocusModeChange = viewModel::setFocusMode,
                onIsoChange = { viewModel.setIso(it) },
                onApertureChange = { viewModel.setAperture(it) },
                onShutterChange = { viewModel.setShutter(it) },
                onEvChange = { viewModel.setExposure(null, null, null, it) },
                onCapture = { viewModel.captureImage() }
            )
        }
    }
}

@Composable
private fun LandscapeLayout(
    viewModel: CameraViewModel,
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
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    val wifiEnabled by viewModel.wifiExperimental.collectAsStateWithLifecycle()
    val detectedUsb by viewModel.detectedUsbDevice.collectAsStateWithLifecycle()
    val uiMode by viewModel.uiMode.collectAsStateWithLifecycle()

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            TopStatusBar(
                brand = cameraSettings.brand,
                connectionType = cameraSettings.connectionType,
                connectionState = connectionState,
                metering = metering,
                exposure = exposure,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            ErrorBanner(
                connectionState = connectionState,
                onDismiss = viewModel::clearError
            )

            ConnectionHintBanner(
                connectionState = connectionState,
                detectedUsbDevice = detectedUsb,
                wifiExperimental = wifiEnabled,
                onPairWifi = { address ->
                    viewModel.setConnectionType(ConnectionType.WiFi)
                    viewModel.pairWifi(address)
                    viewModel.connect()
                },
                onSwitchToUsb = {
                    viewModel.setConnectionType(ConnectionType.USB)
                    viewModel.connect()
                }
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

                FloatingMenu(
                    onOpenSettings = onOpenSettings,
                    onOpenDrawer = onOpenDrawer,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }

        if (uiMode == UiMode.PRO) {
            BottomControlPanel(
                focusMode = focusMode,
                afMode = afMode,
                magnification = magnification,
                peakingEnabled = peakingEnabled,
                exposure = exposure,
                metering = metering,
                isLandscape = true,
                onFocusModeChange = viewModel::setFocusMode,
                onAfModeChange = viewModel::setAfMode,
                onMagnificationChange = viewModel::setFocusMagnification,
                onPeakingChange = viewModel::setFocusPeakingEnabled,
                onMeteringModeChange = viewModel::setMeteringMode,
                onExposureChange = { a, s, i, e -> viewModel.setExposure(a, s, i, e) },
                onCapture = { viewModel.captureImage() },
                onBurst = { viewModel.startBurstCapture(cameraSettings.burstCount) },
                onBulb = { viewModel.startBulb(bulbSettings.durationSeconds) },
                onTimer = { viewModel.captureWithTimer(timerSettings.delaySeconds) },
                onIntervalometer = { viewModel.startIntervalometer(intervalometer) },
                onAeb = { viewModel.captureAeb(aebSettings) }
            )
        } else {
            SimpleControlPanel(
                focusMode = focusMode,
                exposure = exposure,
                isLandscape = true,
                onFocusModeChange = viewModel::setFocusMode,
                onIsoChange = { viewModel.setIso(it) },
                onApertureChange = { viewModel.setAperture(it) },
                onShutterChange = { viewModel.setShutter(it) },
                onEvChange = { viewModel.setExposure(null, null, null, it) },
                onCapture = { viewModel.captureImage() }
            )
        }
    }
}

@Composable
private fun FloatingMenu(
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FloatingActionButton(
            onClick = onOpenDrawer,
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = stringResource(id = R.string.capture_settings),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
        FloatingActionButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(id = R.string.settings),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun LiveViewLayer(
    frame: Bitmap?,
    peakingEnabled: Boolean,
    metering: MeteringResult,
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
        } ?: NoSignalMessage()

        MeteringOverlay(metering = metering)
    }
}

@Composable
private fun NoSignalMessage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(id = R.string.waiting_for_camera),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}
