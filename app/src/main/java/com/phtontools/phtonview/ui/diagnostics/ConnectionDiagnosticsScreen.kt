package com.phtontools.phtonview.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phtontools.phtonview.data.model.ConnectionState
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.ui.CameraViewModel

/**
 * 连接诊断屏（迭代 #9）。
 *
 * 排错快：所有可读 state 一次性展示给用户与开发者。
 * 只读不写（仅收集 StateFlow），不动 PTP 协议层。
 *
 * 适用场景：
 * - USB 设备 attach 但 listPhotos 失败
 * - 频繁连接不上（心跳失败）的根因定位
 * - 提交 issue 时附带的设备指纹
 */
@Composable
fun ConnectionDiagnosticsScreen(
    viewModel: CameraViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val detectedUsb by viewModel.detectedUsbDevice.collectAsStateWithLifecycle()
    val cameraStatus by viewModel.cameraStatus.collectAsStateWithLifecycle()
    val cameraSettings by viewModel.cameraSettings.collectAsStateWithLifecycle()
    val focusMode by viewModel.focusMode.collectAsStateWithLifecycle()
    val afMode by viewModel.afMode.collectAsStateWithLifecycle()
    val afAreaMode by viewModel.afAreaMode.collectAsStateWithLifecycle()
    val liveViewEnabled by viewModel.liveViewEnabled.collectAsStateWithLifecycle()
    val burstRunning by viewModel.burstRunning.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DiagCard("Connection") {
            DiagRow("State", renderConnectionState(connectionState))
            DiagRow("Type", cameraSettings.connectionType.name)
            DiagRow("Detected device", detectedUsb ?: "—")
        }
        DiagCard("Camera status") {
            DiagRow("Battery", if (cameraStatus.batteryLevel < 0) "—" else "${cameraStatus.batteryLevel}%")
            DiagRow("Storage remaining", if (cameraStatus.storageRemaining < 0) "—" else "${cameraStatus.storageRemaining} MB")
            DiagRow("Shots remaining", if (cameraStatus.shotsRemaining < 0) "—" else cameraStatus.shotsRemaining.toString())
            DiagRow("Shutter count", if (cameraStatus.shutterCount < 0) "—" else cameraStatus.shutterCount.toString())
            DiagRow("Temperature", if (cameraStatus.temperatureCelsius < 0) "—" else "${cameraStatus.temperatureCelsius}°C")
            DiagRow("Firmware", cameraStatus.firmwareVersion)
            DiagRow("Manufacturer", cameraStatus.manufacturer)
        }
        DiagCard("Camera settings") {
            DiagRow("Brand", cameraSettings.brand.name)
            DiagRow("Shooting mode", cameraSettings.shootingMode.name)
            DiagRow("Image format", cameraSettings.imageFormat.name)
            DiagRow("Image size", cameraSettings.imageSize.name)
            DiagRow("Burst speed", "${cameraSettings.burstSpeed.name} (${cameraSettings.burstSpeed.framesPerSecond} fps)")
            DiagRow("Burst count", cameraSettings.burstCount.toString())
            DiagRow("White balance", cameraSettings.whiteBalance.name)
            DiagRow("Flash mode", cameraSettings.flashMode.name)
            DiagRow("Storage target", cameraSettings.storageTarget.name)
        }
        DiagCard("Focus") {
            DiagRow("Focus mode", focusMode.name)
            DiagRow("AF mode", afMode.name)
            DiagRow("AF area", afAreaMode.name)
        }
        DiagCard("Runtime") {
            DiagRow("LiveView enabled", liveViewEnabled.toString())
            DiagRow("Burst running", burstRunning.toString())
            DiagRow("Photos in cache", photos.size.toString())
        }
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun renderConnectionState(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Connecting -> "Connecting..."
    is ConnectionState.Connected -> "Connected (${state.model})"
    is ConnectionState.Error -> "Error: ${state.message}"
}
