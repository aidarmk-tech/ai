package com.lampplayer.tv.center

import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Explicit catalog routes observed on the providers; country filters never use title matching. */
data class CenterSection(val id: String, val label: String, val type: String, val country: String? = null) {
    companion object {
        val all = listOf(
            CenterSection("new_movies", "Новинки", "movie"),
            CenterSection("new_episodes", "Новые серии", "tv"),
            CenterSection("ru_series", "Русские сериалы", "tv", "RU"),
            CenterSection("ru_movies", "Русские фильмы", "movie", "RU"),
            CenterSection("kz_movies", "Казахстанское кино", "movie", "KZ"),
            CenterSection("kz_series", "Казахстанские сериалы", "tv", "KZ"))
    }
}

/** Read-only site catalogs. Playback remains a separate, explicit user action. */
class CenterCatalog {
    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
            .readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
        private const val LIMIT = 2 * 1024 * 1024L
        private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        val providers = listOf("tmdb", "kinovod", "filmix", "rezka", "kazplay")
        val sites get() = providers.filter { it != "tmdb" }
        fun name(provider: String?) = when(provider) {
            "kinovod" -> "Киновод"; "filmix" -> "Filmix"; "rezka" -> "Rezka"; "kazplay" -> "KazPlay · Қазақша"; else -> "TMDB"
        }
        fun defaultHost(provider: String) = when(provider) {
            "kinovod" -> "https://kinovod.pro"; "filmix" -> "https://filmix.gg"
            "rezka" -> "https://rezka.ac"; "kazplay" -> "https://kazplay.kz"
            else -> error("Неизвестный каталог")
        }
        fun sections(provider: String): List<CenterSection> = when(provider) {
            "kazplay" -> listOf(CenterSection("kazplay_movies", "Фильмы на казахском", "movie"),
                CenterSection("kazplay_series", "Сериалы на казахском", "tv"))
            "rezka" -> CenterSection.all.filter { it.country != "KZ" }.map {
                when(it.id) { "new_movies" -> it.copy(label="Фильмы · Rezka"); "new_episodes" -> it.copy(label="Сериалы · Rezka"); else -> it }
            }
            else -> CenterSection.all
        }
        fun route(provider: String, base: String, section: CenterSection, page: Int = 1): HttpUrl {
            require(page in 1..5000)
            val builder = base.trimEnd('/').toHttpUrl().newBuilder().query(null).fragment(null)
            if(provider == "kazplay") {
                require(section.country == null && section.id in sections(provider).map { it.id })
                return builder.encodedPath(if(section.type=="tv") "/serials" else "/films")
                    .addQueryParameter("page", page.toString()).build()
            }
            if(provider == "rezka") {
                require(section.country in listOf(null, "RU"))
                val kind = if(section.type=="tv") "series" else "films"
                val country = if(section.country=="RU") (if(section.type=="tv") "russkie/" else "russian/") else ""
                return builder.encodedPath("/$kind/$country" + if(page>1) "page/$page/" else "").build()
            }
            if(provider == "kinovod") {
                val kind = if(section.type == "tv") "serials" else "films"
                val country = when(section.country) { "RU" -> "/rus"; "KZ" -> "/kaz"; else -> "" }
                return builder.encodedPath("/$kind$country").addQueryParameter("page",page.toString()).build()
            }
            require(provider == "filmix")
            val kind = if(section.type == "tv") "seria" else "film"
            val country = when(section.country) { "RU" -> "c6/"; "KZ" -> "c14/"; else -> "" }
            return builder.encodedPath("/$kind/$country" + if(page > 1) "pages/$page/" else "").build()
        }
        fun searchRoute(provider: String, base: String, query: String): HttpUrl {
            val builder=base.trimEnd('/').toHttpUrl().newBuilder().query(null).fragment(null)
            if(provider == "rezka") return builder.encodedPath("/index.php").addQueryParameter("do","search")
                .addQueryParameter("subaction","search").addQueryParameter("story",query).build()
            if(provider == "kazplay") return builder.encodedPath("/api/v2/film/search").addQueryParameter("query",query).build()
            return if(provider == "kinovod") builder.encodedPath("/search").addQueryParameter("query",query).build()
            else builder.encodedPath("/api/v2/suggestions").addQueryParameter("search_word",query).build()
        }
        fun suggestions(json: String, base: String): List<CenterTitle> {
            val origin=base.toHttpUrl().newBuilder().encodedPath("/").query(null).build()
            val root=JsonParser.parseString(json).asJsonObject
            val posts=root.get("posts")?.takeIf { it.isJsonArray }?.asJsonArray ?: error("Filmix вернул неожиданный ответ поиска")
            return posts.mapNotNull { value ->
                val item=value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                fun str(key:String)=item.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val url=origin.resolve(str("link")) ?: return@mapNotNull null
                val id=str("id").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                if(url.host != origin.host || str("title").isBlank()) return@mapNotNull null
                CenterTitle(id,if(url.encodedPath.startsWith("/seria/")) "tv" else "movie",Jsoup.parse(str("title")).text(),
                    original=str("original_name"),poster=origin.resolve(str("poster").replace("/thumbs/w40/","/thumbs/w220/"))?.toString(),year=str("year"),
                    provider="filmix",sourceUrl=url.toString(),updateLabel=str("last_serie").takeIf { it.isNotBlank() })
            }.distinctBy { it.key }.take(60)
        }
        private fun Element.absolute(attr: String) = absUrl(attr).takeIf { it.toHttpUrlOrNull() != null }
        private fun Element.poster(): String? {
            val image = selectFirst("img") ?: return null
            listOf("data-src", "data-original", "data-lazy-src", "src").forEach { key ->
                image.absolute(key)?.takeUnless { it.contains("/templates/") }?.let { return it }
            }
            return null
        }
        private fun catalogId(path: String): Int =
            (java.util.zip.CRC32().apply { update(path.toByteArray(Charsets.UTF_8)) }.value and 0x7fffffffL).toInt().coerceAtLeast(1)
        fun kazplaySearch(json: String, base: String): List<CenterTitle> {
            val origin = base.toHttpUrl().newBuilder().encodedPath("/").query(null).build()
            val root = JsonParser.parseString(json).asJsonObject
            check(root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) { "KazPlay: поиск временно недоступен" }
            val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: error("KazPlay: изменился формат поиска")
            return data.mapNotNull { value ->
                val item=value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                fun str(key:String)=item.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val slug=str("slug")
                if(!Regex("[a-zA-Z0-9_-]+").matches(slug)) return@mapNotNull null
                val title=Jsoup.parse(str("title")).text()
                if(title.isBlank()) return@mapNotNull null
                val type=if(str("is_serial") in listOf("1","true")) "tv" else "movie"
                val path="/${if(type=="tv") "serial" else "film"}/$slug"
                CenterTitle(catalogId(path),type,title,original=Jsoup.parse(str("rus_title")).text().substringBefore('/').trim(),
                    overview=Jsoup.parse(str("description")).text().take(8000),
                    poster=origin.resolve(str("poster_media_url").ifBlank {
                        item.get("poster")?.takeIf { it.isJsonObject }?.asJsonObject?.get("path")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    })?.toString()?.takeUnless { it==origin.toString() },
                    backdrop=origin.resolve(str("landscape_media_url"))?.toString()?.takeUnless { it==origin.toString() },
                    year=str("date_release").takeIf { Regex("(?:19|20)[0-9]{2}").matches(it) }.orEmpty(),
                    rating=str("rating"),provider="kazplay",sourceUrl=origin.resolve(path).toString())
            }.distinctBy { it.key }.take(60)
        }
        private fun parseNew(document: Document, provider: String): List<CenterTitle> {
            val origin = document.location().toHttpUrl()
            val rows = if(provider=="rezka") document.select(".b-content__inline_item")
                else document.select("a.group[href^=/film/], a.group[href^=/serial/]")
            val items = rows.mapNotNull { row ->
                val link = if(provider=="rezka") row.selectFirst(".b-content__inline_item-link a") else row
                val url = link?.absolute("href")?.toHttpUrlOrNull() ?: return@mapNotNull null
                if(url.host != origin.host) return@mapNotNull null
                val rezkaId = Regex("^/(films|series)/(?:[^/]+/)*([0-9]+)-[^/]+\\.html$").find(url.encodedPath)
                if(provider=="rezka" && rezkaId==null) return@mapNotNull null
                if(provider=="kazplay" && !Regex("^/(film|serial)/[^/]+$").matches(url.encodedPath)) return@mapNotNull null
                val id = if(provider=="rezka") rezkaId!!.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    else catalogId(url.encodedPath)
                val type = if(url.encodedPath.startsWith("/series/") || url.encodedPath.startsWith("/serial/")) "tv" else "movie"
                val title = (if(provider=="rezka") link.text() else row.selectFirst("p.truncate")?.text()).orEmpty().trim()
                if(title.isBlank()) return@mapNotNull null
                val info = if(provider=="rezka") row.selectFirst(".misc")?.text() else row.select("p").lastOrNull()?.text()
                val year = Regex("(?:19|20)[0-9]{2}").find(info.orEmpty())?.value.orEmpty()
                val poster = row.poster()?.toHttpUrlOrNull()?.let { image ->
                    if(provider=="kazplay" && image.encodedPath=="/_next/image") image.newBuilder().setQueryParameter("w","384").build().toString()
                    else image.toString()
                }
                CenterTitle(id,type,title,poster=poster,year=year,provider=provider,sourceUrl=url.toString(),
                    rating=if(provider=="kazplay") row.selectFirst("div.absolute.right-2.top-2 span")?.text().orEmpty() else "",
                    updateLabel=if(provider=="rezka") row.selectFirst(".info")?.text()?.takeIf { it.contains("сери",true) || it.contains("сезон",true) }
                        else row.selectFirst("div.absolute.bottom-2")?.text())
            }.distinctBy { it.key }.take(60)
            val empty = document.text().let { it.contains("не найден",true) || it.contains("ничего не",true) || it.contains("табылмады",true) }
            val catalogEmpty = provider=="kazplay" && rows.isEmpty() && document.select("h1").any { it.text() in listOf("Кинолар","СЕРИАЛДАР") }
            check(items.isNotEmpty() || (rows.isEmpty() && (empty || catalogEmpty))) {
                "${name(provider)}: не удалось прочитать каталог. Проверь адрес сайта"
            }
            return items
        }
        fun parse(document: Document, provider: String): List<CenterTitle> {
            if(provider in listOf("rezka","kazplay")) return parseNew(document,provider)
            val rows = if(provider == "kinovod") document.select("ul.items > li.item") else document.select("article.shortstory, article.movie[id^=movie-]")
            val items = rows.mapNotNull { row ->
                val link=if(provider == "kinovod") row.selectFirst(".title a") else row.selectFirst("h2.name a, h2 a[itemprop=url], a.m-movie-item")
                val url=link?.absolute("href")?.toHttpUrlOrNull() ?: return@mapNotNull null
                val origin=document.location().toHttpUrlOrNull() ?: return@mapNotNull null
                if(url.host != origin.host || url.scheme !in listOf("https","http")) return@mapNotNull null
                val id=(if(provider == "kinovod") Regex("^/(?:film|serial)/([0-9]+)-").find(url.encodedPath)?.groupValues?.get(1)
                    else row.attr("data-id").ifBlank { row.id().removePrefix("movie-") }).orEmpty().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val title=(if(provider == "filmix") row.selectFirst("h2[itemprop=name], h2.name")?.text() else link.text())?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val type=if(url.encodedPath.startsWith("/serial/") || url.encodedPath.startsWith("/seria/")) "tv" else "movie"
                val year=Regex("(?:19|20)[0-9]{2}").find(row.selectFirst(if(provider == "kinovod") ".year" else "[itemprop=copyrightYear], .grid-item")?.text().orEmpty())?.value.orEmpty()
                CenterTitle(id,type,title,row.selectFirst(".origin-name, .m-movie-original")?.text().orEmpty(),
                    row.selectFirst("[itemprop=description]")?.text().orEmpty(),row.selectFirst("img.poster, .poster img, img[itemprop=image]")?.absolute("src"),
                    year=year,rating=if(provider == "kinovod") row.selectFirst(".rating")?.text().orEmpty() else "",
                    provider=provider,sourceUrl=url.toString(),updateLabel=row.selectFirst(if(provider == "kinovod") ".poster .label" else ".m-movie-add .serials, .added-info")?.text())
            }.distinctBy { it.key }.take(60)
            check(items.isNotEmpty() || (rows.isEmpty() && (document.selectFirst(".result-title .count")?.text() == "0" || document.text().contains("не найден",true) || document.text().contains("ничего не",true)))) {
                "${name(provider)}: структура страницы изменилась или сайт запросил проверку. Проверь адрес каталога"
            }
            return items
        }
        fun details(document: Document, item: CenterTitle): CenterDetails {
            val structured=document.select("script[type=application/ld+json]").firstOrNull()?.data()?.let {
                runCatching { JsonParser.parseString(it).takeIf { json -> json.isJsonObject }?.asJsonObject }.getOrNull()
            }
            val synopsis=document.selectFirst(".b-post__description_text, [itemprop=description]")?.text()?.takeIf { it.isNotBlank() }
                ?: structured?.get("description")?.takeIf { it.isJsonPrimitive }?.asString
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            val cast=structured?.get("actor")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                runCatching { it.asJsonObject.get("name").asString }.getOrNull()
            }?.joinToString(", ") ?: document.select(".item.actors").firstOrNull { it.text().contains("В ролях") }?.selectFirst(".item-content")?.text().orEmpty()
            val poster=document.selectFirst("meta[property=og:image]")?.attr("content")?.let { document.baseUri().toHttpUrlOrNull()?.resolve(it)?.toString() }
            return CenterDetails(item.copy(overview=synopsis?.take(8000) ?: item.overview,
                poster=if(item.provider=="kazplay") item.poster ?: poster else poster ?: item.poster,
                backdrop=if(item.provider=="kazplay") poster ?: item.backdrop else item.backdrop),emptyList(),cast.take(2000))
        }
    }
    private data class Cache(val at: Long, val items: List<CenterTitle>, val hasNext: Boolean? = null)
    private val cache=object : LinkedHashMap<String,Cache>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Cache>?) = size > 16
    }
    private val detailLimit = Semaphore(3)
    private val detailCache = object : LinkedHashMap<String,Pair<Long,CenterDetails>>(32,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Pair<Long,CenterDetails>>?) = size>96
    }
    fun clearCache() {
        synchronized(cache) { cache.clear() }
        synchronized(detailCache) { detailCache.clear() }
    }
    private data class Page(val bytes:ByteArray,val url:String)
    private suspend fun request(url: HttpUrl): Page = suspendCancellableCoroutine { continuation ->
        val call=http.newCall(Request.Builder().url(url).header("User-Agent",UA).apply {
            if(url.encodedPath.startsWith("/api/")) header("X-Requested-With","XMLHttpRequest")
        }.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call,response: Response) {
                val result=runCatching { response.use {
                    check(response.isSuccessful) { "Каталог вернул HTTP ${response.code}" }
                    val body=response.body ?: error("Пустой ответ каталога")
                    check(body.contentLength() <= LIMIT) { "Слишком большая страница каталога" }
                    val buffer=Buffer()
                    while(buffer.size <= LIMIT) if(body.source().read(buffer,minOf(8192L,LIMIT+1-buffer.size)) == -1L) break
                    check(buffer.size <= LIMIT) { "Слишком большая страница каталога" }
                    Page(buffer.readByteArray(),response.request.url.toString())
                } }
                if(continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
    private suspend fun page(url:HttpUrl):Document {
        val data=request(url)
        return withContext(Dispatchers.IO) { Jsoup.parse(ByteArrayInputStream(data.bytes),null,data.url) }
    }
    suspend fun browse(provider:String,base:String,section:CenterSection,number:Int=1):List<CenterTitle> {
        val url=route(provider,base,section,number)
        synchronized(cache) { cache[url.toString()] }?.takeIf { System.currentTimeMillis()-it.at < 120000 }?.let { return it.items }
        if(number>1) {
            val previous = synchronized(cache) { cache[route(provider,base,section,number-1).toString()] }
            if(previous != null && System.currentTimeMillis()-previous.at<120000 && previous.hasNext==false) return emptyList()
        }
        val document=page(url)
        val items=parse(document,provider)
        val hasNext = if(provider in listOf("rezka","kazplay")) {
            val next=route(provider,base,section,number+1)
            document.select("a[href]").any { link ->
                link.absolute("href")?.toHttpUrlOrNull()?.let { it.host==next.host && it.encodedPath==next.encodedPath &&
                    (if(provider=="kazplay") it.queryParameter("page")==next.queryParameter("page") else true) } == true
            }
        } else null
        synchronized(cache) { cache[url.toString()]=Cache(System.currentTimeMillis(),items,hasNext) }
        return items
    }
    suspend fun search(provider:String,base:String,query:String):List<CenterTitle> {
        val url=searchRoute(provider,base,query)
        if(provider in listOf("kinovod","rezka")) return parse(page(url),provider)
        val data=request(url)
        return withContext(Dispatchers.IO) {
            if(provider=="kazplay") kazplaySearch(data.bytes.toString(Charsets.UTF_8),data.url)
            else suggestions(data.bytes.toString(Charsets.UTF_8),data.url)
        }
    }
    suspend fun detail(item:CenterTitle): CenterDetails = detailLimit.withPermit {
        val url = item.sourceUrl?.toHttpUrlOrNull() ?: error("Нет адреса карточки")
        synchronized(detailCache) { detailCache[url.toString()] }?.takeIf { System.currentTimeMillis()-it.first<600000 }?.let { return@withPermit it.second }
        val result = if(item.provider=="kazplay") {
            val match = try {
                search("kazplay",url.newBuilder().encodedPath("/").query(null).build().toString(),item.title).firstOrNull { it.key==item.key }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(_: Exception) { null }
            if(match != null) CenterDetails(match,emptyList(),"") else details(page(url),item)
        } else details(page(url),item)
        synchronized(detailCache) { detailCache[url.toString()]=System.currentTimeMillis() to result }
        result
    }
}
