package com.phtontools.phtonview

import android.app.Application
import com.phtontools.phtonview.data.local.SettingsManager
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
        UxImprovementManager.init(this, settingsManager)
    }
}
