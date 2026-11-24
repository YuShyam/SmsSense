package com.yushyam.smssense.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val tagName: String,
    val downloadUrl: String,
    val assetName: String,
    val releaseNotes: String,
    val isUpdateAvailable: Boolean
)

object AppUpdater {

    private const val REPO_OWNER = "YuShyam"
    private const val REPO_NAME = "SmsSense"

    fun currentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").trim()
    }

    fun isNewerVersion(currentVersion: String, remoteTagOrVersion: String): Boolean {
        val currentParts = normalizeVersion(currentVersion)
            .split(".", "-")
            .mapNotNull { it.toIntOrNull() }
        val remoteParts = normalizeVersion(remoteTagOrVersion)
            .split(".", "-")
            .mapNotNull { it.toIntOrNull() }

        if (remoteParts.isEmpty()) return false
        val maxLen = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val cur = currentParts.getOrElse(i) { 0 }
            val rem = remoteParts.getOrElse(i) { 0 }
            if (rem > cur) return true
            if (rem < cur) return false
        }
        return false
    }

    fun cleanCachedApks(context: Context) {
        try {
            val dir = File(context.cacheDir, "updates")
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    fun checkLatestRelease(
        context: Context,
        currentVerOverride: String? = null
    ): Result<ReleaseUpdateInfo> {
        return try {
            val currentVer = currentVerOverride ?: currentVersionName(context)
            val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "$REPO_NAME-Android-Updater")
            }

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return Result.failure(IllegalStateException("GitHub API 回應碼：$code"))
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseReleaseJson(currentVer, body)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseReleaseJson(currentVer: String, jsonText: String): Result<ReleaseUpdateInfo> {
        return try {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val tagName = root.get("tag_name")?.asString?.trim().orEmpty()
            if (tagName.isEmpty()) {
                return Result.failure(IllegalStateException("無法取得最新標籤資訊"))
            }
            val latestVer = normalizeVersion(tagName)
            val notes = root.get("body")?.takeIf { !it.isJsonNull }?.asString?.trim()
                .orEmpty()
                .ifEmpty { "包含穩定性修正與功能更新。" }

            var downloadUrl = ""
            var assetName = "$REPO_NAME-$tagName.apk"
            val assets = root.getAsJsonArray("assets")
            if (assets != null) {
                for (el in assets) {
                    val obj = el.asJsonObject
                    val name = obj.get("name")?.asString.orEmpty()
                    val url = obj.get("browser_download_url")?.asString.orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true) && url.isNotEmpty()) {
                        assetName = name
                        downloadUrl = url
                        break
                    }
                }
            }

            if (downloadUrl.isEmpty()) {
                return Result.failure(IllegalStateException("最新版本尚未附加 APK 安裝檔"))
            }

            Result.success(
                ReleaseUpdateInfo(
                    currentVersion = normalizeVersion(currentVer),
                    latestVersion = latestVer,
                    tagName = tagName,
                    downloadUrl = downloadUrl,
                    assetName = assetName,
                    releaseNotes = notes,
                    isUpdateAvailable = isNewerVersion(currentVer, tagName)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun downloadApk(
        context: Context,
        downloadUrl: String,
        assetName: String,
        onProgress: (Int) -> Unit
    ): Result<File> {
        return try {
            val updateDir = File(context.cacheDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifEmpty { "update.apk" }
            val targetFile = File(updateDir, safeName)
            if (targetFile.exists()) targetFile.delete()

            var currentUrl = downloadUrl
            var redirects = 0
            var conn: HttpURLConnection
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 12000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "$REPO_NAME-Android-Updater")
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) break
                    currentUrl = loc
                    redirects++
                } else {
                    break
                }
            }

            try {
                if (conn.responseCode !in 200..299) {
                    return Result.failure(IllegalStateException("下載失敗 (HTTP ${conn.responseCode})"))
                }
                val totalBytes = conn.contentLengthLong
                var downloadedBytes = 0L
                var lastReportedPct = -1

                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(100)
                Result.success(targetFile)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        if (!canInstallPackages(context)) {
            openInstallPermissionSettings(context)
            return false
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
