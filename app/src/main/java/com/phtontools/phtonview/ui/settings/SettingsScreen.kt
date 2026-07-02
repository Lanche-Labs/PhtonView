package com.phtontools.phtonview.ui.settings

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phtontools.phtonview.BuildConfig
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.local.AppLanguage
import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.data.local.ThemeMode
import com.phtontools.phtonview.data.local.UiMode
import com.phtontools.phtonview.data.model.CameraSettings
import com.phtontools.phtonview.data.model.ConnectionState
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.ui.CameraViewModel
import com.phtontools.phtonview.ui.components.UnifiedChip
import com.phtontools.phtonview.ui.components.UnifiedSettingsHeader
import com.phtontools.phtonview.ui.components.UnifiedSettingsItem
import com.phtontools.phtonview.ui.components.UnifiedSwitchRow
import com.phtontools.phtonview.util.AppLogger
import com.phtontools.phtonview.util.UpdateChecker
import com.phtontools.phtonview.util.UxImprovementManager
import kotlinx.coroutines.launch

private enum class SettingsPage {
    Main, Theme, Language, UiMode, Credits, Update, Privacy, License
}

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
    var currentPage by remember { mutableStateOf(SettingsPage.Main) }
    var currentTheme by remember { mutableStateOf(settingsManager.themeMode) }
    var currentLanguage by remember { mutableStateOf(settingsManager.language) }
    var currentUiMode by remember { mutableStateOf(settingsManager.uiMode) }
    var debugMode by remember { mutableStateOf(settingsManager.debugMode) }
    var wifiExperimental by remember { mutableStateOf(settingsManager.wifiExperimental) }
    var uxImprovement by remember { mutableStateOf(settingsManager.uxImprovementEnabled) }
    var pendingRelease by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }

    val checkUpdate: () -> Unit = {
        scope.launch {
            val release = UpdateChecker.fetchLatestRelease()
            if (release == null) {
                android.widget.Toast.makeText(context, "暂无发行版或网络异常", android.widget.Toast.LENGTH_SHORT).show()
            } else if (!UpdateChecker.isNewer(UpdateChecker.getCurrentVersion(), release.version)) {
                android.widget.Toast.makeText(context, "当前已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                pendingRelease = release
                currentPage = SettingsPage.Update
            }
        }
    }

    val navigateBack: () -> Unit = {
        if (currentPage == SettingsPage.Main) {
            onBack()
        } else {
            currentPage = SettingsPage.Main
        }
    }

    val pageTitle = when (currentPage) {
        SettingsPage.Main -> stringResource(id = R.string.settings)
        SettingsPage.Theme -> stringResource(id = R.string.theme)
        SettingsPage.Language -> stringResource(id = R.string.language)
        SettingsPage.UiMode -> stringResource(id = R.string.select_ui_mode)
        SettingsPage.Credits -> stringResource(id = R.string.credits)
        SettingsPage.Update -> stringResource(id = R.string.check_update)
        SettingsPage.Privacy -> stringResource(id = R.string.privacy_policy)
        SettingsPage.License -> stringResource(id = R.string.license)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    fadeIn(animationSpec = tween(200)) + slideInHorizontally { it } togetherWith
                        fadeOut(animationSpec = tween(200)) + slideOutHorizontally { -it }
                } else {
                    fadeIn(animationSpec = tween(200)) + slideInHorizontally { -it } togetherWith
                        fadeOut(animationSpec = tween(200)) + slideOutHorizontally { it }
                }
            },
            label = "settings_page",
            modifier = Modifier
                .pointerInput(Unit) {
                    var consumed = false
                    detectHorizontalDragGestures(
                        onDragStart = { consumed = false },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!consumed && dragAmount > 30f) {
                                change.consume()
                                consumed = true
                                if (currentPage == SettingsPage.Main) {
                                    onBack()
                                } else {
                                    currentPage = SettingsPage.Main
                                }
                            }
                        }
                    )
                }
        ) { page ->
            when (page) {
                SettingsPage.Main -> MainSettingsContent(
                    padding = padding,
                    currentTheme = currentTheme,
                    currentLanguage = currentLanguage,
                    currentUiMode = currentUiMode,
                    wifiExperimental = wifiExperimental,
                    debugMode = debugMode,
                    uxImprovement = uxImprovement,
                    connectionState = connectionState,
                    cameraSettings = cameraSettings,
                    detectedUsb = detectedUsb,
                    onThemeClick = { currentPage = SettingsPage.Theme },
                    onLanguageClick = { currentPage = SettingsPage.Language },
                    onUiModeClick = { currentPage = SettingsPage.UiMode },
                    onCreditsClick = { currentPage = SettingsPage.Credits },
                    onPrivacyClick = { currentPage = SettingsPage.Privacy },
                    onLicenseClick = { currentPage = SettingsPage.License },
                    onCheckUpdate = checkUpdate,
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
                    },
                    onWifiExperimentalChange = {
                        wifiExperimental = it
                        settingsManager.wifiExperimental = it
                    },
                    onDebugModeChange = {
                        debugMode = it
                        settingsManager.debugMode = it
                        AppLogger.debugEnabled = it
                    },
                    onUxImprovementChange = {
                        uxImprovement = it
                        UxImprovementManager.setEnabled(it)
                    }
                )

                SettingsPage.Theme -> ThemePage(
                    modifier = Modifier.padding(padding),
                    currentTheme = currentTheme,
                    onThemeSelected = {
                        currentTheme = it
                        settingsManager.themeMode = it
                        activity?.recreate()
                    }
                )

                SettingsPage.Language -> LanguagePage(
                    modifier = Modifier.padding(padding),
                    currentLanguage = currentLanguage,
                    onLanguageSelected = {
                        currentLanguage = it
                        settingsManager.language = it
                        activity?.recreate()
                    }
                )

                SettingsPage.UiMode -> UiModePage(
                    modifier = Modifier.padding(padding),
                    currentMode = currentUiMode,
                    onModeSelected = {
                        currentUiMode = it
                        settingsManager.uiMode = it
                    }
                )

                SettingsPage.Credits -> CreditsPage(
                    modifier = Modifier.padding(padding)
                )

                SettingsPage.Update -> UpdatePage(
                    modifier = Modifier.padding(padding),
                    release = pendingRelease,
                    onConfirm = {
                        pendingRelease?.let { release ->
                            if (UpdateChecker.canInstallUpdate(context)) {
                                UpdateChecker.downloadAndInstall(context, release)
                            } else {
                                UpdateChecker.requestInstallPermission(context)
                            }
                        }
                    }
                )

                SettingsPage.Privacy -> PrivacyPolicyPage(
                    modifier = Modifier.padding(padding)
                )

                SettingsPage.License -> LicensePage(
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun MainSettingsContent(
    padding: androidx.compose.foundation.layout.PaddingValues,
    currentTheme: ThemeMode,
    currentLanguage: AppLanguage,
    currentUiMode: UiMode,
    wifiExperimental: Boolean,
    debugMode: Boolean,
    uxImprovement: Boolean,
    connectionState: ConnectionState,
    cameraSettings: CameraSettings,
    detectedUsb: String?,
    onThemeClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onUiModeClick: () -> Unit,
    onCreditsClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    onLicenseClick: () -> Unit,
    onCheckUpdate: () -> Unit,
    onConnectionTypeChange: (ConnectionType) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPairWifi: (String) -> Unit,
    onWifiExperimentalChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onUxImprovementChange: (Boolean) -> Unit
) {
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
            onClick = onThemeClick
        )
        SettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(id = R.string.language),
            summary = languageLabel(currentLanguage),
            onClick = onLanguageClick
        )

        SettingsHeader(title = stringResource(id = R.string.camera))
        SettingsItem(
            icon = Icons.Default.CameraAlt,
            title = stringResource(id = R.string.select_ui_mode),
            summary = uiModeLabel(currentUiMode),
            onClick = onUiModeClick
        )
        ConnectionSection(
            connectionState = connectionState,
            connectionType = cameraSettings.connectionType,
            wifiExperimental = wifiExperimental,
            detectedUsb = detectedUsb,
            onConnectionTypeChange = onConnectionTypeChange,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onPairWifi = onPairWifi
        )

        SettingsHeader(title = stringResource(id = R.string.general))
        SettingsSwitchItem(
            icon = Icons.Default.Wifi,
            title = stringResource(id = R.string.wifi_experimental),
            summary = stringResource(id = R.string.wifi_experimental_summary),
            checked = wifiExperimental,
            onCheckedChange = onWifiExperimentalChange
        )
        SettingsSwitchItem(
            icon = Icons.Default.BugReport,
            title = stringResource(id = R.string.debug_mode),
            summary = stringResource(id = R.string.debug_mode_summary),
            checked = debugMode,
            onCheckedChange = onDebugModeChange
        )
        SettingsSwitchItem(
            icon = Icons.Default.Analytics,
            title = stringResource(id = R.string.ux_improvement_title),
            summary = stringResource(id = R.string.ux_improvement_summary),
            checked = uxImprovement,
            onCheckedChange = onUxImprovementChange
        )
        SettingsItem(
            icon = Icons.Default.Update,
            title = stringResource(id = R.string.check_update),
            summary = stringResource(id = R.string.latest_version),
            onClick = onCheckUpdate
        )

        SettingsHeader(title = stringResource(id = R.string.about))
        SettingsItem(
            icon = Icons.Default.Info,
            title = stringResource(id = R.string.app_name),
            summary = String.format(stringResource(id = R.string.version_format), BuildConfig.VERSION_NAME) +
                    " · lanche-furry",
            onClick = {}
        )
        SettingsItem(
            icon = Icons.Default.People,
            title = stringResource(id = R.string.credits),
            summary = "",
            onClick = onCreditsClick
        )
        SettingsItem(
            icon = Icons.Default.Policy,
            title = stringResource(id = R.string.privacy_policy),
            summary = stringResource(id = R.string.privacy_policy_summary),
            onClick = onPrivacyClick
        )
        SettingsItem(
            icon = Icons.Default.Description,
            title = stringResource(id = R.string.license),
            summary = stringResource(id = R.string.license_summary),
            onClick = onLicenseClick
        )
    }
}

