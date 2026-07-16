package com.phtontools.phtonview.util

import android.content.Context
import android.os.Build
import com.phtontools.phtonview.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自动 dump（迭代 #4）。
 *
 * 旧实现：依赖用户手动触发"上传日志"按钮才能拿到崩溃栈。多数崩溃发生在用户
 * 第一次看到就闪退、根本没机会操作 UI 的场景，stack trace 永远拿不到。
 *
 * 新实现：注册 UncaughtExceptionHandler，把 stack trace 写到 cacheDir/crashes/。
 * 下次启动时若有未上传的 crash 文件，弹一次 toast 提示用户"上次崩溃已记录"。
 *
 * 注意：
 * - 不替换系统默认 handler，链式调用让其继续走 Android 默认的"应用已停止"对话框。
 * - 写文件在子线程（崩溃可能发生在 UI 线程）。
 * - 仅保留最近 5 个 crash 文件（避免 cache 堆积）。
 */
object CrashHandler {

    private const val CRASH_DIR = "crashes"
    private const val MAX_CRASH_FILES = 5
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                dumpCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // dump 失败不要影响默认 handler 行为
            }
            previous?.uncaughtException(thread, throwable)
        }
        AppLogger.d("CrashHandler installed")
    }

    /**
     * 检查上次启动是否有未上传的 crash 文件，弹 toast 提示。
     * 启动早期调用即可（任何主线程入口）。
     */
    fun notifyPendingCrashes(context: Context) {
        val dir = File(context.cacheDir, CRASH_DIR)
        if (!dir.exists()) return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.isEmpty()) return
        val latest = files.first()
        // 简化为日志（启动时弹 toast 会被用户感知为噪音）。
        AppLogger.report(
            "W",
            "CrashHandler.kt:notifyPendingCrashes",
            "Previous crash recorded",
            mapOf(
                "file" to latest.name,
                "size" to latest.length().toString(),
                "pendingCount" to files.size.toString()
            )
        )
    }

    private fun dumpCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.cacheDir, CRASH_DIR).apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "crash_$ts.txt")
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== PhtonView Crash Report ===")
            pw.println("versionName=${BuildConfig.VERSION_NAME}")
            pw.println("versionCode=${BuildConfig.VERSION_CODE}")
            pw.println("androidApi=${Build.VERSION.SDK_INT}")
            pw.println("device=${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("abi=${Build.SUPPORTED_ABIS?.joinToString()}")
            pw.println("thread=${thread.name} (id=${thread.id}, priority=${thread.priority})")
            pw.println("time=${ts}")
            pw.println()
            pw.println("--- Stack trace ---")
            throwable.printStackTrace(pw)
        }
        FileOutputStream(outFile).use { it.write(sw.toString().toByteArray(Charsets.UTF_8)) }
        rotateOldCrashes(dir)
    }

    private fun rotateOldCrashes(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size <= MAX_CRASH_FILES) return
        files.drop(MAX_CRASH_FILES).forEach { runCatching { it.delete() } }
    }
}
