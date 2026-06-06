package com.lampplayer.tv.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
)

/**
 * Lightweight self-updater: reads a small version.json published by CI, and (on
 * confirmation) downloads the APK and hands it to the system installer.
 * Fully-silent install isn't possible on non-rooted Android TV — the system
 * shows a one-tap confirm.
 */
object UpdateManager {
    private const val VERSION_URL = "https://aidarmk-tech.github.io/ai/version.json"

    /** Returns update info if version.json advertises a newer versionCode, else null. */
    suspend fun check(currentCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val txt = httpGet(VERSION_URL) ?: return@runCatching null
            val o = JSONObject(txt)
            val code = o.optInt("versionCode", 0)
            val url = o.optString("url", "")
            if (code <= currentCode || url.isBlank()) return@runCatching null
            UpdateInfo(code, o.optString("versionName", ""), url, o.optString("notes", ""))
        }.getOrNull()
    }

    /** Download the APK to cacheDir/updates; returns the file or null on failure. */
    suspend fun download(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "lampplayer.apk")
            openStream(url).use { input -> out.outputStream().use { input.copyTo(it) } }
            out.takeIf { it.length() > 100_000 }   // sanity: a real APK
        }.getOrNull()
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun httpGet(url: String): String? =
        runCatching { openStream(url).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

    private fun openStream(url: String): java.io.InputStream {
        var u = URL(url); var hops = 0
        while (true) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LampPlayer")
            }
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                u = URL(u, c.getHeaderField("Location")); c.disconnect(); continue
            }
            return c.inputStream
        }
    }
}