@Composable
private fun ThemePage(
    modifier: Modifier = Modifier,
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        ThemeMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onThemeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                RadioButton(
                    selected = currentTheme == mode,
                    onClick = { onThemeSelected(mode) }
                )
                Text(
                    text = themeLabel(mode),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun LanguagePage(
    modifier: Modifier = Modifier,
    currentLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        AppLanguage.entries.forEach { lang ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLanguageSelected(lang) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                RadioButton(
                    selected = currentLanguage == lang,
                    onClick = { onLanguageSelected(lang) }
                )
                Text(
                    text = languageLabel(lang),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun UiModePage(
    modifier: Modifier = Modifier,
    currentMode: UiMode,
    onModeSelected: (UiMode) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        UiMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                RadioButton(
                    selected = currentMode == mode,
                    onClick = { onModeSelected(mode) }
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(text = uiModeLabel(mode), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = uiModeSummary(mode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun CreditsPage(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(16.dp)
        ) {
            Text(
                text = "澜澈LanChe",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "程序制作",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(16.dp)
        ) {
            Text(
                text = "安信一・プロス（一桶）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "UI设计",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun UpdatePage(
    modifier: Modifier = Modifier,
    release: UpdateChecker.ReleaseInfo?,
    onConfirm: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (release == null) {
            Text(
                text = "暂无新版本信息",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        } else {
            Text(
                text = "最新版本：${release.version}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (release.body.isNotBlank()) {
                Text(
                    text = release.body,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("下载并安装")
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String) {
    UnifiedSettingsHeader(title = title)
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    UnifiedSettingsItem(
        icon = icon,
        title = title,
        summary = summary,
        onClick = onClick
    )
}

@Composable
private fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    UnifiedSwitchRow(
        label = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        icon = icon
    )
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
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
    UnifiedChip(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier
    )
}
