package com.lampplayer.tv.player

import com.lampplayer.tv.domain.model.EpisodeItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit

/** Read the current TorrServer release's M3U when an external app sends just one URL. */
internal class TorrentEpisodeResolver {
    data class Playlist(val episodes: List<EpisodeItem>, val currentIndex: Int)
    private data class Numbered(val group: String, val season: Int, val episode: Int)
    private data class Entry(val url: HttpUrl, val number: Numbered)

    companion object {
        private const val LIMIT = 1024 * 1024L
        private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        private val hashPattern = Regex("[a-fA-F0-9]{40}")
        private val videoPattern = Regex("(?i)\\.(mkv|mp4|avi|m4v|mov|ts|m2ts|webm|mpg|mpeg)$")
        private val marker = Regex("(?i)(?:s(\\d{1,2})[ ._-]*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))")
        private val unwanted = Regex("(?i)(?:^|[ ._\\-/])(sample|trailer|preview)(?:[ ._\\-/]|$)")
        private val controls = setOf("play", "preload", "m3u", "fromlast", "index", "stat", "save", "fn")

        fun playlistUrl(url: String): HttpUrl? {
            val source = url.toHttpUrlOrNull() ?: return null
            if (!source.encodedPath.contains("/stream/") ||
                !hashPattern.matches(source.queryParameter("link").orEmpty())) return null
            // Use the metadata-only endpoint. An unknown server might ignore &m3u
            // on /stream and start serving video instead of returning a playlist.
            val prefix = source.encodedPath.substringBeforeLast("/stream/")
            val builder = source.newBuilder().encodedPath("$prefix/playlist")
            controls.forEach { builder.removeAllQueryParameters(it) }
            return builder.removeAllQueryParameters("link")
                .setQueryParameter("hash", source.queryParameter("link")).build()
        }

        private fun number(name: String): Numbered? {
            if (!videoPattern.containsMatchIn(name) || unwanted.containsMatchIn(name)) return null
            val m = marker.find(name) ?: return null
            val season = (m.groups[1]?.value ?: m.groups[3]?.value)?.toIntOrNull() ?: return null
            val episode = (m.groups[2]?.value ?: m.groups[4]?.value)?.toIntOrNull() ?: return null
            if (season <= 0 || episode <= 0) return null
            val group = name.substring(0, m.range.first).lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
            if (group.isBlank()) return null
            return Numbered(group, season, episode)
        }

        fun parse(sourceUrl: String, text: String): Playlist? {
            val source = sourceUrl.toHttpUrlOrNull() ?: return null
            if (playlistUrl(sourceUrl) == null || text.length > LIMIT ||
                !text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U") ||
                text.contains("#EXT-X-")) return null
            val currentNumber = number(source.pathSegments.last()) ?: return null
            val hash = source.queryParameter("link")!!
            val streamPrefix = source.encodedPath.substringBeforeLast("/stream/") + "/stream/"
            val entries = mutableListOf<Entry>()
            for (line in text.lineSequence()) {
                val value = line.trim()
                if (value.isEmpty() || value.startsWith('#') || value.startsWith('\uFEFF')) continue
                val url = source.resolve(value) ?: continue
                if (url.scheme != source.scheme || url.host != source.host || url.port != source.port ||
                    !url.encodedPath.startsWith(streamPrefix) ||
                    !hash.equals(url.queryParameter("link"), true) ||
                    (url.queryParameter("index")?.toIntOrNull() ?: -1) < 0) continue
                val n = number(url.pathSegments.last()) ?: continue
                // The info panel numbers rows within one season; don't mix equal episode
                // numbers from different seasons of a multi-season torrent pack.
                if (n.group == currentNumber.group && n.season == currentNumber.season) entries += Entry(url, n)
                if (entries.size > 1000) return null
            }
            val unique = entries.distinctBy { it.url.toString() }
            // Two encodes of the same episode are ambiguous; don't choose a release by accident.
            if (unique.groupBy { it.number.season to it.number.episode }.any { it.value.size > 1 }) return null
            val sorted = unique.sortedWith(compareBy({ it.number.season }, { it.number.episode }))
            val matches = sorted.withIndex().filter { (_, entry) ->
                entry.url.pathSegments.last() == source.pathSegments.last() &&
                    (source.queryParameter("index") == null ||
                        entry.url.queryParameter("index") == source.queryParameter("index"))
            }
            if (sorted.size < 2 || matches.size != 1) return null
            val current = matches.single().index
            val episodes = sorted.mapIndexed { i, entry ->
                val builder = entry.url.newBuilder()
                controls.forEach { if (it != "index") builder.removeAllQueryParameters(it) }
                // Keep access keys used by a proxied server; never change origin or torrent hash.
                source.queryParameterNames.filter { it !in controls && it != "link" }.forEach { key ->
                    if (entry.url.queryParameter(key) == null)
                        source.queryParameterValues(key).forEach { builder.addQueryParameter(key, it) }
                }
                val playable = if (i == current) sourceUrl else builder.addQueryParameter("play", null).build().toString()
                EpisodeItem(i, "S${entry.number.season} · Серия ${entry.number.episode}", playable,
                    entry.number.season, entry.number.episode)
            }
            return Playlist(episodes, current)
        }

        fun showTitle(title: String, season: Int, episode: Int): String {
            val suffix = Regex("(?i)\\s*[·—–-]?\\s*S0*$season\\s*[·—–-]?\\s*E0*$episode\\s*$")
            return title.replace(suffix, "").trim().ifBlank { title }
        }
    }

    suspend fun resolve(url: String, headers: Map<String, String>): Playlist? = withContext(Dispatchers.IO) {
        val target = playlistUrl(url) ?: return@withContext null
        runCatching {
            val request = Request.Builder().url(target).apply {
                headers.filterKeys { !it.equals("Range", true) && !it.equals("Host", true) &&
                    !it.equals("Content-Length", true) && !it.startsWith("X-Lmnp-", true) }
                    .forEach { (key, value) -> header(key, value) }
            }.get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > LIMIT) return@use null
                val buffer = Buffer()
                while (buffer.size <= LIMIT) {
                    if (body.source().read(buffer, minOf(8192L, LIMIT + 1 - buffer.size)) == -1L) break
                }
                if (buffer.size > LIMIT) null else parse(url, buffer.readUtf8())
            }
        }.getOrNull()
    }
}
