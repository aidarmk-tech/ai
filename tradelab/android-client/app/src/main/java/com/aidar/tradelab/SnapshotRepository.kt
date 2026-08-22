package com.aidar.tradelab

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class SnapshotManifest(
    val id: String,
    val filename: String,
    val sha256: String,
    val bytes: Long,
    val createdAtMs: Long,
    val downloadUrl: String,
)

class SnapshotRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val base: String
        get() = (prefs.getString("server_url", BuildConfig.SERVER_URL) ?: BuildConfig.SERVER_URL).trim().trimEnd('/')
    private val token: String
        get() = prefs.getString("read_token", "")?.trim().orEmpty()

    fun latest(): SnapshotManifest = readManifest(open("$base/api/v1/snapshots/latest"))

    fun since(afterMs: Long): List<SnapshotManifest> {
        val c = open("$base/api/v1/snapshots?after_ms=${afterMs.coerceAtLeast(0)}")
        val code = c.responseCode
        if (code !in 200..299) error("snapshot list HTTP $code")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("snapshots")
        return buildList {
            for (i in 0 until array.length()) add(parseManifest(array.getJSONObject(i)))
        }
    }

    fun createFresh(): SnapshotManifest {
        val c = open("$base/api/v1/snapshots/create", "POST")
        c.doOutput = true
        c.outputStream.use { }
        return readManifest(c, fallbackDownloadUrl = true)
    }

    private fun readManifest(c: HttpURLConnection, fallbackDownloadUrl: Boolean = false): SnapshotManifest {
        val code = c.responseCode
        if (code !in 200..299) error("snapshot manifest HTTP $code")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        return parseManifest(JSONObject(text), fallbackDownloadUrl)
    }

    private fun parseManifest(j: JSONObject, fallbackDownloadUrl: Boolean = false): SnapshotManifest {
        val id = j.getString("snapshot_id")
        return SnapshotManifest(
            id = id,
            filename = j.getString("filename"),
            sha256 = j.getString("sha256"),
            bytes = j.getLong("bytes"),
            createdAtMs = j.getLong("created_at_ms"),
            downloadUrl = if (j.has("download_url")) j.getString("download_url")
                else if (fallbackDownloadUrl) "/api/v1/snapshots/$id/download"
                else error("download_url missing"),
        )
    }

    fun download(manifest: SnapshotManifest): File {
        val dir = File(context.getExternalFilesDir(null), "snapshots").apply { mkdirs() }
        val final = File(dir, manifest.filename)
        if (final.exists() && sha256(final) == manifest.sha256) return final
        val part = File(dir, manifest.filename + ".part")
        part.delete()
        val c = open(base + manifest.downloadUrl)
        if (c.responseCode !in 200..299) error("download HTTP ${c.responseCode}")
        part.outputStream().use { out -> c.inputStream.use { input -> input.copyTo(out, 1024 * 1024) } }
        require(part.length() == manifest.bytes) { "size mismatch" }
        require(sha256(part) == manifest.sha256) { "sha256 mismatch" }
        if (final.exists()) final.delete()
        require(part.renameTo(final)) { "atomic rename failed" }
        prune(dir, 15)
        return final
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            if (token.isNotEmpty()) setRequestProperty("X-TradeLab-Token", token)
            setRequestProperty("Accept", "application/json, application/gzip")
        }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun prune(dir: File, keep: Int) {
        dir.listFiles { f -> f.name.endsWith(".sqlite3.gz") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }
}
