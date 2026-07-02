package com.phtontools.phtonview.util

import android.util.Log
import com.phtontools.phtonview.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object AppLogger {
    private const val TAG = "PhtonView"
    private const val MAX_COLLECTED_LOGS = 2000

    @Volatile
    var debugEnabled: Boolean = BuildConfig.DEBUG

    @Volatile
    var collectionEnabled: Boolean = false

    private val logs = ConcurrentLinkedQueue<LogEntry>()
    private val submitting = AtomicBoolean(false)

    data class LogEntry(
        val time: Long,
        val level: String,
        val tag: String,
        val message: String,
        val throwable: String?
    ) {
        fun format(): String {
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(time))
            return "$timeStr [$level/$tag] $message${throwable?.let { "\n$it" } ?: ""}"
        }
    }

    fun d(message: String, throwable: Throwable? = null) {
        if (debugEnabled) {
            Log.d(TAG, message, throwable)
        }
        collect("D", TAG, message, throwable)
    }

    fun i(message: String, throwable: Throwable? = null) {
        Log.i(TAG, message, throwable)
        collect("I", TAG, message, throwable)
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
        collect("W", TAG, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        collect("E", TAG, message, throwable)
    }

    fun logUsb(direction: String, data: ByteArray, length: Int = data.size) {
        if (!debugEnabled && !collectionEnabled) return
        val hex = data.take(length.coerceAtMost(data.size))
            .joinToString(" ") { String.format("%02X", it) }
        val message = "USB $direction: $hex"
        if (debugEnabled) {
            Log.d(TAG, message)
        }
        collect("D", TAG, message, null)
    }

    // #region debug-point instrumentation
    fun report(
        hypothesisId: String,
        location: String,
        msg: String,
        data: Map<String, String> = emptyMap()
    ) {
        val message = "[DEBUG-$hypothesisId] $location: $msg data=$data"
        collect("D", TAG, message, null)

        Thread {
            try {
                val url = URL("http://127.0.0.1:7777/event")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val payload = JSONObject().apply {
                    put("sessionId", "nikon-d5200-compatibility")
                    put("runId", "pre-fix")
                    put("hypothesisId", hypothesisId)
                    put("location", location)
                    put("msg", "[DEBUG] $msg")
                    put("data", JSONObject(data))
                }.toString()
                conn.outputStream.use { it.write(payload.toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) { }
        }.start()
    }
    // #endregion

    private fun collect(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!collectionEnabled) return
        val stack = throwable?.stackTraceToString()
        logs.add(LogEntry(System.currentTimeMillis(), level, tag, message, stack))
        while (logs.size > MAX_COLLECTED_LOGS) {
            logs.poll()
        }
    }

    fun getCollectedLogs(): List<LogEntry> = logs.toList()

    fun clearCollectedLogs() {
        logs.clear()
    }

    /**
     * 将收集到的日志作为 GitHub Issue 提交。
     * 若 [token] 为空或提交失败，仅记录失败并不抛出异常。
     */
    fun submitToGitHubIssue(
        token: String,
        repo: String,
        title: String,
        labels: List<String> = listOf("telemetry", "auto-report")
    ) {
        if (token.isBlank() || submitting.getAndSet(true)) return
        val body = buildString {
            appendLine("## PhtonView 自动日志报告")
            appendLine("- 应用版本：${BuildConfig.VERSION_NAME}")
            appendLine("- 时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("- 日志条数：${logs.size}")
            appendLine()
            appendLine("```")
            logs.forEach { appendLine(it.format()) }
            appendLine("```")
        }
        Thread {
            try {
                val url = URL("https://api.github.com/repos/$repo/issues")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "token $token")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val payload = JSONObject().apply {
                    put("title", title)
                    put("body", body)
                    val labelsArray = org.json.JSONArray()
                    labels.forEach { labelsArray.put(it) }
                    put("labels", labelsArray)
                }.toString()

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val responseCode = conn.responseCode
                conn.disconnect()
                if (responseCode in 200..299) {
                    clearCollectedLogs()
                } else {
                    Log.w(TAG, "GitHub issue submission failed: HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.w(TAG, "GitHub issue submission error", e)
            } finally {
                submitting.set(false)
            }
        }.start()
    }
}
