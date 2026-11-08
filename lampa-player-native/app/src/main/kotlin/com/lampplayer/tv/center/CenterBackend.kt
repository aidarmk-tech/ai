package com.lampplayer.tv.center

import com.google.gson.*
import com.lampplayer.tv.data.tmdb.TmdbRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CenterTitle(val id: Int, val type: String, val title: String, val original: String = "",
    val overview: String = "", val poster: String? = null, val backdrop: String? = null,
    val year: String = "", val rating: String = "", val provider: String? = null,
    val sourceUrl: String? = null, val updateLabel: String? = null) {
    val key get() = "${provider ?: "tmdb"}:$type:$id"
}
data class CenterEpisode(val season: Int, val number: Int, val name: String, val overview: String,
    val still: String? = null, val airDate: String = "", val runtime: Int = 0)
data class CenterDetails(val title: CenterTitle, val seasons: List<Int>, val actors: String)
data class CenterRelease(val title: String, val link: String, val seeds: Int, val size: Long, val tracker: String, val leechers: Int? = null, val bitrate: String? = null, val seedsKnown: Boolean = true, val voices: List<String> = emptyList())
data class CenterFile(val id: Int, val path: String, val size: Long) {
    val name get() = path.substringAfterLast('/')
    fun episode(): Pair<Int, Int>? {
        val m = Regex("(?i)(?:s(\\d{1,2})[ ._-]*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))").find(path) ?: return null
        return (m.groups[1]?.value ?: m.groups[3]!!.value).toInt() to
            (m.groups[2]?.value ?: m.groups[4]!!.value).toInt()
    }
}
data class CenterBrowse(val type: String = "movie", val mode: String = "popular", val country: String = "",
    val year: Int? = null, val genre: Int? = null, val genreName: String = "", val query: String = "", val translatedOnly: Boolean = true)
