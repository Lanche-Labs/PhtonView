package com.phtontools.phtonview.util

import android.util.Log
import com.phtontools.phtonview.BuildConfig

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
}
