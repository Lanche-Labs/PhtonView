package com.phtontools.phtonview.ndk

import android.content.Context
import android.os.Build
import com.phtontools.phtonview.BuildConfig
import com.phtontools.phtonview.util.AppLogger
import java.io.File
import java.util.zip.ZipFile

/**
 * 加载 libgphoto2 相关原生库。
 *
 * 所有 .so 均打包在 APK 的 lib/<abi> 目录下；应用首次启动时将其解压到
 * 应用私有目录，然后通过 JNI 以 dlopen(RTLD_GLOBAL) 统一加载，确保
 * ltdl_preload 导出的符号对 libltdl.so 可见。
 */
object NativeLibraryLoader {

    private const val INTERNAL_DIR = "nativeLibs"
    private const val VERSION_FILE = "native_lib_version.txt"

    // 加载顺序：ltdl_preload 必须先加载，为 libltdl.so 提供缺失的
    // lt_libltdl_LTX_preloaded_symbols 符号。
    private val LIBRARIES = listOf(
        "ltdl_preload",
        "ltdl",
        "gphoto2",
        "gphoto2_port",
        "usb-1.0",
        "usb-0.1",
        "phtonview"
    )

    fun load(context: Context): Boolean {
        val libDir = prepareInternalLibs(context) ?: return false

        return try {
            // 先 System.load ltdl_preload，使其 JNI 方法可用。
            val preloadFile = File(libDir, "libltdl_preload.so")
            System.load(preloadFile.absolutePath)
            AppLogger.d("Loaded native library: ${preloadFile.absolutePath}")

            // 通过 JNI 一次性用 dlopen(RTLD_GLOBAL) 加载全部库，
            // 使 ltdl_preload 的符号对 libltdl.so 可见。
            val paths = LIBRARIES.map { File(libDir, "lib$it.so").absolutePath }.toTypedArray()
            if (!loadLibrariesGlobally(paths)) {
                throw UnsatisfiedLinkError("loadLibrariesGlobally returned false")
            }
            true
        } catch (e: Throwable) {
            AppLogger.e("NativeLibraryLoader: load failed", e)
            false
        }
    }

    private external fun loadLibrariesGlobally(paths: Array<String>): Boolean

    private fun hasLibraryFiles(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return LIBRARIES.all { File(dir, "lib$it.so").exists() }
    }

    private fun prepareInternalLibs(context: Context): File? {
        val internalDir = File(context.filesDir, INTERNAL_DIR)
        val versionFile = File(internalDir, VERSION_FILE)

        val currentVersion = "${BuildConfig.VERSION_CODE}-${BuildConfig.VERSION_NAME}"
        if (versionFile.exists() && versionFile.readText().trim() == currentVersion && hasLibraryFiles(internalDir)) {
            AppLogger.d("Internal native libraries are up-to-date")
            return internalDir
        }

        internalDir.mkdirs()
        val abi = getSupportedAbi()
        val apkPath = context.applicationInfo.sourceDir
        return try {
            ZipFile(apkPath).use { zip ->
                for (lib in LIBRARIES) {
                    val entryName = "lib/$abi/lib$lib.so"
                    val entry = zip.getEntry(entryName)
                    val target = File(internalDir, "lib$lib.so")
                    if (entry == null) {
                        AppLogger.e("NativeLibraryLoader: $entryName not found in APK", null)
                        return null
                    }
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    target.setExecutable(true, false)
                    AppLogger.d("Extracted native library: ${target.absolutePath}")
                }
            }
            versionFile.writeText(currentVersion)
            internalDir
        } catch (e: Throwable) {
            AppLogger.e("NativeLibraryLoader: failed to extract native libraries from APK", e)
            null
        }
    }

    private fun getSupportedAbi(): String {
        val supported = Build.SUPPORTED_ABIS?.toList() ?: listOf(Build.CPU_ABI)
        return supported.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "arm64-v8a"
    }
}
