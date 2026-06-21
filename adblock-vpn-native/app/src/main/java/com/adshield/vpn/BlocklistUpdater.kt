package com.adshield.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a hosts-format blocklist and stores it for the next VPN start. */
object BlocklistUpdater {

    /** StevenBlack's widely used unified ads + trackers hosts list. */
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    sealed class Result {
        data class Success(val domains: Int) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun update(ctx: Context, url: String = DEFAULT_URL): Result =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    requestMethod = "GET"
                }
                conn.inputStream.use { input ->
                    val tmp = File(ctx.filesDir, BlockList.DOWNLOADED_FILE + ".tmp")
                    tmp.outputStream().use { input.copyTo(it) }
                    tmp.renameTo(File(ctx.filesDir, BlockList.DOWNLOADED_FILE))
                }
                conn.disconnect()

                // Reload to report an accurate domain count.
                val list = BlockList.load(ctx)
                Prefs.setLastUpdate(ctx, System.currentTimeMillis())
                Prefs.setDownloadedCount(ctx, list.size)
                VpnState.blockListSize.value = list.size
                Result.Success(list.size)
            } catch (e: Exception) {
                Result.Error(e.message ?: "unknown error")
            }
        }
}