/** Upstream pagination survives pages emptied by title filtering. */
class CenterTitlePage(private val values: List<CenterTitle>, val hasMore: Boolean) : AbstractList<CenterTitle>() {
    override val size: Int get() = values.size
    override fun get(index: Int): CenterTitle = values[index]
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
        private fun count(obj: JsonObject, vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
            obj.str(key).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
        }
        private fun bitrateText(value: Double?): String? = value?.takeIf { it.isFinite() && it > 0 }
            ?.let { "%.1f Мбит/с".format(java.util.Locale.ROOT, it / 1000000.0) }
        fun browseParameters(spec: CenterBrowse, page: Int, today: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())): Map<String, String> {
            require(spec.type in listOf("movie", "tv", "all") && page in 1..500)
            val query = mutableMapOf("page" to page.toString(), "include_adult" to "false")
            if(spec.query.isNotBlank()) { query["query"]=spec.query; return query }
            require(spec.type != "all")
            query["sort_by"] = "popularity.desc"
            if(spec.mode=="new") {
                val field=if(spec.type=="tv") "first_air_date" else "primary_release_date"
                query["$field.lte"]=today
                if(spec.year==null) {
                    val format=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US)
                    val start=java.util.Calendar.getInstance().apply { time=requireNotNull(format.parse(today));add(java.util.Calendar.DAY_OF_YEAR,-180) }
                    query["$field.gte"]=format.format(start.time)
                }
            }
            if(spec.country.isNotBlank()) { require(spec.country in listOf("RU","KZ","US"));query["with_origin_country"]=spec.country }
            spec.year?.let { require(it in 1888..2200);query[if(spec.type=="tv") "first_air_date_year" else "primary_release_year"]=it.toString() }
            spec.genre?.let { require(it>0);query["with_genres"]=it.toString() }
            return query
        }
        fun sectionParameters(section:CenterSection,page:Int,today:String = java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date())):Pair<String,Map<String,String>> {
            require(section.type in listOf("movie","tv")&&page in 1..500)
            val kind=when(section.id) { "new_movies","new_series"->"recent";"new_episodes"->"airing";else->section.kind }
            if(kind=="trending")return "trending/${section.type}/week" to mapOf("page" to page.toString())
            if(kind=="airing") {
                require(section.type=="tv")
                return "tv/on_the_air" to mapOf("page" to page.toString(),"timezone" to "Asia/Almaty")
            }
            val query=browseParameters(CenterBrowse(type=section.type,mode=if(kind=="recent")"new" else "popular",
                country=section.country.orEmpty(),genre=section.genre),page,today).toMutableMap()
            query[if(section.type=="tv")"first_air_date.lte" else "primary_release_date.lte"]=today
            if(kind=="top") {
                query["sort_by"]="vote_average.desc"
                query["vote_count.gte"]=if(section.type=="tv")"200" else "1000"
            }
            return "discover/${section.type}" to query
        }
        fun russianTitle(type: String, id: Int, translations: JsonObject, aliases: JsonObject? = null): String? {
            if(translations.num("id").toInt()!=id)return null
            val translated=translations.getAsJsonArray("translations").orEmpty().filter { it.isJsonObject }
                .map { it.asJsonObject }.filter { it.str("iso_639_1")=="ru" }
                .sortedByDescending { it.str("iso_3166_1")=="RU" }
                .firstNotNullOfOrNull { it.getAsJsonObject("data")?.str(if(type=="tv")"name" else "title")?.trim()?.takeIf(String::isNotBlank) }
            if(translated!=null)return translated
            if(aliases==null||aliases.num("id").toInt()!=id)return null
            return aliases.getAsJsonArray(if(type=="tv")"results" else "titles").orEmpty()
                .filter { it.isJsonObject }.map { it.asJsonObject }
                .firstNotNullOfOrNull { if(it.str("iso_3166_1")=="RU")it.str("title").trim().takeIf(String::isNotBlank) else null }
        }
        private fun alreadyRussianTitle(o: JsonObject, type: String): Boolean {
            val name=o.str(if(type=="tv")"name" else "title")
            val original=o.str(if(type=="tv")"original_name" else "original_title")
            return o.str("original_language")=="ru" ||
                (original.isNotBlank()&&name!=original&&Regex("[А-Яа-яЁё]").containsMatchIn(name))
        }
        fun video(path: String) = Regex("(?i)\\.(mkv|mp4|avi|m4v|mov|ts|m2ts|webm|mpeg|mpg)$").containsMatchIn(path) &&
            !Regex("(?i)(?:^|[ ._/\\-])(sample|trailer|preview)(?:[ ._/\\-]|$)").containsMatchIn(path)
        fun parseReleases(text: String): List<CenterRelease> {
            val results = mutableListOf<CenterRelease>()
            if (text.trimStart().startsWith('<')) {
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(text))
                parser.nextTag()
                check(parser.name.substringAfter(':') == "rss") { "Источник вернул страницу вместо Torznab RSS. Проверь адрес API" }
                var title = ""; var link = ""; var seeds = 0; var size = 0L; var inside = false; var seedsKnown = false; var leechers: Int? = null; var bitrate: String? = null
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) when (parser.name.substringAfter(':')) {
                        "error" -> error("Источник поиска отклонил запрос. Проверь адрес и API-ключ")
                        "item" -> { inside = true; title = ""; link = ""; seeds = 0; size = 0; seedsKnown = false; leechers = null; bitrate = null }
                        "title" -> if (inside) title = parser.nextText()
                        "link" -> if (inside && link.isBlank()) link = parser.nextText()
                        "enclosure" -> if (inside && !link.startsWith("magnet:")) link = parser.getAttributeValue(null, "url").orEmpty()
                        "size" -> if (inside) size = parser.nextText().toLongOrNull() ?: 0
                        "attr" -> if (inside) {
                            val value = parser.getAttributeValue(null, "value").orEmpty()
                            when (parser.getAttributeValue(null, "name")) {
                                "magneturl" -> link = value
                                "seeders" -> { seedsKnown = value.toIntOrNull()?.let { it >= 0 } == true; seeds = value.toIntOrNull()?.coerceAtLeast(0) ?: 0 }
                                "leechers" -> leechers = value.toIntOrNull()?.takeIf { it >= 0 }
                                "bit_rate", "bitrate_bps" -> bitrate = bitrateText(value.toDoubleOrNull())
                                "size" -> size = value.toLongOrNull() ?: 0
                            }
                        }
                    }
                    if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item") {
                        if (playableSource(link)) results += CenterRelease(title, link, seeds, size, "Torznab", leechers, bitrate, seedsKnown)
                        inside = false
                    }
                    parser.next()
                }
            } else {
                val root = JsonParser.parseString(text)
                check(!root.isJsonObject || !root.asJsonObject.has("error")) { "Источник поиска сообщил об ошибке. Проверь настройки" }
                val array = when {
                    root.isJsonArray -> root.asJsonArray
                    root.isJsonObject && root.asJsonObject.get("Results")?.isJsonArray == true -> root.asJsonObject.getAsJsonArray("Results")
                    else -> error("Ответ не похож на список раздач. Проверь тип и адрес источника")
                }
                array.take(1000).forEach { value ->
                    if (!value.isJsonObject) return@forEach
                    val o = value.asJsonObject
                    val link = o.str("magnet").ifBlank { o.str("MagnetUri") }.ifBlank { o.str("Link") }
                    val title = o.str("title").ifBlank { o.str("Title") }
                    val seeds = count(o, "sid", "seeders", "Seeders", "seeds", "Seeds")
                    val leechers = count(o, "pir", "leechers", "Leechers", "leech", "Leech")
                    val directBitrate = listOf("bit_rate", "bitrate_bps").firstNotNullOfOrNull { key -> bitrateText(o.str(key).toDoubleOrNull()) }
                    val videoBitrate = o.get("ffprobe")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.firstOrNull { it.isJsonObject && it.asJsonObject.str("codec_type") == "video" }
                        ?.asJsonObject?.str("bit_rate")?.toDoubleOrNull()?.let { bitrateText(it) }?.let { "Видео $it" }
                    if (title.isNotBlank() && playableSource(link)) results += CenterRelease(title, link,
                        seeds ?: 0, o.num("size").takeIf { it > 0 } ?: o.num("Size"),
                        o.str("tracker").ifBlank { o.str("trackerName") }.ifBlank { o.str("Tracker") },
                        leechers, directBitrate ?: videoBitrate, seeds != null,
                        o.get("voices")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } }?.distinct()?.take(12).orEmpty())
                }
            }
            return results.distinctBy { it.link }.sortedByDescending { it.seeds }.take(100)
        }
        fun matchesRelease(release: CenterRelease, item: CenterTitle, episode: CenterEpisode? = null): Boolean {
            fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase(java.util.Locale.ROOT).replace('ё','е'))
                .map { it.value }.toList()
            val actual = words(release.title).toSet()
            val titles = listOf(item.title, item.original).filter { it.isNotBlank() }
            val titleMatch = titles.any { title ->
                val wanted = words(title)
                wanted.isNotEmpty() && wanted.all { it in actual }
            }
            if (!titleMatch) return false
            val year = Regex("(?<![0-9])(?:19|20)[0-9]{2}(?![0-9])").find(release.title)?.value
            if (item.type == "movie" && item.year.length == 4 && year != null && item.year != year) return false
            if (episode != null) {
                val explicit = Regex("(?i)s([0-9]{1,2})(?:[ ._-]*e([0-9]{1,3}))?").find(release.title)
                if (explicit != null) {
                    if (explicit.groupValues[1].toInt() != episode.season) return false
                    if (explicit.groupValues[2].isNotEmpty() && explicit.groupValues[2].toInt() != episode.number) return false
                }
            }
            return true
        }
        fun releaseFormat(release: CenterRelease): String {
            val tags = listOf("HEVC", "H[ .]?265", "x265", "AVC", "H[ .]?264", "x264", "AV1", "VP9",
                "Dolby[ .]?Vision", "HDR10[+]?", "HDR")
            return tags.mapNotNull { Regex("(?i)(?<![a-z0-9])(?:$it)(?![a-z0-9])").find(release.title)?.value }
                .distinctBy { it.lowercase(java.util.Locale.ROOT) }.joinToString(" · ").ifBlank { "Не указан" }
        }
        fun selectReleases(releases: List<CenterRelease>, quality: String, sort: String, seededOnly: Boolean): List<CenterRelease> {
            val filtered = releases.filter { release ->
                (!seededOnly || (release.seedsKnown && release.seeds > 0)) &&
                    (quality == "Все" || Regex("(?<![0-9])" + Regex.escape(quality) + "(?:[pi])?(?![0-9])", RegexOption.IGNORE_CASE).containsMatchIn(release.title))
            }
            return when (sort) {
                "small" -> filtered.sortedWith(compareBy<CenterRelease> { it.size <= 0 }.thenBy { it.size }.thenByDescending { it.seeds })
                "large" -> filtered.sortedWith(compareBy<CenterRelease> { it.size <= 0 }.thenByDescending { it.size }.thenByDescending { it.seeds })
                else -> filtered.sortedWith(compareBy<CenterRelease> { !it.seedsKnown }.thenByDescending { it.seeds })
            }
        }
        fun playableSource(link: String) = link.startsWith("magnet:?xt=urn:btih:", true) || link.toHttpUrlOrNull() != null
        fun title(o: JsonObject, defaultType: String = "movie"): CenterTitle? {
            val type = o.str("media_type").ifBlank { defaultType }
            val id = o.num("id").toInt()
            val name = o.str("title").ifBlank { o.str("name") }
            if (id <= 0 || name.isBlank() || type !in setOf("movie", "tv")) return null
            return CenterTitle(id, type, name, o.str("original_title").ifBlank { o.str("original_name") }, o.str("overview"),
                o.str("poster_path").takeIf { it.isNotBlank() }?.let { "${TmdbRepository.IMAGE_BASE}w342$it" },
                o.str("backdrop_path").takeIf { it.isNotBlank() }?.let { "${TmdbRepository.IMAGE_BASE}w780$it" },
                o.str("release_date").ifBlank { o.str("first_air_date") }.take(4), o.str("vote_average").take(3))
        }
    }

    private suspend fun request(url: HttpUrl, json: JsonObject? = null): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).apply {
            if (json != null) post(json.toString().toRequestBody("application/json".toMediaType()))
        }.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                check(response.isSuccessful) { "Сервер вернул HTTP ${response.code}" }
                val body = response.body ?: error("Пустой ответ сервера")
                check(body.contentLength() <= LIMIT) { "Слишком большой ответ" }
                val buffer = Buffer()
                while (buffer.size <= LIMIT) if (body.source().read(buffer, minOf(8192L, LIMIT + 1 - buffer.size)) == -1L) break
                check(buffer.size <= LIMIT) { "Слишком большой ответ" }
                buffer.readUtf8()
                } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    protected open suspend fun tmdb(path: String, query: Map<String, String> = emptyMap()): JsonObject {
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", TmdbRepository.API_KEY).addQueryParameter("language", "ru-RU")
        query.forEach { (k, v) -> url.addQueryParameter(k, v) }
        return JsonParser.parseString(request(url.build())).asJsonObject
    }
    private val translationGate=Semaphore(3)
    private val russianNames=linkedMapOf<String,Pair<Long,String?>>()
    private suspend fun localizePage(data: JsonObject, type: String, page: Int, hideMissing: Boolean): CenterTitlePage {
        val rows=data.getAsJsonArray("results").orEmpty().filter { it.isJsonObject }.take(100)
            .mapNotNull { raw -> title(raw.asJsonObject,type)?.let { raw.asJsonObject to it } }
        val output: MutableList<CenterTitle?> = rows.map { (raw,item) ->
            if(alreadyRussianTitle(raw,type))item else item.copy(updateLabel="Перевод не проверен")
        }.toMutableList()
        withTimeoutOrNull(4500) {
            coroutineScope {
                rows.mapIndexed { index,(raw,item) -> async {
                    if(alreadyRussianTitle(raw,type))return@async
                    translationGate.withPermit {
                        try {
                            val cached=synchronized(russianNames) { russianNames[item.key]?.takeIf { System.currentTimeMillis()-it.first<21600000L } }
                            val name=if(cached!=null)cached.second else {
                                val translations=tmdb("${item.type}/${item.id}/translations")
                                check(translations.num("id").toInt()==item.id)
                                val translated=russianTitle(item.type,item.id,translations)
                                val found=if(translated!=null)translated else {
                                    val aliases=tmdb("${item.type}/${item.id}/alternative_titles")
                                    check(aliases.num("id").toInt()==item.id)
                                    russianTitle(item.type,item.id,translations,aliases)
                                }
                                synchronized(russianNames) {
                                    russianNames[item.key]=System.currentTimeMillis() to found
                                    if(russianNames.size>256)russianNames.remove(russianNames.keys.first())
                                }
                                found
                            }
                            output[index]=if(name!=null)item.copy(title=name)
                                else if(hideMissing)null else item.copy(updateLabel="Нет русского названия")
                        } catch(e: CancellationException) { throw e }
                        catch(_: Exception) { output[index]=item.copy(updateLabel="Перевод не проверен") }
                    }
                } }.awaitAll()
            }
        }
        return CenterTitlePage(output.filterNotNull(),page<data.num("total_pages").toInt().coerceAtMost(500))
    }
    open suspend fun library(spec: CenterBrowse, page: Int = 1): List<CenterTitle> {
        val query = browseParameters(spec, page).toMutableMap()
        val route = if(spec.query.isNotBlank()) "search/${if(spec.type=="all") "multi" else spec.type}" else "discover/${spec.type}"
        if(spec.query.isBlank()) {
            val today=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date())
            query[if(spec.type=="tv") "first_air_date.lte" else "primary_release_date.lte"]=today
        }
        val response=tmdb(route,query)
        return if(spec.query.isBlank()&&spec.mode=="new")localizePage(response,spec.type,page,spec.translatedOnly)
            else response.getAsJsonArray("results").orEmpty().mapNotNull { title(it.asJsonObject,spec.type) }
    }
    open suspend fun genres(type: String): List<Pair<Int,String>> {
        require(type in listOf("movie","tv"))
        return tmdb("genre/$type/list").getAsJsonArray("genres").orEmpty().mapNotNull {
            val obj=it.asJsonObject;val id=obj.num("id").toInt();val name=obj.str("name")
            if(id>0&&name.isNotBlank()) id to name else null
        }
    }
    open suspend fun browse(type: String = "all", query: String = "", page: Int = 1): List<CenterTitle> {
        val route = if (query.isNotBlank()) "search/multi" else if (type == "all") "trending/all/week" else "$type/popular"
        val response = tmdb(route, mapOf("page" to page.toString()) + if (query.isNotBlank()) mapOf("query" to query) else emptyMap())
        return response.getAsJsonArray("results").orEmpty().mapNotNull { title(it.asJsonObject, type) }
    }
    open suspend fun section(section: CenterSection, page: Int = 1): List<CenterTitle> {
        val (route,query)=sectionParameters(section,page)
        return localizePage(tmdb(route,query),section.type,page,section.country==null)
    }
    open suspend fun details(item: CenterTitle): CenterDetails {
        val o = tmdb("${item.type}/${item.id}", mapOf("append_to_response" to "credits"))
        val seasons = o.getAsJsonArray("seasons").orEmpty().map { it.asJsonObject.num("season_number").toInt() }.filter { it > 0 }
        val cast = o.getAsJsonObject("credits")?.getAsJsonArray("cast").orEmpty().take(12).map { it.asJsonObject.str("name") }
        val localized=localizePage(JsonObject().apply { add("results",JsonArray().apply { add(o) }) },item.type,1,false).firstOrNull()
        return CenterDetails(localized ?: item, seasons, cast.joinToString(", "))
    }
    open suspend fun episodes(item: CenterTitle, season: Int): List<CenterEpisode> =
        tmdb("tv/${item.id}/season/$season").getAsJsonArray("episodes").orEmpty().map {
            val o = it.asJsonObject
            CenterEpisode(season, o.num("episode_number").toInt(), o.str("name"), o.str("overview"),
                o.str("still_path").takeIf { it.isNotBlank() }?.let { "${TmdbRepository.IMAGE_BASE}w300$it" },
                o.str("air_date"), o.num("runtime").toInt())
        }

    suspend fun engineSettings(base: String): JsonObject = JsonParser.parseString(request(
        "$base/settings".toHttpUrl(), JsonObject().apply { addProperty("action", "get") })).asJsonObject

    /** Preserve every upstream setting; changing the cache reconnects the torrent engine. */
    suspend fun setEngineCache(base: String, megabytes: Int) {
        require(megabytes in setOf(32, 64, 128, 256))
        val settings = engineSettings(base)
        settings.addProperty("CacheSize", megabytes * 1024L * 1024L)
        request("$base/settings".toHttpUrl(), JsonObject().apply {
            addProperty("action", "set"); add("sets", settings)
        })
    }
    suspend fun searchReleases(endpoint: String, key: String, item: CenterTitle, episode: CenterEpisode?, mode: String = "auto"): List<CenterRelease> {
        val input = endpoint.trim().toHttpUrlOrNull() ?: error("Укажи адрес парсера в разделе «Источники»")
        val torznab = mode == "torznab" || input.encodedPath.contains("torznab", true) || input.queryParameter("t") != null
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
        val response = request(builder.build())
        return withContext(Dispatchers.IO) { parseReleases(response) }
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
    open suspend fun torrentMetrics(base: String, hash: String): String {
        val state = torrent(base, "get", hash)
        fun metric(key: String, format: (Long) -> String): String =
            state.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0 }?.toLong()?.let(format) ?: "—"
        val speed = metric("download_speed") { "%.1f МБ/с".format(java.util.Locale.ROOT, it / 1048576.0) }
        val peers = metric("active_peers") { it.toString() }
        val buffer = metric("preloaded_bytes") { "%.1f МБ".format(java.util.Locale.ROOT, it / 1048576.0) }
        return "Скорость: $speed · Пиры: $peers · Буфер: $buffer"
    }

    /** Read only the opening bytes into TorrServer's cache before handing the stream to the player. */
    open suspend fun prepareStream(url: String, onProgress: (Long, Long) -> Unit) {
        val target = 256 * 1024L
        suspendCancellableCoroutine<Unit> { continuation ->
            val client = http.newBuilder().callTimeout(90, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            val call = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-${target - 1}").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        check(response.isSuccessful) { "Подготовка видео: HTTP ${response.code}" }
                        val body = response.body ?: error("Пустой ответ торрент-движка")
                        check(body.contentType()?.type != "text") { "Движок вернул текст вместо видео" }
                        val total = body.contentLength().takeIf { it > 0 }?.coerceAtMost(target) ?: target
                        var read = 0L
                        val bytes = ByteArray(16 * 1024)
                        onProgress(0, total)
                        while (read < total && continuation.isActive) {
                            val count = body.byteStream().read(bytes, 0, minOf(bytes.size.toLong(), total - read).toInt())
                            if (count < 0) break
                            read += count
                            onProgress(read, total)
                        }
                        check(read > 0 && read >= total) { "Начало видео загрузилось не полностью. Попробуй другую раздачу" }
                    } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }
    fun stream(base: String, hash: String, file: CenterFile) = "$base/stream/".toHttpUrl().newBuilder()
        .addPathSegment(file.name).addQueryParameter("link", hash).addQueryParameter("index", file.id.toString())
        .addQueryParameter("play", null).build().toString()
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
