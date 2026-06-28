package com.phtontools.phtonview.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phtontools.phtonview.BuildConfig
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.local.AppLanguage
import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.data.local.ThemeMode
import com.phtontools.phtonview.data.local.UiMode
import com.phtontools.phtonview.data.model.ConnectionState
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.ui.CameraViewModel
import com.phtontools.phtonview.util.AppLogger
import com.phtontools.phtonview.util.UpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val cameraSettings by viewModel.cameraSettings.collectAsStateWithLifecycle()
    val detectedUsb by viewModel.detectedUsbDevice.collectAsStateWithLifecycle()
    val wifiEnabled by viewModel.wifiExperimental.collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showUiModeDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showEasterEgg by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var currentTheme by remember { mutableStateOf(settingsManager.themeMode) }
    var currentLanguage by remember { mutableStateOf(settingsManager.language) }
    var currentUiMode by remember { mutableStateOf(settingsManager.uiMode) }
    var debugMode by remember { mutableStateOf(settingsManager.debugMode) }
    var wifiExperimental by remember { mutableStateOf(settingsManager.wifiExperimental) }
    var pendingRelease by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var easterEggClicks by remember { mutableStateOf(0) }

    val checkUpdate: () -> Unit = {
        scope.launch {
            val release = UpdateChecker.fetchLatestRelease()
            if (release == null) {
                android.widget.Toast.makeText(context, "暂无发行版或网络异常", android.widget.Toast.LENGTH_SHORT).show()
            } else if (!UpdateChecker.isNewer(UpdateChecker.getCurrentVersion(), release.version)) {
                android.widget.Toast.makeText(context, "当前已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                pendingRelease = release
                showUpdateDialog = true
            }
        }
    }

    val onVersionClick: () -> Unit = {
        easterEggClicks += 1
        if (easterEggClicks >= 5) {
            easterEggClicks = 0
            showEasterEgg = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsHeader(title = stringResource(id = R.string.appearance))
            SettingsItem(
                icon = Icons.Default.Palette,
                title = stringResource(id = R.string.theme),
                summary = themeLabel(currentTheme),
                onClick = { showThemeDialog = true }
            )
            SettingsItem(
                icon = Icons.Default.Language,
                title = stringResource(id = R.string.language),
                summary = languageLabel(currentLanguage),
                onClick = { showLanguageDialog = true }
            )

            SettingsHeader(title = stringResource(id = R.string.camera))
            SettingsItem(
                icon = Icons.Default.CameraAlt,
                title = stringResource(id = R.string.select_ui_mode),
                summary = uiModeLabel(currentUiMode),
                onClick = { showUiModeDialog = true }
            )
            ConnectionSection(
                connectionState = connectionState,
                connectionType = cameraSettings.connectionType,
                wifiExperimental = wifiEnabled,
                detectedUsb = detectedUsb,
                onConnectionTypeChange = {
                    viewModel.setConnectionType(it)
                    viewModel.connect()
                },
                onConnect = { viewModel.connect() },
                onDisconnect = { viewModel.disconnect() },
                onPairWifi = { address ->
                    viewModel.setConnectionType(ConnectionType.WiFi)
                    viewModel.pairWifi(address)
                    viewModel.connect()
                }
            )

            SettingsHeader(title = stringResource(id = R.string.general))
            SettingsSwitchItem(
                icon = Icons.Default.Wifi,
                title = stringResource(id = R.string.wifi_experimental),
                summary = stringResource(id = R.string.wifi_experimental_summary),
                checked = wifiExperimental,
                onCheckedChange = {
                    wifiExperimental = it
                    settingsManager.wifiExperimental = it
                }
            )
            SettingsSwitchItem(
                icon = Icons.Default.BugReport,
                title = stringResource(id = R.string.debug_mode),
                summary = stringResource(id = R.string.debug_mode_summary),
                checked = debugMode,
                onCheckedChange = {
                    debugMode = it
                    settingsManager.debugMode = it
                    AppLogger.debugEnabled = it
                }
            )
            SettingsItem(
                icon = Icons.Default.Update,
                title = stringResource(id = R.string.check_update),
                summary = stringResource(id = R.string.latest_version),
                onClick = checkUpdate
            )

            SettingsHeader(title = stringResource(id = R.string.about))
            SettingsItem(
                icon = Icons.Default.Info,
                title = stringResource(id = R.string.app_name),
                summary = String.format(stringResource(id = R.string.version_format), BuildConfig.VERSION_NAME) +
                        " · lanche-furry",
                onClick = onVersionClick
            )
            SettingsItem(
                icon = Icons.Default.People,
                title = stringResource(id = R.string.credits),
                summary = "",
                onClick = { showCreditsDialog = true }
            )
        }
    }

    if (showThemeDialog) {
        ThemeDialog(
            currentTheme = currentTheme,
            onThemeSelected = {
                currentTheme = it
                settingsManager.themeMode = it
                activity?.recreate()
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showLanguageDialog) {
        LanguageDialog(
            currentLanguage = currentLanguage,
            onLanguageSelected = {
                currentLanguage = it
                settingsManager.language = it
                activity?.recreate()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showUiModeDialog) {
        UiModeDialog(
            currentMode = currentUiMode,
            onModeSelected = {
                currentUiMode = it
                settingsManager.uiMode = it
            },
            onDismiss = { showUiModeDialog = false }
        )
    }

    if (showUpdateDialog) {
        UpdateDialog(
            release = pendingRelease,
            onConfirm = {
                showUpdateDialog = false
                pendingRelease?.let { release ->
                    if (UpdateChecker.canInstallUpdate(context)) {
                        UpdateChecker.downloadAndInstall(context, release)
                    } else {
                        UpdateChecker.requestInstallPermission(context)
                    }
                }
            },
            onDismiss = { showUpdateDialog = false }
        )
    }

    if (showEasterEgg) {
        EasterEggDialog(onDismiss = { showEasterEgg = false })
    }

    if (showCreditsDialog) {
        CreditsDialog(onDismiss = { showCreditsDialog = false })
    }
}

@Composable
private fun SettingsHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

@Composable
private fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Column(
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

@Composable
private fun ThemeDialog(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.theme)) },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onThemeSelected(mode)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = currentTheme == mode,
                            onClick = {
                                onThemeSelected(mode)
                                onDismiss()
                            }
                        )
                        Text(
                            text = themeLabel(mode),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.ok))
            }
        }
    )
}

