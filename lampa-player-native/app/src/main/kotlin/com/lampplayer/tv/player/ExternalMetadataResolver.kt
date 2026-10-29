package com.lampplayer.tv.player

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.data.tmdb.TmdbSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.util.concurrent.TimeUnit

/** Optional read-only enrichment for a single external torrent launch. */
internal class ExternalMetadataResolver {
    data class Identity(val title: String, val season: Int, val episode: Int)
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        private const val LIMIT = 1024 * 1024L
        private fun normalized(value: String) = value.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }
        fun sameTitle(expected: String, actual: String?) = !actual.isNullOrBlank() &&
            normalized(expected).isNotEmpty() && normalized(expected) == normalized(actual)

        fun chooseTv(result: TmdbSearch, title: String, year: Int?): Int? =
            if (result.total_pages != 1) null else result.results.filter {
                it.id > 0 && (sameTitle(title, it.name) || sameTitle(title, it.original_name)) &&
                    (year == null || it.first_air_date?.take(4)?.toIntOrNull() == year)
            }.distinctBy { it.id }.singleOrNull()?.id

        fun identity(url: String, title: String): Identity? {
            if (TorrentEpisodeResolver.playlistUrl(url) == null) return null
            val file = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: return null
            val m = Regex("(?i)s(\\d{1,2})[ ._-]*e(\\d{1,3})").find(file) ?: return null
            val season = m.groupValues[1].toInt()
            val episode = m.groupValues[2].toInt()
            val clean = TorrentEpisodeResolver.showTitle(title, season, episode)
            return if (season > 0 && episode > 0 && clean.length in 2..140) Identity(clean, season, episode) else null
        }

        fun parse(text: String, hash: String, title: String): CardMeta? = runCatching {
            val root = JsonParser.parseString(text).asJsonObject
            fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
            if (!hash.equals(root.str("hash"), true)) return@runCatching null
            val data = root.get("data")?.let {
                if (it.isJsonObject) it.asJsonObject else if (it.isJsonPrimitive)
                    runCatching { JsonParser.parseString(it.asString).asJsonObject }.getOrNull() else null
            }
            val card = data?.get("movie")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: data?.get("card")?.takeIf { it.isJsonObject }?.asJsonObject ?: data ?: root
            if (!sameTitle(title, card.str("title") ?: card.str("name"))) return@runCatching null
            fun image(value: String?): String? = value?.takeIf { it.length <= 2048 }?.let {
                if (it.startsWith('/') && !it.startsWith("//")) "https://image.tmdb.org/t/p/w500$it"
                else it.toHttpUrlOrNull()?.toString()
            }
            CardMeta(title, tmdbId = card.str("tmdb_id")?.toIntOrNull()?.takeIf { it > 0 },
                overview = (card.str("overview") ?: card.str("description"))?.take(12000),
                posterUrl = image(card.str("poster_url") ?: card.str("poster_path") ?: card.str("poster") ?: root.str("poster")))
                .takeIf { !it.overview.isNullOrBlank() || it.posterUrl != null || it.tmdbId != null }
        }.getOrNull()
    }

    suspend fun resolve(url: String, title: String, headers: Map<String, String>): CardMeta? = withContext(Dispatchers.IO) {
        val playlist = TorrentEpisodeResolver.playlistUrl(url) ?: return@withContext null
        val hash = playlist.queryParameter("hash") ?: return@withContext null
        val target = playlist.newBuilder().encodedPath(playlist.encodedPath.removeSuffix("/playlist") + "/torrents")
            .removeAllQueryParameters("hash").build()
        runCatching {
            val payload = JsonObject().apply { addProperty("action", "get"); addProperty("hash", hash) }
            val request = Request.Builder().url(target).apply {
                headers.filterKeys { !it.equals("Range", true) && !it.equals("Host", true) &&
                    !it.equals("Content-Length", true) && !it.startsWith("X-Lmnp-", true) }
                    .forEach { (key, value) -> header(key, value) }
            }.post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > LIMIT) return@use null
                val buffer = Buffer()
                while (buffer.size <= LIMIT) {
                    if (body.source().read(buffer, minOf(8192L, LIMIT + 1 - buffer.size)) == -1L) break
                }
                if (buffer.size > LIMIT) null else parse(buffer.readUtf8(), hash, title)
            }
        }.getOrNull()
    }
}
