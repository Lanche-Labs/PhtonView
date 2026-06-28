package com.phtontools.phtonview.ndk

import android.content.Context
import android.os.Build
import com.phtontools.phtonview.util.AppLogger
import java.io.File
import java.io.IOException

object Gphoto2ModuleLoader {

    private const val ASSET_ROOT = "gphoto2"
    private const val VERSION_FILE = "module_version.txt"
    private const val CURRENT_VERSION = "2.5.31-0.12.2"

    fun copyModulesIfNeeded(context: Context, destination: File) {
        val versionFile = File(destination, VERSION_FILE)
        if (versionFile.exists() && versionFile.readText().trim() == CURRENT_VERSION) {
            AppLogger.d("gphoto2 modules already up-to-date")
            return
        }

        val abi = getSupportedAbi()
        val baseAsset = "$ASSET_ROOT/$abi"
        AppLogger.d("Copying gphoto2 modules from assets/$baseAsset to ${destination.absolutePath}")

        try {
            copyAssetDirectory(context, "$baseAsset/libgphoto2/2.5.31", File(destination, "libgphoto2/2.5.31"))
            copyAssetDirectory(context, "$baseAsset/libgphoto2_port/0.12.2", File(destination, "libgphoto2_port/0.12.2"))
            versionFile.parentFile?.mkdirs()
            versionFile.writeText(CURRENT_VERSION)
            AppLogger.d("gphoto2 modules copied successfully")
        } catch (e: IOException) {
            AppLogger.e("Failed to copy gphoto2 modules", e)
        }
    }

    private fun getSupportedAbi(): String {
        val supported = Build.SUPPORTED_ABIS?.toList() ?: listOf(Build.CPU_ABI)
        return supported.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "arm64-v8a"
    }

    private fun copyAssetDirectory(context: Context, assetPath: String, destDir: File) {
        val assets = context.assets
        val files = assets.list(assetPath) ?: return
        if (files.isEmpty()) return

        destDir.mkdirs()
        for (file in files) {
            val src = "$assetPath/$file"
            val dst = File(destDir, file)
            try {
                assets.open(src).use { input ->
                    dst.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                dst.setExecutable(true, false)
                AppLogger.d("Copied module: ${dst.absolutePath}")
            } catch (e: IOException) {
                AppLogger.e("Failed to copy asset $src", e)
            }
        }
    }
}
