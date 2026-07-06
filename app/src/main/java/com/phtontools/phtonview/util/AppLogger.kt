package com.phtontools.phtonview.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phtontools.phtonview.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AppLogger {
    private val reportExecutor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "PhtonView-Report") }
    }
    private const val TAG = "PhtonView"
    private const val MAX_COLLECTED_LOGS = 2000

    @Volatile
    var debugEnabled: Boolean = BuildConfig.DEBUG

    @Volatile
    var collectionEnabled: Boolean = false

    private val logs = ConcurrentLinkedQueue<LogEntry>()
    private val submitting = AtomicBoolean(false)

    // ponytail: reuse SimpleDateFormat per thread instead of creating one per log entry.
    private val dateFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }
    private val issueDateFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    data class LogEntry(
        val time: Long,
        val level: String,
        val tag: String,
        val message: String,
        val throwable: String?
    ) {
        fun format(): String {
            val timeStr = dateFormatter.get()!!.format(Date(time))
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

        // 仅在调试或日志收集开启时发送本地遥测，避免 release 版本中高频创建线程。
        if (!debugEnabled && !collectionEnabled) return

        reportExecutor.execute {
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
        }
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
     * [onResult] 会在提交完成后在主线程回调 (success, message)。
     */
    fun submitToGitHubIssue(
        token: String,
        repo: String,
        title: String,
        labels: List<String> = listOf("telemetry", "auto-report"),
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        if (token.isBlank()) {
            notifyResult(onResult, false, "GITHUB_TOKEN 未配置")
            return
        }
        if (submitting.getAndSet(true)) {
            notifyResult(onResult, false, "正在提交中，请稍后再试")
            return
        }
        val snapshot = logs.toList()
        val body = buildIssueBody(snapshot)
        Thread {
            var success = false
            var message = ""
            try {
                val url = URL("https://api.github.com/repos/$repo/issues")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $token")
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
                val responseBody = if (responseCode in 200..299) {
                    ""
                } else {
                    runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
                }
                conn.disconnect()
                if (responseCode in 200..299) {
                    clearCollectedLogs()
                    success = true
                    message = "日志上报成功"
                    Log.i(TAG, "GitHub issue submitted successfully")
                } else {
                    success = false
                    message = parseGitHubError(responseCode, responseBody)
                    Log.w(TAG, "GitHub issue submission failed: HTTP $responseCode, body=$responseBody")
                }
            } catch (e: Exception) {
                success = false
                message = "日志上报异常：${e.message}"
                Log.w(TAG, "GitHub issue submission error", e)
            } finally {
                submitting.set(false)
                notifyResult(onResult, success, message)
            }
        }.start()
    }

    /**
     * 构建 GitHub Issue 正文，限制总长度不超过 GitHub 上限（65536 字符），
     * 避免提交时因 body 过长而被服务器拒绝。
     */
    private fun buildIssueBody(snapshot: List<LogEntry>): String {
        val header = buildString {
            appendLine("## PhtonView 日志报告")
            appendLine("- 应用版本：${BuildConfig.VERSION_NAME}")
            appendLine("- 时间：${issueDateFormatter.get()!!.format(Date())}")
            appendLine("- 日志条数：${snapshot.size}")
            appendLine()
        }
        val footer = "\n```\n"
        val maxBodyBytes = 65000
        val maxLogBytes = maxBodyBytes - header.toByteArray(Charsets.UTF_8).size - footer.toByteArray(Charsets.UTF_8).size - 100

        val logBuilder = StringBuilder()
        logBuilder.appendLine("```")
        var currentBytes = logBuilder.toString().toByteArray(Charsets.UTF_8).size
        for (entry in snapshot) {
            val line = entry.format() + "\n"
            val lineBytes = line.toByteArray(Charsets.UTF_8).size
            if (currentBytes + lineBytes > maxLogBytes) {
                logBuilder.appendLine("\n...（日志过长，已截断）")
                break
            }
            logBuilder.append(line)
            currentBytes += lineBytes
        }
        logBuilder.append("```")
        return header.toString() + logBuilder.toString()
    }

    /**
     * 根据 HTTP 状态码和 GitHub 返回内容给出更明确的失败提示。
     */
    private fun parseGitHubError(responseCode: Int, responseBody: String): String {
        val base = when (responseCode) {
            401 -> "GITHUB_TOKEN 无效或已过期（HTTP 401），请检查 token 是否正确"
            403 -> "GITHUB_TOKEN 没有该仓库的 Issues 写入权限（HTTP 403），请确认 token 拥有 Lanche-Labs/PhtonView 的 Issues 读写权限"
            404 -> "仓库不存在或无法访问（HTTP 404）"
            422 -> "请求格式错误，可能是日志过长或标签不存在（HTTP 422）"
            in 500..599 -> "GitHub 服务器错误（HTTP $responseCode），请稍后重试"
            else -> "日志上报失败：HTTP $responseCode"
        }
        val detail = runCatching {
            JSONObject(responseBody).optString("message", "")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return if (detail != null) "$base\n详情：$detail" else base
    }

    /**
     * 确保 [onResult] 在主线程回调，避免调用方在后台线程直接弹 Toast 导致崩溃。
     */
    private fun notifyResult(onResult: ((Boolean, String) -> Unit)?, success: Boolean, message: String) {
        onResult ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onResult(success, message)
        } else {
            Handler(Looper.getMainLooper()).post { onResult(success, message) }
        }
    }
}
