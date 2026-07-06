package com.lampplayer.tv.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    // SHA-256 of the APK as published by CI; empty for releases predating the field.
    val sha256: String,
)

/**
 * Lightweight self-updater: reads a small version.json published by CI, and (on
 * confirmation) downloads the APK and hands it to the system installer.
 * Fully-silent install isn't possible on non-rooted Android TV — the system
 * shows a one-tap confirm.
 */
object UpdateManager {
    // Served as an asset of the rolling 'auto' release (no repo commits needed).
    private const val VERSION_URL = "https://github.com/aidarmk-tech/ai/releases/download/auto/version.json"

    /** Returns update info if version.json advertises a newer versionCode, else null. */
    suspend fun check(currentCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val txt = httpGet(VERSION_URL) ?: return@runCatching null
            val o = JSONObject(txt)
            val code = o.optInt("versionCode", 0)
            val url = o.optString("url", "")
            if (code <= currentCode || url.isBlank()) return@runCatching null
            UpdateInfo(code, o.optString("versionName", ""), url, o.optString("notes", ""), o.optString("sha256", ""))
        }.getOrNull()
    }

    /**
     * Download the APK to cacheDir/updates; returns the file or null on failure.
     * When [expectedSha256] is non-blank the file's hash must match it — a mismatch
     * (corrupt download or substituted file) discards the APK.
     */
    suspend fun download(context: Context, url: String, expectedSha256: String = ""): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "lampplayer.apk")
            openStream(url).use { input -> out.outputStream().use { input.copyTo(it) } }
            if (out.length() <= 100_000) return@runCatching null   // sanity: a real APK
            if (expectedSha256.isNotBlank() && !sha256(out).equals(expectedSha256, ignoreCase = true)) {
                out.delete()
                return@runCatching null
            }
            out
        }.getOrNull()
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Hand the APK to the system installer — but only after verifying it is signed
     * with THIS app's own certificate. A tampered version.json pointing at a foreign
     * APK (or any substituted download) is refused here even though the OS installer
     * would also block a signature-mismatched update: this fails fast and safely and
     * never launches an installer for an untrusted package. Returns false if refused.
     */
    fun install(context: Context, file: File): Boolean {
        if (!signedLikeSelf(context, file)) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    /** True when [apk]'s signing certificate set matches the running app's. */
    private fun signedLikeSelf(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val mine = certDigests(installedSignatures(pm, context.packageName))
        val theirs = certDigests(archiveSignatures(pm, apk.absolutePath))
        mine.isNotEmpty() && mine == theirs
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun installedSignatures(pm: PackageManager, pkg: String): Array<Signature> =
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }

    @Suppress("DEPRECATION")
    private fun archiveSignatures(pm: PackageManager, path: String): Array<Signature> =
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            info?.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)?.signatures ?: emptyArray()
        }

    private fun certDigests(sigs: Array<Signature>): Set<String> {
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    private fun httpGet(url: String): String? =
        runCatching { openStream(url).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

    /** HTTPS-only, with a bounded redirect chain that must also stay HTTPS. */
    private fun openStream(url: String): java.io.InputStream {
        require(url.startsWith("https://", true)) { "refusing non-HTTPS update URL" }
        var u = URL(url); var hops = 0
        while (true) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                instanceFollowRedirects = false   // inspect each hop ourselves
                setRequestProperty("User-Agent", "LampPlayer")
            }
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = URL(u, c.getHeaderField("Location")); c.disconnect()
                require(next.protocol.equals("https", true)) { "refusing HTTPS→HTTP downgrade in update redirect" }
                u = next; continue
            }
            return c.inputStream
        }
    }
}
