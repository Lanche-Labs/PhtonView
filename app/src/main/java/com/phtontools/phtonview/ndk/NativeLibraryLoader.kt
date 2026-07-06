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
 * 应用私有目录，然后通过无依赖的 bootstrap 库以 dlopen(RTLD_GLOBAL) 预加载
 * 所有依赖，确保 ltdl_preload 导出的 lt_libltdl_LTX_preloaded_symbols 符号在
 * libltdl.so 加载前进入全局命名空间。
 */
object NativeLibraryLoader {

    private const val INTERNAL_DIR = "nativeLibs"
    private const val VERSION_FILE = "native_lib_version.txt"

    // bootstrap 本身无依赖，先被 System.load，随后负责 RTLD_GLOBAL 加载其余库。
    private const val BOOTSTRAP_LIB = "bootstrap"

    // 依赖库加载顺序：叶子依赖先加载，确保 RTLD_NOW 能立即解析符号。
    // ltdl_preload 必须为 libltdl.so 提供缺失符号；usb 必须在 gphoto2_port/gphoto2 之前。
    private val DEPENDENCIES = listOf(
        "ltdl_preload",
        "ltdl",
        "usb-1.0",
        "usb-0.1",
        "gphoto2_port",
        "gphoto2"
    )

    // 需要解压/校验的全部原生库（包含引导库与最终由 JVM 加载的 phtonview）。
    private val LIBRARIES = listOf(BOOTSTRAP_LIB) + DEPENDENCIES + "phtonview"

    fun load(context: Context): Boolean {
        val libDir = prepareInternalLibs(context) ?: return false

        return try {
            // 1. 加载无依赖引导库，使 JNI 方法可用。
            val bootstrapFile = File(libDir, "lib$BOOTSTRAP_LIB.so")
            System.load(bootstrapFile.absolutePath)
            AppLogger.d("Loaded bootstrap library: ${bootstrapFile.absolutePath}")

            // 2. 通过 bootstrap 以 RTLD_GLOBAL | RTLD_NOW 按依赖顺序加载所有依赖，
            //    让 ltdl_preload 的符号在 libltdl.so 加载前进入全局命名空间。
            val depPaths = DEPENDENCIES.map { File(libDir, "lib$it.so").absolutePath }.toTypedArray()
            val globalLoaded = try {
                loadLibrariesGlobally(depPaths)
            } catch (e: Throwable) {
                AppLogger.e("NativeLibraryLoader: loadLibrariesGlobally threw", e)
                false
            }
            if (!globalLoaded) {
                // ponytail: System.load 使用 RTLD_LOCAL，无法让 ltdl_preload 符号全局可见，
                // 回退只会产生更隐蔽的 UnsatisfiedLinkError；直接失败并保留清晰日志。
                val detail = getLastNativeError()
                AppLogger.e("NativeLibraryLoader: RTLD_GLOBAL preload failed; aborting native load: $detail")
                return false
            }

            // 3. 最后由 JVM 加载 phtonview，触发 JNI 方法注册，同时让其链接的
            //    依赖（已全局加载）正常解析。
            val phtonviewFile = File(libDir, "libphtonview.so")
            System.load(phtonviewFile.absolutePath)
            AppLogger.d("Loaded phtonview library: ${phtonviewFile.absolutePath}")
            true
        } catch (e: Throwable) {
            AppLogger.e("NativeLibraryLoader: load failed", e)
            false
        }
    }

    private external fun loadLibrariesGlobally(paths: Array<String>): Boolean

    private external fun getLastNativeError(): String

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