@Composable
private fun LanguageDialog(
    currentLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.language)) },
        text = {
            Column {
                AppLanguage.entries.forEach { lang ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onLanguageSelected(lang)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = currentLanguage == lang,
                            onClick = {
                                onLanguageSelected(lang)
                                onDismiss()
                            }
                        )
                        Text(
                            text = languageLabel(lang),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.ok))
            }
        }
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String {
    return stringResource(
        id = when (mode) {
            ThemeMode.SYSTEM -> R.string.theme_system
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.DARK -> R.string.theme_dark
        }
    )
}

@Composable
private fun UiModeDialog(
    currentMode: UiMode,
    onModeSelected: (UiMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.select_ui_mode)) },
        text = {
            Column {
                UiMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onModeSelected(mode)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = {
                                onModeSelected(mode)
                                onDismiss()
                            }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = uiModeLabel(mode))
                            Text(
                                text = uiModeSummary(mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.ok))
            }
        }
    )
}

@Composable
private fun uiModeLabel(mode: UiMode): String {
    return stringResource(
        id = when (mode) {
            UiMode.SIMPLE -> R.string.ui_mode_simple
            UiMode.PRO -> R.string.ui_mode_pro
        }
    )
}

@Composable
private fun uiModeSummary(mode: UiMode): String {
    return stringResource(
        id = when (mode) {
            UiMode.SIMPLE -> R.string.ui_mode_simple_desc
            UiMode.PRO -> R.string.ui_mode_pro_desc
        }
    )
}



