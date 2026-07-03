package com.phtontools.phtonview.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.phtontools.phtonview.BuildConfig
import com.phtontools.phtonview.data.local.SettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用户体验改进计划管理器。
 *
 * - 在应用启动时开启日志收集；
 * - 在应用进入后台或退出时，若用户同意且配置了 GitHub Token，则自动提交 Issue；
 * - 支持通过设置随时开关。
 */
object UxImprovementManager {

    private const val GITHUB_REPO = "Lanche-Labs/PhtonView"
    private const val DEFAULT_LABEL = "telemetry"

    private var settingsManager: SettingsManager? = null
    private val activityCount = AtomicInteger(0)
    private var hasShownConsent = false

    fun init(application: Application, settings: SettingsManager) {
        settingsManager = settings
        AppLogger.collectionEnabled = settings.uxImprovementEnabled

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (activityCount.incrementAndGet() == 1) {
                    onAppForeground()
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                if (activityCount.decrementAndGet() == 0) {
                    onAppBackground()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun onAppForeground() {
        AppLogger.collectionEnabled = settingsManager?.uxImprovementEnabled == true
        AppLogger.clearCollectedLogs()
        AppLogger.i("UX improvement session started")
    }

    private fun onAppBackground() {
        AppLogger.i("UX improvement session ended")
        submitSessionLogs { submitted, message ->
            AppLogger.i("UX improvement submission: submitted=$submitted, message=$message")
        }
        AppLogger.collectionEnabled = false
    }

    fun setEnabled(enabled: Boolean) {
        settingsManager?.uxImprovementEnabled = enabled
        AppLogger.collectionEnabled = enabled
    }

    fun isEnabled(): Boolean = settingsManager?.uxImprovementEnabled == true

    fun hasConsentBeenShown(): Boolean = hasShownConsent || settingsManager?.uxImprovementConsentShown == true

    fun markConsentShown() {
        hasShownConsent = true
        settingsManager?.uxImprovementConsentShown = true
    }

    /**
     * 手动触发日志提交（例如设置页点击“立即提交”）。
     * [onResult] 会在提交完成后回调 (success, message)。
     */
    fun submitSessionLogs(onResult: ((Boolean, String) -> Unit)? = null) {
        val settings = settingsManager ?: run {
            onResult?.invoke(false, "设置管理器未初始化")
            return
        }
        if (!settings.uxImprovementEnabled) {
            AppLogger.w("UX improvement disabled, skip submission")
            onResult?.invoke(false, "用户体验改进计划未开启")
            return
        }
        submitLogsInternal(buildTitle(), listOf(DEFAULT_LABEL), onResult)
    }

    /**
     * 强制提交当前日志，不受用户体验改进计划开关限制，用于“功能异常上报”。
     * [onResult] 会在提交完成后回调 (success, message)。
     */
    fun forceSubmitLogs(onResult: ((Boolean, String) -> Unit)? = null) {
        if (settingsManager == null) {
            onResult?.invoke(false, "设置管理器未初始化")
            return
        }
        submitLogsInternal(buildManualTitle(), listOf("bug-report", "manual"), onResult)
    }

    private fun submitLogsInternal(
        title: String,
        labels: List<String>,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val token = BuildConfig.GITHUB_TOKEN.takeIf { it.isNotBlank() }
        if (token.isNullOrBlank()) {
            AppLogger.w("GITHUB_TOKEN not configured, cannot submit issue")
            onResult?.invoke(false, "GITHUB_TOKEN 未配置，请在 local.properties 中填写后重新编译")
            return
        }
        AppLogger.submitToGitHubIssue(
            token = token,
            repo = GITHUB_REPO,
            title = title,
            labels = labels,
            onResult = onResult
        )
    }

    private fun buildManualTitle(): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return "[Bug Report] PhtonView $time (${BuildConfig.VERSION_NAME})"
    }

    private fun buildTitle(): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return "[Telemetry] PhtonView $time (${BuildConfig.VERSION_NAME})"
    }
}
