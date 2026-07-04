package com.phtontools.phtonview.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.phtontools.phtonview.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/Lanche-Labs/PhtonView/releases/latest"

    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val body: String
    )

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Progress(val percent: Int, val downloaded: Long, val total: Long) : DownloadState()
        data class Success(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.connect()

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(response)
            val tagName = json.getString("tag_name").removePrefix("v")
            val body = json.optString("body", "")
            val assets = json.getJSONArray("assets")

            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (apkUrl.isNotBlank()) {
                ReleaseInfo(tagName, apkUrl, body)
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e("UpdateChecker: Failed to fetch release", e)
            null
        }
    }

    fun isNewer(localVersion: String, remoteVersion: String): Boolean {
        return try {
            val local = localVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val remote = remoteVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(local.size, remote.size)
            for (i in 0 until maxLen) {
                val l = local.getOrElse(i) { 0 }
                val r = remote.getOrElse(i) { 0 }
                when {
                    r > l -> return true
                    r < l -> return false
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun canInstallUpdate(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.REQUEST_INSTALL_PACKAGES
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        val subPath = "updates/PhtonView-${release.version}.apk"
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl)).apply {
            setTitle("PhtonView ${release.version}")
            setDescription("正在下载最新版本...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, subPath)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
                if (id != downloadId) return

                context.unregisterReceiver(this)
                val file = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    subPath
                )
                installApk(context, file)
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun downloadUpdate(context: Context, release: ReleaseInfo): Flow<DownloadState> = flow {
        emit(DownloadState.Progress(0, 0, -1))
        var connection: HttpURLConnection? = null
        try {
            val file = File(context.cacheDir, "updates/PhtonView-${release.version}.apk")
            file.parentFile?.mkdirs()

            connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "*/*")
            connection.connect()

            val total = connection.contentLength.toLong().coerceAtLeast(-1)
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            emit(DownloadState.Progress(percent, downloaded, total))
                        } else {
                            emit(DownloadState.Progress(0, downloaded, -1))
                        }
                    }
                }
            }
            emit(DownloadState.Success(file))
        } catch (e: Exception) {
            AppLogger.e("UpdateChecker: download failed", e)
            emit(DownloadState.Error(e.message ?: "下载失败"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun installApk(context: Context, file: File) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    fun getCurrentVersion(): String = BuildConfig.VERSION_NAME
}
