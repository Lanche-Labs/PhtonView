package com.phtontools.phtonview.util

import android.util.Log
import com.phtontools.phtonview.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AppLogger {
    private const val TAG = "PhtonView"

    @Volatile
    var debugEnabled: Boolean = BuildConfig.DEBUG

    fun d(message: String, throwable: Throwable? = null) {
        if (debugEnabled) {
            Log.d(TAG, message, throwable)
        }
    }

    fun i(message: String, throwable: Throwable? = null) {
        Log.i(TAG, message, throwable)
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    fun logUsb(direction: String, data: ByteArray, length: Int = data.size) {
        if (!debugEnabled) return
        val hex = data.take(length.coerceAtMost(data.size))
            .joinToString(" ") { String.format("%02X", it) }
        d("USB $direction: $hex")
    }

    // #region debug-point instrumentation
    fun report(
        hypothesisId: String,
        location: String,
        msg: String,
        data: Map<String, String> = emptyMap()
    ) {
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
}
