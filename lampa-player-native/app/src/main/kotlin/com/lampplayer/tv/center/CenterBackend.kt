package com.lampplayer.tv.center

import com.google.gson.*
import com.lampplayer.tv.data.tmdb.TmdbRepository
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

data class CenterTitle(val id: Int, val type: String, val title: String, val original: String = "",
    val overview: String = "", val poster: String? = null, val backdrop: String? = null,
    val year: String = "", val rating: String = "")
data class CenterEpisode(val season: Int, val number: Int, val name: String, val overview: String)
data class CenterDetails(val title: CenterTitle, val seasons: List<Int>, val actors: String)
data class CenterRelease(val title: String, val link: String, val seeds: Int, val size: Long, val tracker: String)
data class CenterFile(val id: Int, val path: String, val size: Long) {
    val name get() = path.substringAfterLast('/')
    fun episode(): Pair<Int, Int>? {
        val m = Regex("(?i)(?:s(\\d{1,2})[ ._-]*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))").find(path) ?: return null
        return (m.groups[1]?.value ?: m.groups[3]!!.value).toInt() to
            (m.groups[2]?.value ?: m.groups[4]!!.value).toInt()
    }
}
data class CenterTorrent(val hash: String, val files: List<CenterFile>)

/** HTTP catalog, configurable indexers and the local torrent engine share no UI state. */
open class CenterBackend {
    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
        private const val LIMIT = 4 * 1024 * 1024L
        fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun JsonObject.num(key: String): Long = str(key).toDoubleOrNull()?.toLong() ?: 0
        fun video(path: String) = Regex("(?i)\\.(mkv|mp4|avi|m4v|mov|ts|m2ts|webm|mpeg|mpg)$").containsMatchIn(path) &&
            !Regex("(?i)(?:^|[ ._/\\-])(sample|trailer|preview)(?:[ ._/\\-]|$)").containsMatchIn(path)
        fun parseReleases(text: String): List<CenterRelease> {
            val results = mutableListOf<CenterRelease>()
            if (text.trimStart().startsWith('<')) {
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(text))
                var title = ""; var link = ""; var seeds = 0; var size = 0L; var inside = false
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) when (parser.name.substringAfter(':')) {
                        "error" -> error("Источник поиска отклонил запрос. Проверь адрес и API-ключ")
                        "item" -> { inside = true; title = ""; link = ""; seeds = 0; size = 0 }
                        "title" -> if (inside) title = parser.nextText()
                        "link" -> if (inside && link.isBlank()) link = parser.nextText()
                        "enclosure" -> if (inside && !link.startsWith("magnet:")) link = parser.getAttributeValue(null, "url").orEmpty()
                        "size" -> if (inside) size = parser.nextText().toLongOrNull() ?: 0
                        "attr" -> if (inside) {
                            val value = parser.getAttributeValue(null, "value").orEmpty()
                            when (parser.getAttributeValue(null, "name")) {
                                "magneturl" -> link = value
                                "seeders" -> seeds = value.toIntOrNull() ?: 0
                                "size" -> size = value.toLongOrNull() ?: 0
                            }
                        }
                    }
                    if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item") {
                        if (playableSource(link)) results += CenterRelease(title, link, seeds, size, "Torznab")
                        inside = false
                    }
                    parser.next()
                }
            } else {
                val root = JsonParser.parseString(text)
                check(!root.isJsonObject || !root.asJsonObject.has("error")) { "Источник поиска сообщил об ошибке. Проверь настройки" }
                val array = if (root.isJsonArray) root.asJsonArray else root.asJsonObject.getAsJsonArray("Results") ?: JsonArray()
                array.take(1000).forEach { value ->
                    if (!value.isJsonObject) return@forEach
                    val o = value.asJsonObject
                    val link = o.str("magnet").ifBlank { o.str("MagnetUri") }.ifBlank { o.str("Link") }
                    val title = o.str("title").ifBlank { o.str("Title") }
                    if (title.isNotBlank() && playableSource(link)) results += CenterRelease(title, link,
                        (o.num("seeders").takeIf { it > 0 } ?: o.num("Seeders")).toInt(),
                        o.num("size").takeIf { it > 0 } ?: o.num("Size"), o.str("tracker").ifBlank { o.str("Tracker") })
                }
            }
            return results.distinctBy { it.link }.sortedByDescending { it.seeds }.take(100)
        }
        fun playableSource(link: String) = link.startsWith("magnet:?xt=urn:btih:", true) || link.toHttpUrlOrNull() != null
        fun title(o: JsonObject, defaultType: String = "movie"): CenterTitle? {
            val type = o.str("media_type").ifBlank { defaultType }
            val id = o.num("id").toInt()
            val name = o.str("title").ifBlank { o.str("name") }
            if (id <= 0 || name.isBlank() || type !in setOf("movie", "tv")) return null
            return CenterTitle(id, type, name, o.str("original_title").ifBlank { o.str("original_name") }, o.str("overview"),
                o.str("poster_path").takeIf { it.isNotBlank() }?.let { "${TmdbRepository.IMAGE_BASE}w342$it" },
                o.str("backdrop_path").takeIf { it.isNotBlank() }?.let { "${TmdbRepository.IMAGE_BASE}w1280$it" },
                o.str("release_date").ifBlank { o.str("first_air_date") }.take(4), o.str("vote_average").take(3))
        }
    }

    private suspend fun request(url: HttpUrl, json: JsonObject? = null): String = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).apply {
            if (json != null) post(json.toString().toRequestBody("application/json".toMediaType()))
        }.build())
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "Сервер вернул HTTP ${response.code}" }
                val body = response.body ?: error("Пустой ответ сервера")
                check(body.contentLength() <= LIMIT) { "Слишком большой ответ" }
                val buffer = Buffer()
                while (buffer.size <= LIMIT) if (body.source().read(buffer, minOf(8192L, LIMIT + 1 - buffer.size)) == -1L) break
                check(buffer.size <= LIMIT) { "Слишком большой ответ" }
                buffer.readUtf8()
            }
        } finally { call.cancel() }
    }

    private suspend fun tmdb(path: String, query: Map<String, String> = emptyMap()): JsonObject {
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", TmdbRepository.API_KEY).addQueryParameter("language", "ru-RU")
        query.forEach { (k, v) -> url.addQueryParameter(k, v) }
        return JsonParser.parseString(request(url.build())).asJsonObject
    }
    open suspend fun browse(type: String = "all", query: String = "", page: Int = 1): List<CenterTitle> {
        val route = if (query.isNotBlank()) "search/multi" else if (type == "all") "trending/all/week" else "$type/popular"
        val response = tmdb(route, mapOf("page" to page.toString()) + if (query.isNotBlank()) mapOf("query" to query) else emptyMap())
        return response.getAsJsonArray("results").orEmpty().mapNotNull { title(it.asJsonObject, type) }
    }
    suspend fun details(item: CenterTitle): CenterDetails {
        val o = tmdb("${item.type}/${item.id}", mapOf("append_to_response" to "credits"))
        val seasons = o.getAsJsonArray("seasons").orEmpty().map { it.asJsonObject.num("season_number").toInt() }.filter { it > 0 }
        val cast = o.getAsJsonObject("credits")?.getAsJsonArray("cast").orEmpty().take(12).map { it.asJsonObject.str("name") }
        return CenterDetails(title(o, item.type) ?: item, seasons, cast.joinToString(", "))
    }
    suspend fun episodes(item: CenterTitle, season: Int): List<CenterEpisode> =
        tmdb("tv/${item.id}/season/$season").getAsJsonArray("episodes").orEmpty().map {
            val o = it.asJsonObject
            CenterEpisode(season, o.num("episode_number").toInt(), o.str("name"), o.str("overview"))
        }
    suspend fun searchReleases(endpoint: String, key: String, item: CenterTitle, episode: CenterEpisode?): List<CenterRelease> {
        val input = endpoint.trim().toHttpUrlOrNull() ?: error("Укажи адрес парсера в разделе «Источники»")
        val torznab = input.encodedPath.contains("torznab", true) || input.queryParameter("t") != null
        val builder = input.newBuilder()
        if (key.isNotBlank()) builder.setQueryParameter("apikey", key)
        if (torznab) {
            builder.setQueryParameter("t", "search").setQueryParameter("q", item.original.ifBlank { item.title } +
                (episode?.let { " S${it.season.toString().padStart(2, '0')}" } ?: ""))
        } else {
            if (!input.encodedPath.contains("/api/")) builder.encodedPath(input.encodedPath.trimEnd('/') + "/api/v1.0/torrents")
            builder.setQueryParameter("search", item.title).setQueryParameter("title", item.title)
                .setQueryParameter("title_original", item.original).setQueryParameter("type", if (item.type == "tv") "serial" else "movie")
            if (item.type == "movie" && item.year.isNotBlank()) builder.setQueryParameter("year", item.year)
            episode?.let { builder.setQueryParameter("season", it.season.toString()) }
        }
        return parseReleases(request(builder.build()))
    }

    private suspend fun torrent(base: String, action: String, hash: String = "", link: String = "", item: CenterTitle? = null): JsonObject {
        val payload = JsonObject().apply {
            addProperty("action", action)
            if (hash.isNotBlank()) addProperty("hash", hash)
            if (link.isNotBlank()) { addProperty("link", link); addProperty("save_to_db", true) }
            item?.let { addProperty("title", it.title); addProperty("poster", it.poster) }
        }
        return JsonParser.parseString(request("$base/torrents".toHttpUrl(), payload)).asJsonObject
    }
    suspend fun openRelease(base: String, release: CenterRelease, item: CenterTitle): CenterTorrent {
        var state = torrent(base, "add", link = release.link, item = item)
        val hash = state.str("hash").takeIf { Regex("[a-fA-F0-9]{40}").matches(it) } ?: error("Сервер не сообщил хеш раздачи")
        repeat(90) {
            currentCoroutineContext().ensureActive()
            val files = state.getAsJsonArray("file_stats").orEmpty().mapNotNull {
                val o = it.asJsonObject; val path = o.str("path"); val id = o.num("id").toInt()
                if (id <= 0 || !video(path)) null else CenterFile(id, path, o.num("length"))
            }
            if (files.isNotEmpty()) return CenterTorrent(hash, files)
            delay(1000)
            state = torrent(base, "get", hash)
        }
        error("Не удалось получить файлы раздачи. Проверь доступность пиров и попробуй другую раздачу")
    }
    fun stream(base: String, hash: String, file: CenterFile) = "$base/stream/".toHttpUrl().newBuilder()
        .addPathSegment(file.name).addQueryParameter("link", hash).addQueryParameter("index", file.id.toString())
        .addQueryParameter("play", null).build().toString()
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
