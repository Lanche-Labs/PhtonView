package com.phtontools.phtonview.data.local

import android.content.Context
import android.content.SharedPreferences
import com.phtontools.phtonview.data.model.CameraBrand
import com.phtontools.phtonview.data.model.ConnectionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeModeFlow = MutableStateFlow(themeMode)
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow

    private val _languageFlow = MutableStateFlow(language)
    val languageFlow: StateFlow<AppLanguage> = _languageFlow

    private val _debugModeFlow = MutableStateFlow(debugMode)
    val debugModeFlow: StateFlow<Boolean> = _debugModeFlow

    private val _wifiExperimentalFlow = MutableStateFlow(wifiExperimental)
    val wifiExperimentalFlow: StateFlow<Boolean> = _wifiExperimentalFlow

    private val _uiModeFlow = MutableStateFlow(uiMode)
    val uiModeFlow: StateFlow<UiMode> = _uiModeFlow

    private val _uxImprovementFlow = MutableStateFlow(uxImprovementEnabled)
    val uxImprovementFlow: StateFlow<Boolean> = _uxImprovementFlow

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    var uxImprovementConsentShown: Boolean
        get() = prefs.getBoolean(KEY_UX_IMPROVEMENT_CONSENT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_UX_IMPROVEMENT_CONSENT_SHOWN, value).apply()

    var uxImprovementEnabled: Boolean
        get() = prefs.getBoolean(KEY_UX_IMPROVEMENT_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_UX_IMPROVEMENT_ENABLED, value).apply()
            _uxImprovementFlow.value = value
        }

    var themeMode: ThemeMode
        get() = ThemeMode.fromOrdinal(prefs.getInt(KEY_THEME_MODE, ThemeMode.DARK.ordinal))
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value.ordinal).apply()
            _themeModeFlow.value = value
        }

    var language: AppLanguage
        get() = AppLanguage.fromOrdinal(prefs.getInt(KEY_LANGUAGE, AppLanguage.SYSTEM.ordinal))
        set(value) {
            prefs.edit().putInt(KEY_LANGUAGE, value.ordinal).apply()
            _languageFlow.value = value
        }

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()
            _debugModeFlow.value = value
        }

    var cameraBrand: CameraBrand
        get() = CameraBrand.fromOrdinal(prefs.getInt(KEY_CAMERA_BRAND, CameraBrand.Generic.ordinal))
        set(value) {
            prefs.edit().putInt(KEY_CAMERA_BRAND, value.ordinal).apply()
        }

    var connectionType: ConnectionType
        get() = ConnectionType.fromOrdinal(
            prefs.getInt(KEY_CONNECTION_TYPE, ConnectionType.USB.ordinal)
        )
        set(value) {
            prefs.edit().putInt(KEY_CONNECTION_TYPE, value.ordinal).apply()
        }

    var wifiExperimental: Boolean
        get() = prefs.getBoolean(KEY_WIFI_EXPERIMENTAL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_EXPERIMENTAL, value).apply()
            _wifiExperimentalFlow.value = value
        }

    var wifiPairedAddress: String?
        get() = prefs.getString(KEY_WIFI_PAIRED_ADDRESS, null)
        set(value) {
            prefs.edit().putString(KEY_WIFI_PAIRED_ADDRESS, value).apply()
        }

    var wifiPairedPort: Int?
        get() {
            val port = prefs.getInt(KEY_WIFI_PAIRED_PORT, -1)
            return if (port > 0) port else null
        }
        set(value) {
            if (value != null && value > 0) {
                prefs.edit().putInt(KEY_WIFI_PAIRED_PORT, value).apply()
            } else {
                prefs.edit().remove(KEY_WIFI_PAIRED_PORT).apply()
            }
        }

    var wifiPairedEventPort: Int?
        get() {
            val port = prefs.getInt(KEY_WIFI_PAIRED_EVENT_PORT, -1)
            return if (port > 0) port else null
        }
        set(value) {
            if (value != null && value > 0) {
                prefs.edit().putInt(KEY_WIFI_PAIRED_EVENT_PORT, value).apply()
            } else {
                prefs.edit().remove(KEY_WIFI_PAIRED_EVENT_PORT).apply()
            }
        }

    var uiMode: UiMode
        get() = UiMode.fromOrdinal(prefs.getInt(KEY_UI_MODE, UiMode.PRO.ordinal))
        set(value) {
            prefs.edit().putInt(KEY_UI_MODE, value.ordinal).apply()
            _uiModeFlow.value = value
        }

    companion object {
        internal const val PREFS_NAME = "phtonview_settings"
        internal const val KEY_FIRST_LAUNCH = "first_launch"
        internal const val KEY_THEME_MODE = "theme_mode"
        internal const val KEY_LANGUAGE = "language"
        internal const val KEY_DEBUG_MODE = "debug_mode"
        internal const val KEY_CAMERA_BRAND = "camera_brand"
        internal const val KEY_CONNECTION_TYPE = "connection_type"
        internal const val KEY_WIFI_EXPERIMENTAL = "wifi_experimental"
        internal const val KEY_WIFI_PAIRED_ADDRESS = "wifi_paired_address"
        internal const val KEY_WIFI_PAIRED_PORT = "wifi_paired_port"
        internal const val KEY_WIFI_PAIRED_EVENT_PORT = "wifi_paired_event_port"
        internal const val KEY_UI_MODE = "ui_mode"
        internal const val KEY_UX_IMPROVEMENT_ENABLED = "ux_improvement_enabled"
        internal const val KEY_UX_IMPROVEMENT_CONSENT_SHOWN = "ux_improvement_consent_shown"
    }
}

enum class UiMode {
    SIMPLE, PRO;

    companion object {
        fun fromOrdinal(ordinal: Int): UiMode = entries.getOrElse(ordinal) { PRO }
    }
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromOrdinal(ordinal: Int): ThemeMode = entries.getOrElse(ordinal) { SYSTEM }
    }
}

enum class AppLanguage(val code: String) {
    SYSTEM(""),
    ENGLISH("en"),
    CHINESE("zh"),
    JAPANESE("ja"),
    KOREAN("ko"),
    FRENCH("fr"),
    GERMAN("de"),
    SPANISH("es"),
    RUSSIAN("ru");

    companion object {
        fun fromOrdinal(ordinal: Int): AppLanguage = entries.getOrElse(ordinal) { SYSTEM }
    }
}
