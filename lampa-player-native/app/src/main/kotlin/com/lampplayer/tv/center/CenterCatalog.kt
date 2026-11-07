package com.lampplayer.tv.center

import com.google.gson.*
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
        val providers = listOf("tmdb", "kinovod", "filmix", "kinogo", "kinotac", "zona", "kinokong")
        val sites get() = providers.filter { it != "tmdb" }
        fun isSiteIdentity(provider:String?) = provider in sites || provider in listOf("rezka","kazplay","hdrezka")
        fun name(provider: String?) = when(provider) {
            "kinovod" -> "Киновод"; "filmix" -> "Filmix"; "kinotac" -> "KinoTac"; "zona" -> "Zona.movie"; "kinokong" -> "KinoKong"; "rezka" -> "Rezka"; "kazplay" -> "KazPlay · Қазақша"; "hdrezka" -> "HDRezka · LV · тест"; "kinogo" -> "KinoGo · KZ"; else -> "TMDB"
        }
        fun defaultHost(provider: String) = when(provider) {
            "kinovod" -> "https://kinovod.pro"; "filmix" -> "https://filmix.gg"
            "kinotac" -> "https://kinotac.org"; "zona" -> "https://zona.movie"; "kinokong" -> "https://kinokong.es"
            "rezka" -> "https://rezka.ac"; "kazplay" -> "https://kazplay.kz"
            "hdrezka" -> "https://lv.hdrezka.inc"; "kinogo" -> "https://kz.kinogo.moi"
            else -> error("Неизвестный каталог")
        }
        fun sections(provider: String): List<CenterSection> = when(provider) {
            "kinotac", "zona", "kinokong" -> listOf(CenterSection("${provider}_movies","Фильмы","movie"),CenterSection("${provider}_series","Сериалы","tv"))
            "kazplay" -> listOf(CenterSection("kazplay_movies", "Фильмы на казахском", "movie"),
                CenterSection("kazplay_series", "Сериалы на казахском", "tv"))
            "hdrezka" -> listOf(CenterSection("hdrezka_movies","Фильмы · HDRezka","movie"),
                CenterSection("hdrezka_series","Сериалы · HDRezka","tv"))
            "kinogo" -> listOf(CenterSection("kinogo_movies","Фильмы · KinoGo","movie"),
                CenterSection("kinogo_series","Сериалы · KinoGo","tv"),
                CenterSection("kinogo_ru_movies","Русские фильмы","movie","RU"),
                CenterSection("kinogo_ru_series","Русские сериалы","tv","RU"),
                CenterSection("kinogo_kz","Казахстан · фильмы и сериалы","all","KZ"))
            "rezka" -> CenterSection.all.filter { it.country != "KZ" }.map {
                when(it.id) { "new_movies" -> it.copy(label="Фильмы · Rezka"); "new_episodes" -> it.copy(label="Сериалы · Rezka"); else -> it }
            }
            else -> CenterSection.all
        }
        fun route(provider: String, base: String, section: CenterSection, page: Int = 1): HttpUrl {
            require(page in 1..5000)
            val builder = base.trimEnd('/').toHttpUrl().newBuilder().query(null).fragment(null)
            if(provider in listOf("kinotac","zona","kinokong")) {
                require(section.id in sections(provider).map { it.id })
                val path=when(provider) {
                    "kinotac" -> if(section.type=="tv") "/serialy/" else "/filmy/"
                    "zona" -> if(section.type=="tv") "/series/" else "/film/"
                    else -> if(section.type=="tv") "/serialo/" else if(page>1) "/kino/" else "/kinofilmi/"
                }
                return builder.encodedPath(path+if(page>1) "page/$page/" else "").build()
            }
            if(provider == "kazplay") {
                require(section.country == null && section.id in sections(provider).map { it.id })
                return builder.encodedPath(if(section.type=="tv") "/serials" else "/films")
                    .addQueryParameter("page", page.toString()).build()
            }
            if(provider == "kinogo") {
                require(section.id in sections(provider).map { it.id })
                val path = when {
                    section.country=="KZ" -> "/xfsearch/country/казахстан/"
                    section.type=="tv" && section.country=="RU" -> "/serial/russkye_serialy/"
                    section.country=="RU" -> "/film/russkie-filmy/"
                    section.type=="tv" -> "/serial/"
                    else -> "/film/"
                }
                return builder.encodedPath(path + if(page>1) "page/$page/" else "").build()
            }
            if(provider == "hdrezka") {
                require(section.id in sections(provider).map { it.id })
                return builder.encodedPath("/${if(section.type=="tv") "series" else "films"}/" + if(page>1) "page/$page/" else "").build()
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
            if(provider == "hdrezka") return builder.encodedPath("/search/").addQueryParameter("do","search")
                .addQueryParameter("subaction","search").addQueryParameter("q",query).build()
            if(provider=="kinokong")return builder.encodedPath("/index.php").addQueryParameter("do","search")
                .addQueryParameter("subaction","search").addEncodedQueryParameter("story",java.net.URLEncoder.encode(query,"windows-1251")).build()
            if(provider in listOf("rezka","kinogo","kinotac","zona")) return builder.encodedPath("/index.php").addQueryParameter("do","search")
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
            val rows = if(provider in listOf("rezka","hdrezka")) document.select(".b-content__inline_item")
                else document.select("a.group[href^=/film/], a.group[href^=/serial/]")
            val items = rows.mapNotNull { row ->
                val link = if(provider in listOf("rezka","hdrezka")) row.selectFirst(".b-content__inline_item-link a") else row
                val url = link?.absolute("href")?.toHttpUrlOrNull() ?: return@mapNotNull null
                if(url.host != origin.host) return@mapNotNull null
                val rezkaId = Regex("^/(films|series)/(?:[^/]+/)*([0-9]+)-[^/]+\\.html$").find(url.encodedPath)
                if(provider in listOf("rezka","hdrezka") && rezkaId==null) return@mapNotNull null
                if(provider=="kazplay" && !Regex("^/(film|serial)/[^/]+$").matches(url.encodedPath)) return@mapNotNull null
                val id = if(provider in listOf("rezka","hdrezka")) rezkaId!!.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    else catalogId(url.encodedPath)
                val type = if(url.encodedPath.startsWith("/series/") || url.encodedPath.startsWith("/serial/")) "tv" else "movie"
                val title = (if(provider in listOf("rezka","hdrezka")) link.text() else row.selectFirst("p.truncate")?.text()).orEmpty().trim()
                if(title.isBlank()) return@mapNotNull null
                val info = if(provider in listOf("rezka","hdrezka")) row.selectFirst(".misc")?.text() else row.select("p").lastOrNull()?.text()
                val year = Regex("(?:19|20)[0-9]{2}").find(info.orEmpty())?.value.orEmpty()
                val poster = row.poster()?.toHttpUrlOrNull()?.let { image ->
                    if(provider=="kazplay" && image.encodedPath=="/_next/image") image.newBuilder().setQueryParameter("w","384").build().toString()
                    else image.toString()
                }
                CenterTitle(id,type,title,poster=poster,year=year,provider=provider,sourceUrl=url.toString(),
                    rating=if(provider=="kazplay") row.selectFirst("div.absolute.right-2.top-2 span")?.text().orEmpty() else "",
                    updateLabel=if(provider in listOf("rezka","hdrezka")) row.selectFirst(".info")?.text()?.takeIf { it.contains("сери",true) || it.contains("сезон",true) }
                        else row.selectFirst("div.absolute.bottom-2")?.text())
            }.distinctBy { it.key }.take(60)
            val empty = document.text().let { it.contains("не найден",true) || it.contains("ничего не",true) || it.contains("табылмады",true) }
            val catalogEmpty = provider=="kazplay" && rows.isEmpty() && document.select("h1").any { it.text() in listOf("Кинолар","СЕРИАЛДАР") }
            check(items.isNotEmpty() || (rows.isEmpty() && (empty || catalogEmpty))) {
                "${name(provider)}: не удалось прочитать каталог. Проверь адрес сайта"
            }
            return items
        }
        private fun parseKinogo(document: Document): List<CenterTitle> {
            val origin=document.location().toHttpUrl()
            val rows=document.select("div.movie[id]")
            val items=rows.mapNotNull { row ->
                val link=row.selectFirst(".shortstorytitle h2 a") ?: return@mapNotNull null
                val url=link.absolute("href")?.toHttpUrlOrNull() ?: return@mapNotNull null
                if(url.host!=origin.host) return@mapNotNull null
                val id=Regex("^/([0-9]+)-[^/]+\\.html$").find(url.encodedPath)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it>0 } ?: return@mapNotNull null
                if(row.id()!=id.toString()) return@mapNotNull null
                val raw=link.text().trim()
                val badge=row.selectFirst(".movie__info-lenta-body")?.text()?.takeIf { it.isNotBlank() }
                val type=if(badge!=null || Regex("\\d+\\s*сезон",RegexOption.IGNORE_CASE).containsMatchIn(raw)) "tv" else "movie"
                val title=raw.replace(Regex("(?iu)\\s*\\((?:(?:19|20)[0-9]{2}|[0-9]+(?:[-–][0-9]+)?\\s*сезон(?:а|ов)?)\\)\\s*$"),"").trim()
                if(title.isBlank()) return@mapNotNull null
                val info=row.select(".movie__info-item").map { it.text() }
                val year=info.firstOrNull { it.contains("Год выпуска",true) }?.let { Regex("(?:19|20)[0-9]{2}").find(it)?.value }
                    ?: Regex("\\(((?:19|20)[0-9]{2})\\)\\s*$").find(raw)?.groupValues?.get(1).orEmpty()
                val rating=row.selectFirst(".movie__info-rates .kp")?.text()?.takeIf { it.isNotBlank() }
                    ?: row.selectFirst(".movie__info-rates .imdb")?.text().orEmpty()
                CenterTitle(id,type,title,overview=row.selectFirst(".movie__info-desc")?.text().orEmpty().take(8000),
                    poster=row.selectFirst(".movie__info-img")?.poster(),year=year,rating=rating,
                    provider="kinogo",sourceUrl=url.toString(),updateLabel=badge)
            }.distinctBy { it.key }.take(60)
            check(items.isNotEmpty() || (rows.isEmpty() && document.text().let { it.contains("ничего не найден",true) || it.contains("поиск не дал",true) || it.contains("не найдено",true) })) {
                "KinoGo: структура страницы изменилась или сайт запросил проверку. Проверь адрес каталога"
            }
            return items
        }
        private fun parseAddedCatalog(document:Document,provider:String):List<CenterTitle> {
            val origin=document.location().toHttpUrl()
            val scope=document.selectFirst("#dle-content") ?: document
            val rows=when(provider) {
                "kinotac" -> scope.select(".th-item")
                "zona" -> scope.select(".keep-item")
                else -> scope.select("div.item:has(.main-sliders-title a),article.kg-search-hit")
            }
            val items=rows.mapNotNull { row ->
                val link=when(provider) {
                    "kinotac" -> row.selectFirst("a.th-in[href]")
                    "zona" -> row.selectFirst("a.keep-item_title[href]")
                    else -> row.selectFirst(".main-sliders-title a,.kg-search-hit-title")
                } ?: return@mapNotNull null
                val url=link.absolute("href")?.toHttpUrlOrNull() ?: return@mapNotNull null
                if(url.host!=origin.host)return@mapNotNull null
                val match=if(provider=="zona")Regex("^/(film|series)/[^/]+-([0-9]+)/?$").find(url.encodedPath)
                    else Regex("^/([0-9]+)-[^/]+\\.html$").find(url.encodedPath)
                val id=match?.groupValues?.get(if(provider=="zona")2 else 1)?.toIntOrNull()?.takeIf { it>0 } ?: return@mapNotNull null
                val raw=when(provider) {
                    "kinotac" -> row.selectFirst(".th-title")?.text().orEmpty()
                    else -> link.text()
                }.trim()
                val title=raw.replace(Regex("(?iu)\\s*\\((?:(?:19|20)[0-9]{2}|[0-9]+(?:[-–][0-9]+)?\\s*сезон(?:а|ов)?)\\)\\s*$"),"").trim()
                if(title.isBlank())return@mapNotNull null
                val category=row.selectFirst(".th-cat")?.text().orEmpty()
                val info=when(provider) {
                    "kinotac" -> category
                    "zona" -> row.selectFirst(".keep-item_meta")?.text().orEmpty()
                    else -> row.selectFirst(".kg-search-hit-muted,.main-sliders-popup em")?.text().orEmpty()+" "+raw
                }
                val badge=when(provider) {
                    "zona" -> row.selectFirst(".keep-item_meta .bbr1")?.text()
                    "kinokong" -> row.selectFirst(".main-sliders-season,.kg-search-hit-extra")?.text()
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
                val type=when {
                    provider=="zona" -> if(match!!.groupValues[1]=="series") "tv" else "movie"
                    provider=="kinotac" -> if(Regex("(?iu)сериалы|мультсериалы").containsMatchIn(category)||origin.encodedPath.startsWith("/serialy/")) "tv" else "movie"
                    else -> if(origin.encodedPath.startsWith("/serialo/")||Regex("^/[0-9]+-serial-").containsMatchIn(url.encodedPath)||Regex("(?iu)сезон|серия|серии").containsMatchIn(raw+" "+badge.orEmpty())) "tv" else "movie"
                }
                val update=badge?.let { label ->
                    Regex("(?i)^([0-9]{1,2})s([0-9]{1,4})e$").matchEntire(label)?.let { "${it.groupValues[1]} сезон ${it.groupValues[2]} серия" } ?: label
                }?.takeIf { type=="tv" }
                val year=(if(provider=="zona")row.select(".keep-item_meta span").map { it.text().trim() }.firstOrNull { Regex("(?:19|20)[0-9]{2}").matches(it) } else null)
                    ?: Regex("(?<![0-9])(?:19|20)[0-9]{2}(?![0-9])").find(info)?.value.orEmpty()
                val cover=if(provider=="kinokong") row.selectFirst(".main-sliders-img > img,.kg-search-hit-poster img") else null
                val poster=cover?.let { image -> listOf("data-src","data-original","src").firstNotNullOfOrNull { image.absolute(it) } } ?: row.poster()
                CenterTitle(id,type,title,
                    overview=row.selectFirst(".kg-search-hit-description,.main-sliders-popup i")?.text().orEmpty().take(8000),
                    poster=poster,year=year,
                    rating=row.selectFirst(if(provider=="zona") ".rating-color" else ".main-sliders-kp,.main-sliders-imdb,.kg-search-score-kp")?.text().orEmpty(),
                    provider=provider,sourceUrl=url.toString(),updateLabel=update)
            }.distinctBy { it.key }.take(60)
            val empty=document.text().let { it.contains("ничего не найден",true)||it.contains("поиск не дал",true)||it.contains("не найдено",true)||it.contains("не найден",true) }
            check(items.isNotEmpty()||rows.isEmpty()&&empty) { "${name(provider)}: не удалось прочитать карточки. Проверь адрес каталога" }
            return items
        }
        fun parse(document: Document, provider: String): List<CenterTitle> {
            if(provider in listOf("kinotac","zona","kinokong"))return parseAddedCatalog(document,provider)
            if(provider=="kinogo") return parseKinogo(document)
            if(provider in listOf("rezka","kazplay","hdrezka")) return parseNew(document,provider)
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

        /** Only declared non-trailer video tabs on the verified selected KinoKong page. */
        fun kinokongEmbeds(document:Document,item:CenterTitle):List<String> {
            require(item.provider=="kinokong")
            check(CenterOnline.sameTitleNavigation(item,document.location())) { "KinoKong открыл другую страницу вместо выбранной карточки" }
            check(document.getElementById("full_${item.id}")!=null) { "KinoKong не отдал выбранную карточку: проверь доступность страницы в плеере сайта" }
            val base=document.location().toHttpUrlOrNull() ?: error("Нет адреса карточки")
            return document.select(".newTabs > li[data-iframe]").mapNotNull { tab ->
                if(tab.hasClass("trail")||Regex("(?iu)трейлер|trailer").containsMatchIn(tab.text()))return@mapNotNull null
                if(tab.parents().any { p -> p.id().startsWith("comm-")||p.classNames().any { it.startsWith("comment")||it.startsWith("comm-") } ||
                    p.id().startsWith("full_")&&p.id()!="full_${item.id}" })return@mapNotNull null
                val value=tab.attr("data-iframe").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val url=base.resolve(value) ?: return@mapNotNull null
                if(Regex("(?i)^/(?:trailer|trailer-cdn)(?:/|$)").containsMatchIn(url.encodedPath)||CenterOnline.sameTitleNavigation(item,url.toString()))return@mapNotNull null
                url.toString()
            }.distinct().take(3)
        }

        /** Only iframe declarations inside the selected Zona video panels. */
        fun zonaEmbeds(document:Document,item:CenterTitle):List<String> {
            require(item.provider=="zona")
            check(CenterOnline.sameTitleNavigation(item,document.location())) { "Zona.movie открыла другую карточку" }
            val base=document.location().toHttpUrlOrNull() ?: error("Нет адреса карточки")
            return document.select(".plays > .tabs-box2 > .tabs-b2 iframe").mapNotNull { frame ->
                if(frame.parents().any { p -> p.id().startsWith("comm-")||p.classNames().any {
                    it.startsWith("comment")||it in setOf("comm-item","advertisement","trailer","trail","tmdbview") } })return@mapNotNull null
                val panel=frame.parents().firstOrNull { it.hasClass("tabs-b2") } ?: return@mapNotNull null
                if(Regex("(?iu)трейлер|trailer").containsMatchIn(panel.attr("data-title")))return@mapNotNull null
                val values=listOf(frame.attr("src"),frame.attr("data-src"))
                val url=values.firstNotNullOfOrNull { value -> value.trim().takeIf { it.isNotEmpty()&&it!="about:blank" }?.let { base.resolve(it) } }
                    ?: return@mapNotNull null
                if(CenterOnline.sameTitleNavigation(item,url.toString())||
                    Regex("(?i)^/(?:trailer|trailer-cdn)(?:/|$)").containsMatchIn(url.encodedPath))return@mapNotNull null
                url.toString()
            }.distinct().take(2)
        }

        /** KinoTac's video iframe panels; SDK IDs and trailer panels are not stream addresses. */
        fun kinotacEmbeds(document:Document,item:CenterTitle):List<String> {
            require(item.provider=="kinotac")
            check(CenterOnline.sameTitleNavigation(item,document.location())) { "KinoTac открыл другую карточку" }
            val article=document.selectFirst("article.full") ?: error("KinoTac не отдал выбранную карточку")
            val declared=document.selectFirst("input[name=post_id]")?.attr("value")?.toIntOrNull()
            check(declared==null||declared==item.id) { "KinoTac отдал другую карточку" }
            val base=document.location().toHttpUrlOrNull() ?: error("Нет адреса карточки")
            return article.select(".pmovie__player .tabs-block__content.fplayer iframe").mapNotNull { frame ->
                if(frame.parents().any { p -> p.id().startsWith("comm")||p.id()=="full-comms"||p.classNames().any {
                    it.startsWith("comment")||it in setOf("comm-item","advertisement","ads","fplay","trailer","trail") } })return@mapNotNull null
                val url=listOf(frame.attr("src"),frame.attr("data-src")).firstNotNullOfOrNull { value ->
                    value.trim().takeIf { it.isNotEmpty()&&it!="about:blank" }?.let { base.resolve(it) }
                } ?: return@mapNotNull null
                if(CenterOnline.sameTitleNavigation(item,url.toString())||
                    url.pathSegments.any { it.equals("trailer",true)||it.equals("trailer-cdn",true) })return@mapNotNull null
                url.toString()
            }.distinct().take(3)
        }

        private fun mediaMetadata(document:Document,item:CenterTitle):JsonObject? {
            fun text(node:JsonObject,key:String)=node.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            fun title(value:String)=Jsoup.parse(value).text().replace(Regex("(?iu)\\s*\\((?:(?:19|20)[0-9]{2}|[0-9]+(?:[-–][0-9]+)?\\s*сезон(?:а|ов)?)\\)\\s*$"),"")
                .lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
            val candidates=mutableListOf<JsonObject>();val legacy=mutableListOf<JsonObject>()
            fun collect(value:JsonElement,depth:Int) {
                if(depth>3||candidates.size>=64)return
                if(value.isJsonArray) { value.asJsonArray.take(64).forEach { collect(it,depth+1) };return }
                if(!value.isJsonObject)return
                val obj=value.asJsonObject;val kind=obj.get("@type")
                val types=if(kind?.isJsonArray==true)kind.asJsonArray.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }
                    else listOfNotNull(kind?.takeIf { it.isJsonPrimitive }?.asString)
                if(types.any { it in listOf("Movie","TVSeries") })candidates+=obj
                else if(depth==0&&types.isEmpty()&&!obj.has("@graph")&&obj.has("description"))legacy+=obj
                obj.get("@graph")?.let { collect(it,depth+1) }
            }
            document.select("script[type=application/ld+json]").take(16).forEach {
                runCatching { collect(JsonParser.parseString(it.data()),0) }
            }
            val targets=listOfNotNull(document.location().toHttpUrlOrNull(),item.sourceUrl?.toHttpUrlOrNull())
            val name=title(item.title)
            fun match(node:JsonObject):Boolean {
                val candidateName=text(node,"name")
                if(candidateName.isNotBlank()&&title(candidateName)!=name)return false
                val page=node.get("mainEntityOfPage")
                val pageUrl=when { page?.isJsonPrimitive==true -> page.asString
                    page?.isJsonObject==true -> text(page.asJsonObject,"@id").ifBlank { text(page.asJsonObject,"url") };else -> "" }
                val address=text(node,"url").ifBlank { pageUrl }.ifBlank { text(node,"@id") }
                if(address.isNotBlank()&&!address.startsWith("#")) {
                    val url=targets.firstOrNull()?.resolve(address) ?: return false
                    return targets.any { it.host==url.host&&it.port==url.port&&it.encodedPath.trimEnd('/')==url.encodedPath.trimEnd('/') }
                }
                return candidateName.isNotBlank()
            }
            return candidates.firstOrNull { match(it) } ?: legacy.singleOrNull()?.takeIf {
                text(it,"name").isBlank()&&text(it,"url").isBlank()&&text(it,"@id").isBlank()
            }
        }
        fun details(document: Document, item: CenterTitle): CenterDetails {
            val added=item.provider in listOf("kinotac","zona","kinokong")
            if(added) {
                val path=document.location().toHttpUrl().encodedPath
                val id=if(item.provider=="zona")Regex("^/(film|series)/[^/]+-([0-9]+)/?$").find(path)?.groupValues?.get(2)?.toIntOrNull()
                    else Regex("^/([0-9]+)-[^/]+\\.html$").find(path)?.groupValues?.get(1)?.toIntOrNull()
                check(id==item.id&&(item.provider!="zona"||(if(path.startsWith("/series/"))"tv" else "movie")==item.type)) {
                    "Сайт открыл другую страницу вместо выбранной карточки"
                }
            }
            val structured=mediaMetadata(document,item)
            fun str(key:String)=structured?.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            fun clean(value:String)=Jsoup.parse(value).text().trim()
            fun usable(element:Element)=(listOf(element)+element.parents()).none { parent ->
                parent.id().startsWith("comm-")||parent.classNames().any { it.startsWith("comment")||it in listOf("comm","comm-item","dle-comm_item") }
            }
            fun description(selector:String):String?=document.select(selector).firstNotNullOfOrNull { element ->
                if(!usable(element)||element.tagName() in listOf("meta","script"))null else {
                    val copy=element.clone();copy.select("script,style,iframe,form").remove()
                    val paragraphs=copy.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
                    (if(paragraphs.isNotEmpty())paragraphs.joinToString("\n\n") else copy.text()).trim().takeIf { it.isNotBlank() }
                }
            }
            val specific=when(item.provider) {
                "kinotac" -> "article.full .descriptionnew"
                "zona" -> ".keep-detal_text"
                "kinokong" -> "#full_${item.id} .full-kino-story"
                else -> ".b-post__description_text"
            }
            val structuredSynopsis=str("description").takeIf { it.isNotBlank() }?.let(::clean)
            val synopsis=description(specific)
                ?: (if(added)structuredSynopsis ?: description("[itemprop=description]") else description("[itemprop=description]") ?: structuredSynopsis)
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.let(::clean)?.takeIf { it.isNotBlank() }
            fun people(value:JsonElement?):List<String> = when {
                value==null||value.isJsonNull -> emptyList()
                value.isJsonArray -> value.asJsonArray.take(24).flatMap { people(it) }
                value.isJsonObject -> listOf(value.asJsonObject.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
                value.isJsonPrimitive -> value.asString.split(',')
                else -> emptyList()
            }
            val names=(people(structured?.get("actor"))+people(structured?.get("actors"))).map(::clean).filter { it.isNotEmpty() }
            val metadataRoot=when(item.provider) {
                "kinotac" -> document.selectFirst("article.full")
                "kinokong" -> document.getElementById("full_"+item.id)
                "zona" -> document.selectFirst(".keep-full")
                else -> null
            } ?: document
            val microdata=metadataRoot.select("[itemprop=actor]").filter(::usable).mapNotNull { actor ->
                actor.select("[itemprop=name]").map { it.attr("content").ifBlank { it.text() } }.filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }?.joinToString(", ") ?: actor.text().takeIf { it.isNotBlank() }
            }
            val oldCast=document.select(".item.actors").firstOrNull { it.text().contains("В ролях") }?.selectFirst(".item-content")?.text().orEmpty()
            val cast=(if(names.isNotEmpty())names else microdata.flatMap { it.split(',') }.ifEmpty { listOf(oldCast) })
                .map(::clean).filter { it.isNotEmpty() }.distinct().take(24).joinToString(", ").take(2000)
            fun image(element:Element?):String?=element?.let { el ->
                listOf("data-src","data-original","src","content").firstNotNullOfOrNull { attr ->
                    el.attr(attr).takeIf { it.isNotBlank() }?.let { document.location().toHttpUrl().resolve(it) }?.toString()
                }
            }
            val social=document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?.let { document.location().toHttpUrlOrNull()?.resolve(it)?.toString() }
            val portrait=when(item.provider) {
                "zona" -> image(document.selectFirst(".keep-full_poster img"))
                "kinotac" -> image(document.selectFirst("article.full .images img[itemprop=image]"))
                "kinokong" -> item.poster ?: image(document.selectFirst("#full_"+item.id+" .full-kino-story img"))
                else -> null
            }
            val background=if(item.provider=="zona")image(document.selectFirst(".keep-full_bg img.posterbg")) else null
            val year=if(item.year.isNotBlank())item.year else {
                val copyright=metadataRoot.select("[itemprop=copyrightYear]").firstNotNullOfOrNull { element ->
                    Regex("^(?:19|20)[0-9]{2}$").find(element.attr("content").ifBlank { element.text() }.trim())?.value
                }
                val rowYear=if(item.provider=="kinokong")document.select("#full_${item.id} .full-kino-info > div").firstNotNullOfOrNull {
                    Regex("(?iu)^Год\\s*:\\s*((?:19|20)[0-9]{2})$").find(it.text().trim())?.groupValues?.get(1)
                } else null
                copyright ?: rowYear ?: str("datePublished").takeIf { item.provider=="zona"&&Regex("(?:19|20)[0-9]{2}").matches(it) }.orEmpty()
            }
            val original=item.original.ifBlank { clean(str("alternateName")) }
            val poster=if(added)portrait ?: item.poster ?: social else if(item.provider=="kazplay")item.poster ?: social else social ?: item.poster
            val backdrop=if(added)background ?: item.backdrop else if(item.provider=="kazplay")social ?: item.backdrop else item.backdrop
            return CenterDetails(item.copy(overview=synopsis?.take(8000) ?: item.overview,poster=poster,backdrop=backdrop,
                year=year,original=original),emptyList(),cast)
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
                    check(response.isSuccessful) { if(response.code==503) "Сайт каталога временно недоступен (HTTP 503). Попробуй позднее или выбери другой каталог" else "Каталог вернул HTTP ${response.code}" }
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
        val parsed=parse(document,provider)
        val items=if(provider in listOf("kinotac","zona","kinokong"))parsed.filter { it.type==section.type } else parsed
        val hasNext = if(provider in listOf("rezka","kazplay","hdrezka","kinogo","kinotac","zona","kinokong")) {
            val next=route(provider,base,section,number+1)
            document.select("a[href]").any { link ->
                link.absolute("href")?.toHttpUrlOrNull()?.let { it.host==next.host && it.encodedPath.trimEnd('/')==next.encodedPath.trimEnd('/') &&
                    (if(provider=="kazplay") it.queryParameter("page")==next.queryParameter("page") else true) } == true
            }
        } else null
        synchronized(cache) { cache[url.toString()]=Cache(System.currentTimeMillis(),items,hasNext) }
        return items
    }
    suspend fun search(provider:String,base:String,query:String):List<CenterTitle> {
        val url=searchRoute(provider,base,query)
        if(provider in listOf("kinovod","rezka","hdrezka","kinogo","kinotac","zona","kinokong")) return parse(page(url),provider)
        val data=request(url)
        return withContext(Dispatchers.IO) {
            if(provider=="kazplay") kazplaySearch(data.bytes.toString(Charsets.UTF_8),data.url)
            else suggestions(data.bytes.toString(Charsets.UTF_8),data.url)
        }
    }
    suspend fun onlineFrames(item:CenterTitle):List<String> {
        require(item.provider in listOf("kinokong","zona","kinotac"))
        val selected=CenterOnline.source(item)
        val document=page(selected)
        val frames=when(item.provider) {
            "zona" -> zonaEmbeds(document,item)
            "kinotac" -> kinotacEmbeds(document,item)
            else -> kinokongEmbeds(document,item)
        }
        check(frames.isNotEmpty()) { if(item.provider=="zona")
            "Zona.movie не отдал адреса видеоплееров для выбранной карточки. Открой плеер сайта или выбери другой источник"
            else if(item.provider=="kinotac") "KinoTac не отдал адреса видеоплееров. Открой плеер сайта или выбери другой источник"
            else "На выбранной странице KinoKong не найдены видеоплееры. Открой плеер сайта или выбери другой источник" }
        return frames
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
