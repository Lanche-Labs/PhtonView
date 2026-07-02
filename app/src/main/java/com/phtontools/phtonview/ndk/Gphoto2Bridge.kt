package com.phtontools.phtonview.ndk

import android.content.Context
import com.phtontools.phtonview.util.AppLogger
import java.io.File

object Gphoto2Bridge {
    private var modulesBasePath: String = ""



    private fun loadNativeLibraries(context: Context) {
        NativeLibraryLoader.load(context)
    }

    fun init(context: Context): Boolean {
        AppLogger.d("GPhoto2Bridge.init()")
        loadNativeLibraries(context)
        return try {
            val base = File(context.filesDir, "gphoto2/modules")
            modulesBasePath = base.absolutePath
            Gphoto2ModuleLoader.copyModulesIfNeeded(context, base)
            nativeInit(modulesBasePath)
        } catch (e: Throwable) {
            AppLogger.e("GPhoto2Bridge.init() failed", e)
            false
        }
    }

    fun listCameras(): String {
        AppLogger.d("GPhoto2Bridge.listCameras()")
        return nativeListCameras()
    }

    fun connect(index: Int): Boolean {
        AppLogger.d("GPhoto2Bridge.connect($index)")
        return nativeConnect(index)
    }

    fun capture(): Boolean {
        AppLogger.d("GPhoto2Bridge.capture()")
        return nativeCapture()
    }

    fun executeCommand(command: String): String {
        AppLogger.d("GPhoto2Bridge.executeCommand($command)")
        return nativeExecuteCommand(command)
    }

    private external fun nativeInit(modulesBasePath: String): Boolean
    private external fun nativeListCameras(): String
    private external fun nativeConnect(index: Int): Boolean
    private external fun nativeCapture(): Boolean
    private external fun nativeExecuteCommand(command: String): String
}
