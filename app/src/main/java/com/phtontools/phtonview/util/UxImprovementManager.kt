package com.phtontools.phtonview.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
     * [onResult] 会在提交完成后在主线程回调 (success, message)。
     */
    fun submitSessionLogs(onResult: ((Boolean, String) -> Unit)? = null) {
        val safeResult = wrapOnMainThread(onResult)
        try {
            val settings = settingsManager ?: run {
                safeResult.invoke(false, "设置管理器未初始化")
                return
            }
            if (!settings.uxImprovementEnabled) {
                AppLogger.w("UX improvement disabled, skip submission")
                safeResult.invoke(false, "用户体验改进计划未开启")
                return
            }
            submitLogsInternal(buildTitle(), listOf(DEFAULT_LABEL), safeResult)
        } catch (e: Exception) {
            AppLogger.e("submitSessionLogs error", e)
            safeResult.invoke(false, "提交初始化失败：${e.message}")
        }
    }

    /**
     * 强制提交当前日志，不受用户体验改进计划开关限制，用于“功能异常上报”。
     * [onResult] 会在提交完成后在主线程回调 (success, message)。
     */
    fun forceSubmitLogs(onResult: ((Boolean, String) -> Unit)? = null) {
        val safeResult = wrapOnMainThread(onResult)
        try {
            if (settingsManager == null) {
                safeResult.invoke(false, "设置管理器未初始化")
                return
            }
            // 手动上报时临时启用日志收集，尽量保留当前上下文日志；
            // 不影响用户体验改进计划开关的持久状态。
            val previouslyEnabled = AppLogger.collectionEnabled
            if (!previouslyEnabled) {
                AppLogger.collectionEnabled = true
                AppLogger.i("Manual bug report requested, temporarily enabling log collection")
            }
            submitLogsInternal(
                title = buildManualTitle(),
                labels = listOf("bug-report", "manual"),
                onResult = { success, message ->
                    if (!previouslyEnabled) {
                        AppLogger.collectionEnabled = false
                    }
                    safeResult.invoke(success, message)
                }
            )
        } catch (e: Exception) {
            AppLogger.e("forceSubmitLogs error", e)
            safeResult.invoke(false, "上报初始化失败：${e.message}")
        }
    }

    private fun submitLogsInternal(
        title: String,
        labels: List<String>,
        onResult: (Boolean, String) -> Unit
    ) {
        val token = BuildConfig.GITHUB_TOKEN.takeIf { it.isNotBlank() }
        if (token.isNullOrBlank()) {
            AppLogger.w("GITHUB_TOKEN not configured, cannot submit issue")
            onResult.invoke(false, "GITHUB_TOKEN 未配置，请在 local.properties 中填写后重新编译")
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

    /**
     * 包装回调，确保无论 [AppLogger.submitToGitHubIssue] 在哪个线程回调，
     * 最终都在主线程执行，避免调用方直接弹 Toast 崩溃。
     */
    private fun wrapOnMainThread(
        onResult: ((Boolean, String) -> Unit)?
    ): (Boolean, String) -> Unit {
        return { success, message ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onResult?.invoke(success, message)
            } else {
                Handler(Looper.getMainLooper()).post { onResult?.invoke(success, message) }
            }
        }
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
