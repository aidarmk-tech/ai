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
        if (toFetch.isEmpty()) {
            return@withContext Result.Error("Не выбрано ни одной категории")
        }

        val failed = mutableListOf<String>()
        for (source in toFetch) {
            val ok = runCatching { download(ctx, source) }.getOrDefault(false)
            if (!ok) failed.add(source.title)
        }

        // Reload to report an accurate, deduplicated domain count.
        val list = BlockList.load(ctx)
        Prefs.setLastUpdate(ctx, System.currentTimeMillis())
        VpnState.blockListSize.value = list.size

        if (failed.size == toFetch.size) {
            Result.Error("Не удалось скачать списки (нет сети?)")
        } else {
            Result.Success(list.size, failed)
        }
    }

    private fun download(ctx: Context, source: BlocklistSource): Boolean {
        val conn = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 45000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) return false
            val tmp = File(BlocklistCatalog.fileFor(ctx, source).absolutePath + ".tmp")
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
            return tmp.renameTo(BlocklistCatalog.fileFor(ctx, source))
        } finally {
            conn.disconnect()
        }
    }
}
