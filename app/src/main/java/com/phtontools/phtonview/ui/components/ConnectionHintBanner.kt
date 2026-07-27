package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.phtontools.phtonview.R
import com.phtontools.phtonview.connection.WifiCameraDiscovery
import com.phtontools.phtonview.data.model.ConnectionState
import com.phtontools.phtonview.data.model.ConnectionType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionHintBanner(
    connectionState: ConnectionState,
    detectedUsbDevice: String?,
    onPairWifi: (String) -> Unit,
    onSwitchToUsb: () -> Unit,
    onStartWifiScan: () -> Unit = {},
    onStopWifiScan: () -> Unit = {},
    onConnectWifiService: (WifiCameraDiscovery.CameraServiceInfo) -> Unit = {},
    discoveredWifiServices: List<WifiCameraDiscovery.CameraServiceInfo> = emptyList(),
    wifiScanProgress: WifiCameraDiscovery.ScanProgress = WifiCameraDiscovery.ScanProgress.IDLE,
    onConnect: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showWifiDialog by remember { mutableStateOf(false) }
    var showUsbGuide by remember { mutableStateOf(false) }

    if (connectionState is ConnectionState.Connected) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResource(id = R.string.connection_hint_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!detectedUsbDevice.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable { onSwitchToUsb() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.usb_device_detected, detectedUsbDevice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(id = R.string.tap_to_connect_usb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            androidx.compose.material3.OutlinedButton(
                onClick = { showUsbGuide = true }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp).size(16.dp)
                )
                Text(
                    text = stringResource(id = R.string.usb_connection_guide),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = { showWifiDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Default.NetworkWifi,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    text = stringResource(id = R.string.wifi_pair),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showWifiDialog) {
        WifiAutoScanDialog(
            onDismiss = {
                onStopWifiScan()
                showWifiDialog = false
            },
            onPairWifi = onPairWifi,
            onStartScan = onStartWifiScan,
            onStopScan = onStopWifiScan,
            onConnectService = {
                onStopWifiScan()
                showWifiDialog = false
                onConnectWifiService(it)
            },
            discoveredServices = discoveredWifiServices,
            scanProgress = wifiScanProgress,
            onConnect = onConnect
        )
    }

    if (showUsbGuide) {
        UsbGuideDialog(onDismiss = { showUsbGuide = false })
    }
}

/**
 * WiFi 自动扫描对话框：自动轮询 mDNS + 子网端口扫描，
 * 展示发现的相机列表，点击列表项即配对并连接。
 * 保留手动输入作为 fallback。
 */
@Composable
private fun WifiAutoScanDialog(
    onDismiss: () -> Unit,
    onPairWifi: (String) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectService: (WifiCameraDiscovery.CameraServiceInfo) -> Unit,
    discoveredServices: List<WifiCameraDiscovery.CameraServiceInfo>,
    scanProgress: WifiCameraDiscovery.ScanProgress,
    onConnect: () -> Unit
) {
    var showManual by remember { mutableStateOf(false) }
    var manualAddress by remember { mutableStateOf("") }

    // 对话框打开即开始自动轮询；用户关闭或选中设备时停止。
    LaunchedEffect(Unit) {
        onStartScan()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(16.dp)
                )
                .padding(20.dp)
                .heightIn(max = 560.dp)
        ) {
            Text(
                text = stringResource(id = R.string.wifi_auto_scan_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(id = R.string.wifi_auto_scan_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )

            // 扫描状态行
            ScanStatusRow(
                progress = scanProgress,
                foundCount = discoveredServices.size,
                onRescan = onStartScan,
                onToggleManual = { showManual = !showManual }
            )

            // 已发现列表
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 280.dp)
                    .padding(top = 8.dp)
            ) {
                if (discoveredServices.isEmpty()) {
                    EmptyScanPlaceholder(progress = scanProgress)
                } else {
                    DiscoveredList(
                        services = discoveredServices,
                        onClick = onConnectService
                    )
                }
            }

            // 手动输入 fallback
            if (showManual) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = manualAddress,
                        onValueChange = { manualAddress = it },
                        label = { Text(stringResource(id = R.string.wifi_address_label)) },
                        placeholder = { Text("192.168.1.1:15740") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // 底部按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = android.R.string.cancel))
                }
                if (showManual) {
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            if (manualAddress.isNotBlank()) {
                                onPairWifi(manualAddress)
                                onDismiss()
                                onConnect()
                            }
                        }
                    ) {
                        Text(stringResource(id = android.R.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanStatusRow(
    progress: WifiCameraDiscovery.ScanProgress,
    foundCount: Int,
    onRescan: () -> Unit,
    onToggleManual: () -> Unit
) {
    val scanning = progress == WifiCameraDiscovery.ScanProgress.SCANNING_MDNS ||
            progress == WifiCameraDiscovery.ScanProgress.SCANNING_PORTS
    val (statusText, isError) = when (progress) {
        WifiCameraDiscovery.ScanProgress.IDLE -> stringResource(id = R.string.wifi_scan_idle) to false
        WifiCameraDiscovery.ScanProgress.SCANNING_MDNS ->
            stringResource(id = R.string.wifi_scan_mdns) to false
        WifiCameraDiscovery.ScanProgress.SCANNING_PORTS ->
            stringResource(id = R.string.wifi_scan_ports, foundCount) to false
        WifiCameraDiscovery.ScanProgress.DONE ->
            stringResource(id = R.string.wifi_scan_done, foundCount) to false
        WifiCameraDiscovery.ScanProgress.FAILED ->
            stringResource(id = R.string.wifi_scan_failed) to true
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onToggleManual) {
                Text(
                    text = stringResource(id = R.string.wifi_manual),
                    fontSize = 12.sp
                )
            }
            TextButton(onClick = onRescan, enabled = !scanning) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(id = R.string.wifi_rescan),
                    fontSize = 12.sp
                )
            }
        }
        if (scanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

@Composable
private fun EmptyScanPlaceholder(progress: WifiCameraDiscovery.ScanProgress) {
    val message = when (progress) {
        WifiCameraDiscovery.ScanProgress.IDLE -> stringResource(id = R.string.wifi_scan_starting)
        WifiCameraDiscovery.ScanProgress.SCANNING_MDNS -> stringResource(id = R.string.wifi_scan_searching)
        WifiCameraDiscovery.ScanProgress.SCANNING_PORTS -> stringResource(id = R.string.wifi_scan_searching)
        WifiCameraDiscovery.ScanProgress.DONE -> stringResource(id = R.string.wifi_scan_empty_done)
        WifiCameraDiscovery.ScanProgress.FAILED -> stringResource(id = R.string.wifi_scan_empty_failed)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun DiscoveredList(
    services: List<WifiCameraDiscovery.CameraServiceInfo>,
    onClick: (WifiCameraDiscovery.CameraServiceInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        services.forEach { service ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onClick(service) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NetworkWifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${service.host}:${service.port}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = service.vendorHint ?: service.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbGuideDialog(
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(16.dp)
                )
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(id = R.string.usb_guide_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Column(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(id = R.string.usb_guide_step_1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = stringResource(id = R.string.usb_guide_step_2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = stringResource(id = R.string.usb_guide_step_3),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = stringResource(id = R.string.usb_guide_step_4),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = stringResource(id = R.string.usb_guide_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = android.R.string.ok))
                }
            }
        }
    }
}