@Composable
private fun languageLabel(language: AppLanguage): String {
    return stringResource(
        id = when (language) {
            AppLanguage.SYSTEM -> R.string.system_default
            AppLanguage.ENGLISH -> R.string.language_english
            AppLanguage.CHINESE -> R.string.language_chinese
            AppLanguage.JAPANESE -> R.string.language_japanese
            AppLanguage.KOREAN -> R.string.language_korean
            AppLanguage.FRENCH -> R.string.language_french
            AppLanguage.GERMAN -> R.string.language_german
            AppLanguage.SPANISH -> R.string.language_spanish
            AppLanguage.RUSSIAN -> R.string.language_russian
        }
    )
}

@Composable
private fun UpdateDialog(
    release: UpdateChecker.ReleaseInfo?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (release == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本") },
        text = {
            Column {
                Text(
                    text = "最新版本：${release.version}",
                    style = MaterialTheme.typography.titleMedium
                )
                if (release.body.isNotBlank()) {
                    Text(
                        text = release.body,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("下载并安装")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun EasterEggDialog(
    onDismiss: () -> Unit
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = tween(2000, easing = LinearEasing)
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("彩蛋") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .graphicsLayer { rotationZ = rotation.value },
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "PhtonView 由 lanche-furry 出品\n感谢使用！",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.ok))
            }
        }
    )
}

@Composable
private fun CreditsDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.credits)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "澜澈LanChe",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "程序制作",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                HorizontalDivider()
                Text(
                    text = "安信一・プロス（一桶）",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "UI设计",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.ok))
            }
        }
    )
}

@Composable
private fun ConnectionSection(
    connectionState: ConnectionState,
    connectionType: ConnectionType,
    wifiExperimental: Boolean,
    detectedUsb: String?,
    onConnectionTypeChange: (ConnectionType) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPairWifi: (String) -> Unit
) {
    var wifiAddress by remember { mutableStateOf("") }
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(id = R.string.connection),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (connectionState) {
                    is ConnectionState.Connected -> stringResource(id = R.string.status_connected, connectionState.model)
                    is ConnectionState.Connecting -> stringResource(id = R.string.status_connecting)
                    is ConnectionState.Error -> stringResource(id = R.string.status_error, connectionState.message)
                    is ConnectionState.Disconnected -> stringResource(id = R.string.status_disconnected)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (isConnected) {
                TextButton(onClick = onDisconnect) {
                    Text(stringResource(id = R.string.disconnect))
                }
            } else {
                TextButton(
                    onClick = onConnect,
                    enabled = !isConnecting
                ) {
                    Text(
                        if (isConnecting) stringResource(id = R.string.status_connecting)
                        else stringResource(id = R.string.connect)
                    )
                }
            }
        }

        // USB / WiFi 切换
        if (wifiExperimental) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConnectionChip(
                    label = stringResource(id = R.string.usb),
                    selected = connectionType == ConnectionType.USB,
                    onClick = { onConnectionTypeChange(ConnectionType.USB) },
                    modifier = Modifier.weight(1f)
                )
                ConnectionChip(
                    label = stringResource(id = R.string.wifi),
                    selected = connectionType == ConnectionType.WiFi,
                    onClick = { onConnectionTypeChange(ConnectionType.WiFi) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // WiFi 配对输入
        if (wifiExperimental && connectionType == ConnectionType.WiFi) {
            androidx.compose.material3.OutlinedTextField(
                value = wifiAddress,
                onValueChange = { wifiAddress = it },
                label = { Text(stringResource(id = R.string.wifi_address_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true,
                trailingIcon = {
                    TextButton(
                        onClick = { if (wifiAddress.isNotBlank()) onPairWifi(wifiAddress) },
                        enabled = wifiAddress.isNotBlank()
                    ) {
                        Text(stringResource(id = R.string.wifi_pair))
                    }
                }
            )
        }

        // USB 设备检测提示
        if (!wifiExperimental && connectionType == ConnectionType.USB && detectedUsb != null) {
            Text(
                text = stringResource(id = R.string.usb_device_detected, detectedUsb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ConnectionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
