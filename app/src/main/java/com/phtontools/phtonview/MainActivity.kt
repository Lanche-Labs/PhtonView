package com.phtontools.phtonview

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phtontools.phtonview.data.local.AppLanguage
import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.data.local.ThemeMode
import com.phtontools.phtonview.ui.CameraScreen
import com.phtontools.phtonview.ui.onboarding.OnboardingScreen
import com.phtontools.phtonview.ui.settings.SettingsScreen
import com.phtontools.phtonview.ui.splash.SplashScreen
import com.phtontools.phtonview.ui.theme.PhtonViewTheme
import com.phtontools.phtonview.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsManager: SettingsManager

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
        val languageOrdinal = prefs.getInt(SettingsManager.KEY_LANGUAGE, AppLanguage.SYSTEM.ordinal)
        val language = AppLanguage.fromOrdinal(languageOrdinal)
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.debugEnabled = settingsManager.debugMode
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsManager.themeModeFlow.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            PhtonViewTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhtonViewApp(settingsManager = settingsManager)
                }
            }
        }
    }

    fun recreateForThemeOrLanguage() {
        recreate()
    }
}

object LocaleHelper {
    fun applyLanguage(context: Context, language: AppLanguage): Context {
        val locale = when (language) {
            AppLanguage.ENGLISH -> Locale("en")
            AppLanguage.CHINESE -> Locale("zh")
            AppLanguage.JAPANESE -> Locale("ja")
            AppLanguage.KOREAN -> Locale("ko")
            AppLanguage.FRENCH -> Locale("fr")
            AppLanguage.GERMAN -> Locale("de")
            AppLanguage.SPANISH -> Locale("es")
            AppLanguage.RUSSIAN -> Locale("ru")
            AppLanguage.SYSTEM -> Locale.getDefault()
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}

@Composable
private fun PhtonViewApp(
    settingsManager: SettingsManager
) {
    var showSplash by remember { mutableStateOf(true) }
    var showOnboarding by remember { mutableStateOf(settingsManager.isFirstLaunch) }
    var showSettings by remember { mutableStateOf(false) }
    var isPreparingMain by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = when {
            showSplash -> Screen.Splash
            showOnboarding -> Screen.Onboarding
            isPreparingMain -> Screen.Preparing
            showSettings -> Screen.Settings
            else -> Screen.Camera
        },
        transitionSpec = {
            fadeIn() + slideInHorizontally { it } togetherWith fadeOut() + slideOutHorizontally { -it }
        },
        label = "screen_transition"
    ) { screen ->
        when (screen) {
            Screen.Splash -> {
                SplashScreen { showSplash = false }
            }
            Screen.Onboarding -> {
                OnboardingScreen(
                    initialThemeMode = settingsManager.themeMode,
                    initialUiMode = settingsManager.uiMode,
                    onThemeSelected = {
                        settingsManager.themeMode = it
                    },
                    onUiModeSelected = {
                        settingsManager.uiMode = it
                    },
                    onFinished = {
                        settingsManager.isFirstLaunch = false
                        showOnboarding = false
                        isPreparingMain = true
                    }
                )
            }
            Screen.Preparing -> {
                PreparingMainScreen {
                    isPreparingMain = false
                    showOnboarding = false
                }
            }
            Screen.Camera -> {
                CameraScreen(
                    viewModel = hiltViewModel(),
                    onOpenSettings = { showSettings = true }
                )
            }
            Screen.Settings -> {
                SettingsScreen(
                    settingsManager = settingsManager,
                    onBack = { showSettings = false }
                )
            }
        }
    }
}

@Composable
private fun PreparingMainScreen(
    onReady: () -> Unit
) {
    LaunchedEffect(Unit) {
        delay(600)
        onReady()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                strokeWidth = 4.dp
            )
            Text(
                text = stringResource(id = R.string.preparing_main),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private enum class Screen {
    Splash, Onboarding, Preparing, Camera, Settings
}
