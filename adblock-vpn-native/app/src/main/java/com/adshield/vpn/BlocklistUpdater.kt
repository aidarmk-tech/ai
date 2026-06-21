package com.adshield.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the lists for every enabled category and reports the result. */
object BlocklistUpdater {

    private const val MAX_BYTES = 30L * 1024 * 1024 // 30 MB safety cap per list

    sealed class Result {
        data class Success(val domains: Int, val failed: List<String>) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun update(ctx: Context): Result = withContext(Dispatchers.IO) {
        val enabled = Prefs.enabledSources(ctx)
        val toFetch = BlocklistCatalog.sources.filter { enabled.contains(it.id) }
        val customUrls = Prefs.customUrls(ctx)
        if (toFetch.isEmpty() && customUrls.isEmpty()) {
            return@withContext Result.Error("Не выбрано ни одной категории")
        }

        val failed = mutableListOf<String>()
        var attempted = 0
        for (source in toFetch) {
            attempted++
            val ok = runCatching {
                download(source.url, BlocklistCatalog.fileFor(ctx, source))
            }.getOrDefault(false)
            if (!ok) failed.add(source.title)
        }
        for (url in customUrls) {
            attempted++
            val ok = runCatching {
                download(url, BlocklistCatalog.fileForCustom(ctx, url))
            }.getOrDefault(false)
            if (!ok) failed.add(url)
        }

        // Reload to report an accurate, deduplicated domain count.
        val list = BlockList.load(ctx)
        Prefs.setLastUpdate(ctx, System.currentTimeMillis())
        VpnState.blockListSize.value = list.size

        if (failed.size == attempted) {
            Result.Error("Не удалось скачать списки (нет сети?)")
        } else {
            Result.Success(list.size, failed)
        }
    }

    private fun download(url: String, target: File): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 45000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) return false
            val tmp = File(target.absolutePath + ".tmp")
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return false
                        out.write(buf, 0, n)
                    }
                }
            }
            return tmp.renameTo(target)
        } finally {
            conn.disconnect()
        }
    }
}
