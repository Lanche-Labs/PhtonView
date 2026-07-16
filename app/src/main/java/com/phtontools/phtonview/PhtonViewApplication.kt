package com.phtontools.phtonview

import android.app.Application
import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.util.CrashHandler
import com.phtontools.phtonview.util.UxImprovementManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 应用入口类，启用 Hilt 依赖注入。
 */
@HiltAndroidApp
class PhtonViewApplication : Application() {

    @Inject
    lateinit var settingsManager: SettingsManager

    override fun onCreate() {
        super.onCreate()
        // **迭代 #4**：先装上崩溃 handler 再做其他初始化，
        // 这样后续任何初始化抛出异常都能被记录。
        CrashHandler.install(this)
        UxImprovementManager.init(this, settingsManager)
        // 启动时若上次崩溃已记录，发日志（不弹 toast，避免启动噪音）
        CrashHandler.notifyPendingCrashes(this)
    }
}
