package com.aidar.tradelab

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

data class SavedSnapshot(
    val filename: String,
    val bytes: Long,
    val location: String,
)

class SnapshotRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val base: String
        get() = (prefs.getString("server_url", BuildConfig.SERVER_URL) ?: BuildConfig.SERVER_URL).trim().trimEnd('/')
    private val token: String
        get() = prefs.getString("read_token", "")?.trim().orEmpty()
    private val publicLocation = "Download/TradeLab"
    private val relativePath = Environment.DIRECTORY_DOWNLOADS + "/TradeLab/"

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

    fun download(
        manifest: SnapshotManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): SavedSnapshot {
        findVerifiedExisting(manifest)?.let { return it }

        // Keep partial bytes in persistent app storage. If Android kills the worker,
        // the next WorkManager attempt continues this exact file using HTTP Range.
        val tempDir = File(context.getExternalFilesDir(null), "snapshot-parts").apply { mkdirs() }
        val part = File(tempDir, manifest.filename + ".part")
        if (part.length() > manifest.bytes) part.delete()

        if (part.exists() && part.length() == manifest.bytes) {
            if (sha256(part) == manifest.sha256) return finishPart(part, manifest)
            part.delete()
        }

        var offset = if (part.exists()) part.length() else 0L
        val c = open(base + manifest.downloadUrl).apply {
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        val code = c.responseCode

        val append = when {
            offset > 0L && code == HttpURLConnection.HTTP_PARTIAL -> {
                val range = c.getHeaderField("Content-Range").orEmpty()
                require(range.startsWith("bytes $offset-")) { "unexpected Content-Range: $range" }
                true
            }
            code in 200..299 -> {
                // Server/proxy ignored Range. Restart this one attempt safely.
                if (offset > 0L) {
                    part.delete()
                    offset = 0L
                }
                false
            }
            code == 416 && offset == manifest.bytes -> {
                if (sha256(part) == manifest.sha256) return finishPart(part, manifest)
                part.delete()
                error("range complete but SHA-256 mismatch")
            }
            else -> error("download HTTP $code")
        }

        onProgress(offset, manifest.bytes)
        FileOutputStream(part, append).use { out ->
            c.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var downloaded = offset
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    downloaded += n
                    require(downloaded <= manifest.bytes) { "download exceeds manifest size" }
                    onProgress(downloaded, manifest.bytes)
                }
                out.fd.sync()
            }
        }

        require(part.length() == manifest.bytes) {
            "incomplete download: ${part.length()} of ${manifest.bytes} bytes"
        }
        if (sha256(part) != manifest.sha256) {
            part.delete()
            error("sha256 mismatch; partial file discarded")
        }
        return finishPart(part, manifest)
    }

    private fun finishPart(part: File, manifest: SnapshotManifest): SavedSnapshot {
        val saved = publishVerified(part, manifest)
        part.delete()
        pruneDownloads(15)
        pruneParts(3)
        return saved
    }

    private fun pruneParts(keep: Int) {
        val dir = File(context.getExternalFilesDir(null), "snapshot-parts")
        dir.listFiles { f -> f.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }

    private fun findVerifiedExisting(manifest: SnapshotManifest): SavedSnapshot? {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
            val args = arrayOf(manifest.filename, relativePath)
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    if (sha256(uri) == manifest.sha256) {
                        return SavedSnapshot(manifest.filename, manifest.bytes, publicLocation)
                    }
                    resolver.delete(uri, null, null)
                }
            }
            return null
        }

        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TradeLab").apply { mkdirs() }
        val file = File(dir, manifest.filename)
        if (file.exists() && sha256(file) == manifest.sha256) {
            return SavedSnapshot(file.name, file.length(), publicLocation)
        }
        if (file.exists()) file.delete()
        return null
    }

    private fun publishVerified(source: File, manifest: SnapshotManifest): SavedSnapshot {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, manifest.filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/gzip")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("cannot create Downloads entry")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out, 1024 * 1024) }
                } ?: error("cannot open Downloads output")
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return SavedSnapshot(manifest.filename, manifest.bytes, publicLocation)
        }

        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TradeLab").apply { mkdirs() }
        val final = File(dir, manifest.filename)
        source.copyTo(final, overwrite = true)
        return SavedSnapshot(final.name, final.length(), publicLocation)
    }

    private fun pruneDownloads(keep: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_ADDED)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH}=?"
            val args = arrayOf(relativePath)
            val oldUris = mutableListOf<android.net.Uri>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { cursor ->
                var index = 0
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                    if (!name.endsWith(".sqlite3.gz")) continue
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    if (index >= keep) oldUris += ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    index++
                }
            }
            oldUris.forEach { resolver.delete(it, null, null) }
            return
        }

        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TradeLab")
        dir.listFiles { f -> f.name.endsWith(".sqlite3.gz") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 180_000
            if (token.isNotEmpty()) setRequestProperty("X-TradeLab-Token", token)
            setRequestProperty("Accept", "application/json, application/gzip")
            setRequestProperty("Accept-Encoding", "identity")
        }

    private fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    private fun sha256(uri: android.net.Uri): String =
        context.contentResolver.openInputStream(uri)?.use { sha256(it) } ?: ""

    private fun sha256(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1024 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
