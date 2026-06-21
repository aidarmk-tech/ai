package com.adshield.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update: checks the rolling GitHub release for a newer APK and installs
 * it. The CI workflow publishes `version.json` + `AdShield.apk` under the
 * `adshield-auto` release tag.
 */
object AppUpdater {

    private const val VERSION_URL =
        "https://github.com/aidarmk-tech/ai/releases/download/adshield-auto/version.json"
    private const val APK_NAME = "AdShield-update.apk"

    data class Info(val versionCode: Int, val versionName: String, val url: String, val notes: String)

    /** Returns release info if a newer build is available, else null. */
    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                instanceFollowRedirects = true
            }
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            val json = JSONObject(text)
            val info = Info(
                versionCode = json.getInt("versionCode"),
                versionName = json.optString("versionName"),
                url = json.getString("url"),
                notes = json.optString("notes"),
            )
            if (info.versionCode > BuildConfig.VERSION_CODE) info else null
        }.getOrNull()
    }

    /** Downloads the APK to cache; returns the file or null on failure. */
    suspend fun download(ctx: Context, info: Info): File? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val out = File(ctx.cacheDir, APK_NAME)
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            out
        }.getOrNull()
    }

    /**
     * Launches the system package installer for [apk].
     * Returns false if the user must first grant "install unknown apps".
     */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(settings) }
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        return true
    }
}
