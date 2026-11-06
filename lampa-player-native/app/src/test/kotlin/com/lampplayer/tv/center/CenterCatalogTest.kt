package com.lampplayer.tv.center

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.lampplayer.tv.data.datastore.PositionDataStore
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.domain.model.PlaybackPosition
import com.lampplayer.tv.player.IntentParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class CenterCatalogTest {
    @Test fun countryAndTypeFiltersUseRealProviderRoutesAndPagination() {
        val ru=CenterSection.all.first { it.id=="ru_series" }
        val kz=CenterSection.all.first { it.id=="kz_movies" }
        assertEquals("/serials/rus",CenterCatalog.route("kinovod","https://catalog.test",ru).encodedPath)
        assertEquals("3",CenterCatalog.route("kinovod","https://catalog.test",kz,3).queryParameter("page"))
        assertEquals("/films/kaz",CenterCatalog.route("kinovod","https://catalog.test",kz).encodedPath)
        assertEquals("/seria/c6/pages/2/",CenterCatalog.route("filmix","https://catalog.test",ru,2).encodedPath)
        assertEquals("/film/c14/",CenterCatalog.route("filmix","https://catalog.test",kz).encodedPath)
        assertEquals("Мой сериал",CenterCatalog.searchRoute("kinovod","https://catalog.test","Мой сериал").queryParameter("query"))
        assertEquals("Мой сериал",CenterCatalog.searchRoute("filmix","https://catalog.test","Мой сериал").queryParameter("search_word"))
    }
    @Test fun kinovodCardsKeepProviderIdentityAndEpisodeUpdateBadge() {
        val doc=Jsoup.parse("""<ul class="items"><li class="item"><div class="poster"><img src="/poster.jpg"><div class="label">2 сезон 4 серия</div></div>
            <div class="title"><a href="/serial/17-my-series">Мой сериал</a></div><span class="year">2026, HDRip</span><span class="rating">7.9</span></li>
            <li class="item"><div class="title"><a href="https://advert.test/film/42-spam">Ad</a></div></li></ul>""","https://catalog.test/serials")
        val item=CenterCatalog.parse(doc,"kinovod").single()
        assertEquals("kinovod:tv:17",item.key);assertEquals("2 сезон 4 серия",item.updateLabel)
        assertEquals("2026",item.year);assertEquals("https://catalog.test/poster.jpg",item.poster)
        assertEquals("https://catalog.test/serial/17-my-series",item.sourceUrl)
    }
    @Test fun filmixWindows1251AndHtmlEntitiesAreDecodedWithoutGuessingIdsFromTmdb() {
        val html="""<html><head><meta charset="windows-1251"></head><body><article class="shortstory" data-id="17">
            <img class="poster" src="/poster.jpg"><h2 class="name"><a itemprop="url" href="/film/drama/17-test.html">Тест &amp; кино</a></h2>
            <span itemprop="copyrightYear">2025</span><p itemprop="description">Казахстан, новая история</p></article></body></html>"""
        val doc=Jsoup.parse(ByteArrayInputStream(html.toByteArray(charset("windows-1251"))),null,"https://catalog.test/film/")
        val item=CenterCatalog.parse(doc,"filmix").single()
        assertEquals("Тест & кино",item.title);assertEquals("filmix:movie:17",item.key)
        assertEquals("Казахстан, новая история",item.overview)
    }
    @Test fun providerDetailsReadSynopsisAndCastWithoutInventingSeasons() {
        val doc=Jsoup.parse("""<script type="application/ld+json">{"description":"Сюжет","actor":[{"name":"Актёр"}]}</script>""","https://catalog.test")
        val details=CenterCatalog.details(doc,CenterTitle(17,"tv","Series",provider="kinovod"))
        assertEquals("Сюжет",details.title.overview);assertEquals("Актёр",details.actors);assertTrue(details.seasons.isEmpty())
    }
    @Test fun filmixMobileCardsUseTheMovieElementIdAndEpisodeBadgeNotQualityText() {
        val doc=Jsoup.parse("""<article class="movie article line" id="movie-17"><a class="m-movie-item" href="/seria/drama/17-show.html">
            <img itemprop="image" src="/poster.jpg"><h2 class="m-movie-title" itemprop="name">Мой сериал</h2>
            <div class="m-movie-original">My show</div><div class="m-movie-add">Добавлена: <span class="serials">1-15 серия (1 сезон)</span></div>
            <div class="added-info">WEBRip 1080</div><div class="grid-item">2023</div></a></article>""","https://catalog.test/seria/c14/")
        val item=CenterCatalog.parse(doc,"filmix").single()
        assertEquals("filmix:tv:17",item.key);assertEquals("Мой сериал",item.title);assertEquals("2023",item.year)
        assertEquals("1-15 серия (1 сезон)",item.updateLabel);assertEquals("https://catalog.test/poster.jpg",item.poster)
    }
    @Test fun filmixSearchUsesNativeSiteSuggestionIdsLinksAndUpdateLabels() {
        val items=CenterCatalog.suggestions("""{"posts":[{"id":17,"title":"Мой &amp; сериал","link":"/seria/drama/17-test.html","poster":"/poster.jpg","year":2026,"last_serie":"3 серия"}],"persons":[]}""","https://catalog.test/api/v2/suggestions")
        val item=items.single();assertEquals("filmix:tv:17",item.key);assertEquals("Мой & сериал",item.title)
        assertEquals("https://catalog.test/seria/drama/17-test.html",item.sourceUrl);assertEquals("3 серия",item.updateLabel)
    }
    @Test fun websiteLoginAndChallengePagesAreNotAnEmptyCatalog() {
        try { CenterCatalog.parse(Jsoup.parse("<form>Login</form>","https://catalog.test"),"filmix");fail("Accepted login page") }
        catch (_:IllegalStateException) {}
        assertTrue(CenterCatalog.parse(Jsoup.parse("Ничего не найдено","https://catalog.test"),"filmix").isEmpty())
    }
    @Test fun favoritesPinsAndHistoryDoNotCollideAcrossCatalogIds() {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("media_center",0).edit().clear().commit()
        val store=CenterStore(app)
        val items=listOf(CenterTitle(17,"movie","TMDB"),CenterTitle(17,"movie","KinoVod",provider="kinovod"),CenterTitle(17,"movie","Filmix",provider="filmix"))
        items.forEach { store.toggle(it);store.pin(it);store.remember(it.key,CardMeta(it.title,contentKey=it.key)) }
        assertEquals(3,store.favorites().size);assertEquals(3,store.pinned().size);assertEquals(3,store.history().size)
        store.pin(items[1]);assertEquals(2,store.pinned().size);assertTrue(store.isPinned(items[2]))
    }
    @Test fun providerMetadataSurvivesIntentParsingAndPortRotationResume() = runBlocking {
        val intent=Intent(Intent.ACTION_VIEW,Uri.parse("http://127.0.0.1:1234/stream/test.mkv?link=abc&index=1"))
            .putExtra("lampa_data","""{"title":"Мой сериал","content_key":"kinovod:tv:17","source_page":"https://catalog.test/serial/17-test","season_number":1,"episode_number":2}""")
        val card=IntentParser.parse(intent)!!.second
        assertNull(card.tmdbId);assertEquals("kinovod:tv:17",card.contentKey);assertEquals("https://catalog.test/serial/17-test",card.sourcePage)
        val positions=PositionDataStore(RuntimeEnvironment.getApplication())
        positions.savePosition(intent.dataString!!,card,PlaybackPosition(time=123.0))
        assertEquals(123.0,positions.getPosition("http://127.0.0.1:9999/stream/test.mkv",card)!!.time,0.01)
        assertNull(positions.episodeKey(card.copy(episodeNumber=null)))
    }
    @Test fun websiteCaptureAcceptsOnlyHttpMediaManifestsAndFullMp4Files() {
        assertTrue(CenterWebActivity.isVideo("https://cdn.test/master.m3u8?token=abc"))
        assertTrue(CenterWebActivity.isVideo("https://cdn.test/movie.MP4"))
        assertFalse(CenterWebActivity.isVideo("https://cdn.test/segment.ts"))
        assertFalse(CenterWebActivity.isVideo("javascript:movie.mp4"))
    }
    @Test fun newProvidersHaveObservedRoutesAndOnlySupportedSections() {
        val rezka=CenterCatalog.sections("rezka")
        assertEquals(4,rezka.size)
        assertFalse(rezka.any { it.country=="KZ" })
        assertEquals("/films/page/2/",CenterCatalog.route("rezka","https://catalog.test",rezka.first { it.type=="movie" && it.country==null },2).encodedPath)
        assertEquals("/series/russkie/",CenterCatalog.route("rezka","https://catalog.test",rezka.first { it.type=="tv" && it.country=="RU" }).encodedPath)
        assertEquals("/films/russian/",CenterCatalog.route("rezka","https://catalog.test",rezka.first { it.type=="movie" && it.country=="RU" }).encodedPath)
        val kz=CenterCatalog.sections("kazplay")
        assertEquals(listOf("Фильмы на казахском","Сериалы на казахском"),kz.map { it.label })
        assertTrue(kz.all { it.country==null })
        assertEquals("/serials",CenterCatalog.route("kazplay","https://catalog.test",kz[1],3).encodedPath)
        assertEquals("3",CenterCatalog.route("kazplay","https://catalog.test",kz[1],3).queryParameter("page"))
        val search=CenterCatalog.searchRoute("rezka","https://catalog.test","Матрица & фильм")
        assertEquals("/index.php",search.encodedPath);assertEquals("Матрица & фильм",search.queryParameter("story"))
        assertEquals("search",search.queryParameter("do"))
        val kazSearch=CenterCatalog.searchRoute("kazplay","https://catalog.test","Титаник")
        assertEquals("/api/v2/film/search",kazSearch.encodedPath);assertEquals("Титаник",kazSearch.queryParameter("query"))
    }
    @Test fun rezkaCardsReadStableIdsTitlesYearsAndLazyPostersButRejectForeignLinks() {
        val doc=Jsoup.parse("""<div class="b-content__inline_item" data-id="28626">
            <div class="b-content__inline_item-cover"><a href="/series/drama/28626-show.html"><img src="/templates/2rezka/images/rezka-img.webp" data-src="/poster.jpg"></a>
            <span class="info">2 сезон, 5 серия</span></div>
            <div class="b-content__inline_item-link"><a href="/series/drama/28626-show.html">Сериал</a><div class="misc">2026 драма</div></div></div>
            <div class="b-content__inline_item"><div class="b-content__inline_item-link"><a href="https://foreign.test/films/12-film.html">Wrong</a></div></div>""","https://catalog.test/series/")
        val item=CenterCatalog.parse(doc,"rezka").single()
        assertEquals("rezka:tv:28626",item.key);assertEquals("Сериал",item.title);assertEquals("2026",item.year)
        assertEquals("https://catalog.test/poster.jpg",item.poster);assertEquals("2 сезон, 5 серия",item.updateLabel)
    }
    @Test fun kazplayCardsAndSearchUseTheSameMirrorIndependentIdentityAndRussianSearchTitle() {
        val html="""<a class="group block" href="/film/titanik-qazaqsha-audarmasy">
            <img src="/_next/image?url=%2Fmedia%2Fposter.jpg&amp;w=3840&amp;q=68">
            <div class="absolute right-2 top-2"><span>7.9</span></div>
            <div class="mt-2"><p class="truncate">Титаник қазақша аудармасы</p><p>1997 · Драма</p></div></a>"""
        val card=CenterCatalog.parse(Jsoup.parse(html,"https://catalog.test/films"),"kazplay").single()
        assertEquals("1997",card.year);assertEquals("7.9",card.rating);assertEquals("movie",card.type)
        assertEquals("384",android.net.Uri.parse(card.poster).getQueryParameter("w"))
        val search=CenterCatalog.kazplaySearch("""{"success":true,"data":[{"id":475,"slug":"titanik-qazaqsha-audarmasy","title":"Титаник қазақша аудармасы",
            "rus_title":"Титаник","date_release":1997,"is_serial":0,"poster_media_url":"/media/real.jpg","description":"<p>Описание</p>","rating":7.9}]}""","https://mirror.test/api/v2/film/search")
            .single()
        assertEquals(card.key,search.key);assertEquals("Титаник",search.original);assertEquals("Описание",search.overview)
        assertEquals("https://mirror.test/film/titanik-qazaqsha-audarmasy",search.sourceUrl)
        assertEquals("https://mirror.test/media/real.jpg",search.poster)
        assertEquals(card.key,CenterCatalog.parse(Jsoup.parse(html,"https://another.test/films"),"kazplay").single().key)
    }
    @Test fun kazplaySeriesNeverBecomeMoviesAndMalformedSearchDataIsRejected() {
        val item=CenterCatalog.kazplaySearch("""{"success":true,"data":[{"slug":"erke-mun-boztorgay","title":"Ерке мұң.","date_release":2013,"is_serial":1},
            {"slug":"https://foreign.test/title","title":"Wrong"}]}""","https://catalog.test").single()
        assertEquals("tv",item.type);assertTrue(item.sourceUrl!!.contains("/serial/"))
        val html="""<a class="group" href="/serial/erke-mun-boztorgay"><img src="/poster.jpg">
            <div class="absolute bottom-2">71 бөлім</div><p class="truncate">Ерке мұң.</p><p>2013 · Драма</p></a>"""
        val card=CenterCatalog.parse(Jsoup.parse(html,"https://catalog.test/serials"),"kazplay").single()
        assertEquals(item.key,card.key);assertEquals("71 бөлім",card.updateLabel)
        try { CenterCatalog.kazplaySearch("""{"success":false,"data":[]}""","https://catalog.test");fail("Accepted API failure") }
        catch(_:IllegalStateException) {}
    }
    @Test fun newCatalogEmptyPagesAreDistinctFromLoginOrChangedMarkup() {
        assertTrue(CenterCatalog.parse(Jsoup.parse("<h1>Кинолар</h1>","https://catalog.test/films?page=99"),"kazplay").isEmpty())
        assertTrue(CenterCatalog.parse(Jsoup.parse("Ничего не найдено","https://catalog.test/index.php"),"rezka").isEmpty())
        for(provider in listOf("rezka","kazplay")) {
            try { CenterCatalog.parse(Jsoup.parse("<form>Login</form>","https://catalog.test"),provider);fail("Accepted login page") }
            catch(_:IllegalStateException) {}
        }
    }
    @Test fun siteDetailsKeepPortraitCoversAndUseMetadataWithoutLoadingPlayback() {
        val item=CenterTitle(42,"movie","Фильм",poster="https://catalog.test/portrait.jpg",provider="kazplay")
        val doc=Jsoup.parse("""<meta property="og:description" content="Сюжет"><meta property="og:image" content="/landscape.jpg">""","https://catalog.test")
        val detail=CenterCatalog.details(doc,item)
        assertEquals(item.poster,detail.title.poster);assertEquals("https://catalog.test/landscape.jpg",detail.title.backdrop)
        assertEquals("Сюжет",detail.title.overview);assertTrue(detail.seasons.isEmpty())
        val rezka=CenterCatalog.details(Jsoup.parse("""<div class="b-post__description_text">Полный сюжет</div>
            <meta property="og:description" content="Рекламное описание">""","https://catalog.test"),item.copy(provider="rezka"))
        assertEquals("Полный сюжет",rezka.title.overview)
    }
    @Test fun newCatalogSelectionsAndTheirOwnMirrorAddressesPersist() {
        val app=RuntimeEnvironment.getApplication()
        val store=CenterStore(app)
        for(provider in listOf("kinotac","zona","kinokong")) {
            store.catalog=provider;assertEquals(provider,CenterStore(app).catalog)
            assertTrue(store.catalogHost(provider).startsWith("https://"))
            store.setCatalogHost(provider,"https://$provider.test")
            assertEquals("https://$provider.test",CenterStore(app).catalogHost(provider))
        }
        assertEquals("https://kinotac.test",store.catalogHost("kinotac"))
    }

    @Test fun suppliedDomainsHaveIndependentEntriesAndObservedKinogoRoutes() {
        assertEquals("https://lv.hdrezka.inc",CenterCatalog.defaultHost("hdrezka"))
        assertEquals("https://kz.kinogo.moi",CenterCatalog.defaultHost("kinogo"))
        assertTrue(CenterCatalog.name("hdrezka").contains("тест"))
        val sections=CenterCatalog.sections("kinogo")
        val kz=sections.single { it.country=="KZ" }
        assertEquals("all",kz.type);assertEquals("Казахстан · фильмы и сериалы",kz.label)
        val url=CenterCatalog.route("kinogo","https://catalog.test",kz,2)
        assertEquals(listOf("xfsearch","country","казахстан","page","2",""),url.pathSegments)
        assertEquals("/film/russkie-filmy/",CenterCatalog.route("kinogo","https://catalog.test",sections.single { it.type=="movie" && it.country=="RU" }).encodedPath)
        assertEquals("/serial/russkye_serialy/page/2/",CenterCatalog.route("kinogo","https://catalog.test",sections.single { it.type=="tv" && it.country=="RU" },2).encodedPath)
        assertEquals("Матрица & фильм",CenterCatalog.searchRoute("kinogo","https://catalog.test","Матрица & фильм").queryParameter("story"))
        assertEquals("Матрица",CenterCatalog.searchRoute("hdrezka","https://lv.hdrezka.inc","Матрица").queryParameter("q"))
    }
    @Test fun kinogoNativeRowsReadRealIdsLazyCoversBadgesYearsAndRatings() {
        val doc=Jsoup.parse("""<div class="movie" id="62752"><div class="shortstorytitle"><h2><a href="/62752-sintetika.html">Синтетика (2026)</a></h2>
            <span class="rating__votes">3.2/5</span></div><div class="movie__info-img"><img src="/templates/Kinogo/images/lazy-poster.png" data-src="/uploads/poster.jpg"></div>
            <div class="movie__info-item">Год выпуска: 2026</div><div class="movie__info-rates"><span class="kp">KP 7.4</span></div>
            <div class="movie__info-desc">Сюжет фильма</div></div>
            <div class="movie" id="62687"><div class="shortstorytitle"><h2><a href="/62687-show.html">Сериал (1-2 сезон)</a></h2></div>
            <div class="movie__info-img"><div class="movie__info-lenta-body">2 сезон 5 серия</div><img data-src="/uploads/show.jpg"></div>
            <div class="movie__info-item">Год выпуска: 2024</div></div>
            <div class="movie" id="12"><div class="shortstorytitle"><h2><a href="https://foreign.test/12-other.html">Чужой фильм (2025)</a></h2></div></div>""","https://kz.kinogo.moi/")
        val items=CenterCatalog.parse(doc,"kinogo")
        assertEquals(2,items.size)
        assertEquals("kinogo:movie:62752",items[0].key);assertEquals("Синтетика",items[0].title)
        assertEquals("2026",items[0].year);assertEquals("https://kz.kinogo.moi/uploads/poster.jpg",items[0].poster)
        assertEquals("KP 7.4",items[0].rating);assertEquals("Сюжет фильма",items[0].overview)
        assertEquals("kinogo:tv:62687",items[1].key);assertEquals("Сериал",items[1].title)
        assertEquals("2024",items[1].year);assertEquals("2 сезон 5 серия",items[1].updateLabel)
        assertEquals(items.map { it.key },CenterCatalog.parse(Jsoup.parse(doc.outerHtml(),"https://mirror.test"),"kinogo").map { it.key })
    }
    @Test fun hdrezkaTestParserKeepsItsIdentitySeparateAndChallengePagesFailHonestly() {
        val html="""<div class="b-content__inline_item"><div class="b-content__inline_item-link">
            <a href="/series/drama/17-show.html">Сериал</a><div class="misc">2025, драма</div></div><img src="/poster.jpg"></div>"""
        val doc=Jsoup.parse(html,"https://lv.hdrezka.inc")
        val item=CenterCatalog.parse(doc,"hdrezka").single()
        assertEquals("hdrezka:tv:17",item.key)
        assertNotEquals(CenterCatalog.parse(doc,"rezka").single().key,item.key)
        for(provider in listOf("hdrezka","kinogo")) {
            try { CenterCatalog.parse(Jsoup.parse("<h1>Service unavailable</h1>","https://catalog.test"),provider);fail("Accepted a challenge or outage") }
            catch(_:IllegalStateException) {}
        }
        assertTrue(CenterCatalog.parse(Jsoup.parse("По вашему запросу ничего не найдено","https://catalog.test"),"kinogo").isEmpty())
    }


    @Test fun replacementProvidersUseObservedRoutesAndSearchEncodingAndRemoveTracking() {
        assertTrue(CenterCatalog.providers.containsAll(listOf("kinotac","zona","kinokong")))
        assertFalse(CenterCatalog.providers.any { it in listOf("rezka","hdrezka","kazplay") })
        for((provider,movie,tv,nextMovie) in listOf(
            listOf("kinotac","/filmy/","/serialy/","/filmy/page/2/"),
            listOf("zona","/film/","/series/","/film/page/2/"),
            listOf("kinokong","/kinofilmi/","/serialo/","/kino/page/2/"))) {
            val sections=CenterCatalog.sections(provider)
            assertEquals(listOf("movie","tv"),sections.map { it.type })
            assertEquals(movie,CenterCatalog.route(provider,"https://catalog.test/?ysclid=tracking",sections[0]).encodedPath)
            assertEquals(tv,CenterCatalog.route(provider,"https://catalog.test",sections[1]).encodedPath)
            val next=CenterCatalog.route(provider,"https://catalog.test/?ysclid=tracking",sections[0],2)
            assertEquals(nextMovie,next.encodedPath);assertNull(next.query)
            assertEquals("/index.php",CenterCatalog.searchRoute(provider,"https://catalog.test","Матрица").encodedPath)
        }
        assertTrue(CenterCatalog.searchRoute("kinokong","https://catalog.test","Матрица").encodedQuery!!.contains("story=%CC%E0%F2%F0%E8%F6%E0"))
        assertEquals("Матрица",CenterCatalog.searchRoute("kinotac","https://catalog.test","Матрица").queryParameter("story"))
    }
    @Test fun kinoTacCardsKeepNativeIdsTypesCoversAndCategoryYearsWithoutInventingEpisodeCounts() {
        val html="""<div class="th-item"><a class="th-in" href="/17-movie.html"><img src="/poster.jpg"><h2 class="th-title">Фильм</h2><div class="th-cat">Фильмы / Фильмы 2025 года / Драма</div></a></div>
        <div class="th-item"><a class="th-in" href="/18-show.html"><img src="/show.jpg"><h2 class="th-title">Сериал</h2><div class="th-cat">Сериалы / Сериалы 2026 / Драма</div></a></div>
        <div class="th-item"><a class="th-in" href="https://advert.test/19-wrong.html"><h2 class="th-title">Реклама</h2></a></div>"""
        val items=CenterCatalog.parse(Jsoup.parse(html,"https://catalog.test"),"kinotac")
        assertEquals(listOf("kinotac:movie:17","kinotac:tv:18"),items.map { it.key })
        assertEquals(listOf("2025","2026"),items.map { it.year });assertEquals("https://catalog.test/poster.jpg",items[0].poster)
        assertNull(items[1].updateLabel)
    }
    @Test fun zonaCardsReadTrailingIdsExplicitTypesRatingsAndSeasonBadges() {
        val html="""<div class="keep-item"><img src="/poster.jpg"><div class="keep-item_meta"><span class="rating-color">8.3</span><span>2025</span><span class="bbr1">2s8e</span></div>
        <a class="keep-item_title" href="/series/mobland-1478/">Гангстерленд</a></div>
        <div class="keep-item"><img src="/movie.jpg"><div class="keep-item_meta">7.4 2026</div><a class="keep-item_title" href="/film/movie-90881/">Фильм</a></div>"""
        val items=CenterCatalog.parse(Jsoup.parse(html,"https://catalog.test/series/"),"zona")
        assertEquals("zona:tv:1478",items[0].key);assertEquals("2 сезон 8 серия",items[0].updateLabel)
        assertEquals("8.3",items[0].rating);assertEquals("2025",items[0].year)
        assertEquals("zona:movie:90881",items[1].key);assertNull(items[1].updateLabel)
    }
    @Test fun kinoKongWindows1251CatalogAndSearchExcludeNewsAndUseRealPostersInsteadOfBookmarkIcons() {
        val html="""<html><head><meta charset="windows-1251"></head><body>
        <div class="kino-news1"><a href="/99-news.html">Новости</a></div>
        <div class="item"><div class="main-sliders-img"><span><img src="/templates/icon.gif"></span><img data-src="/poster.webp" src="/uploads/preload.svg"></div>
        <div class="main-sliders-season">1-4 Сезон | 1-12 Серия</div><span class="main-sliders-imdb">7.9</span>
        <h2 class="main-sliders-title"><a href="/61709-serial-show.html">Док — Всё в твоих руках (1-4 Сезон)</a></h2></div>
        <article class="kg-search-hit"><a class="kg-search-hit-poster" href="/1562-movie.html"><img src="/matrix.webp"></a><a class="kg-search-hit-title" href="/1562-movie.html">Матрица (1999)</a>
        <div class="kg-search-hit-muted">1999 Боевик</div><div class="kg-search-hit-description">Описание</div></article></body></html>"""
        val document=Jsoup.parse(ByteArrayInputStream(html.toByteArray(charset("windows-1251"))),null,"https://catalog.test/index.php")
        val items=CenterCatalog.parse(document,"kinokong")
        assertEquals(2,items.size);assertEquals("kinokong:tv:61709",items[0].key);assertEquals("Док — Всё в твоих руках",items[0].title)
        assertEquals("https://catalog.test/poster.webp",items[0].poster);assertEquals("1-4 Сезон | 1-12 Серия",items[0].updateLabel)
        assertEquals("kinokong:movie:1562",items[1].key);assertEquals("Матрица",items[1].title);assertEquals("1999",items[1].year)
        assertEquals("Описание",items[1].overview)
    }
    @Test fun removedCatalogPreferenceFallsBackWithoutDeletingSavedTitles() {
        val app=RuntimeEnvironment.getApplication();val store=CenterStore(app)
        val saved=CenterTitle(17,"tv","Сохранённый сериал",provider="kazplay")
        if(!store.isFavorite(saved))store.toggle(saved)
        store.catalog="kazplay"
        assertEquals("tmdb",CenterStore(app).catalog)
        assertTrue(CenterStore(app).favorites().any { it.key==saved.key })
        for(provider in listOf("kinotac","zona","kinokong")) {
            try { CenterCatalog.parse(Jsoup.parse("<h1>Checking your browser</h1>","https://catalog.test"),provider);fail("Accepted challenge") } catch(_:IllegalStateException) {}
            assertTrue(CenterCatalog.parse(Jsoup.parse("По запросу ничего не найдено","https://catalog.test"),provider).isEmpty())
        }
    }
    @Test fun newCatalogPaginationFiltersOtherMediaAndStopsAtTheLastObservedPage() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            fun card(id:Int,kind:String)= """<div class="keep-item"><a class="keep-item_title" href="/$kind/title-$id/">Название $id</a></div>"""
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(card(9,"film")+card(17,"series")+"""<a href="/series/page/2/">2</a>"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(card(18,"series")))
            val catalog=CenterCatalog();val section=CenterCatalog.sections("zona")[1];val base=server.url("/").toString()
            assertEquals(listOf("zona:tv:17"),catalog.browse("zona",base,section).map { it.key })
            assertEquals(listOf("zona:tv:18"),catalog.browse("zona",base,section,2).map { it.key })
            assertTrue(catalog.browse("zona",base,section,3).isEmpty());assertEquals(2,server.requestCount)
        }
    }

    @Test fun watchedSeriesKeepAllCatalogIdentitiesIncludingRemovedChoices() {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("media_center",0).edit().clear().commit()
        val store=CenterStore(app)
        val providers=listOf("kinotac","zona","kinokong","kazplay","rezka","hdrezka")
        for(provider in providers)store.remember("https://catalog.test/$provider/17",CardMeta(
            title="Сериал $provider",contentKey="$provider:tv:17",sourcePage="https://catalog.test/$provider/17",seasonNumber=2,episodeNumber=5))
        assertEquals(providers.map { "$it:tv:17" }.toSet(),store.mySeries().map { it.key }.toSet())
        assertTrue(store.mySeries().all { it.sourceUrl!=null&&it.id==17 })
        assertFalse(CenterCatalog.providers.any { it in listOf("kazplay","rezka","hdrezka") })
        assertFalse(CenterCatalog.isSiteIdentity("tmdb"));assertFalse(CenterCatalog.isSiteIdentity("unknown"))
    }

    @Test fun zonaDetailsReadTheSelectedMediaGraphFullSynopsisAndPortraitSeparatelyFromBackdrop() {
        val source="https://catalog.test/series/show-17/"
        val html="""<script type="application/ld+json">{"@type":"Organization","description":"Реклама сайта"}</script>
        <script type="application/ld+json">{"@graph":[
          {"@type":"Movie","name":"Другой фильм","url":"https://catalog.test/film/other-99/","description":"Чужой сюжет","actors":"Чужой актёр"},
          {"@type":"Movie","name":"Сериал","url":"https://catalog.test/series/show-17/","description":"Кратко","actors":"Актёр А, Актёр Б","alternateName":"My Show","datePublished":"2025"}
        ]}</script><meta property="og:image" content="/social-wide.jpg">
        <div class="keep-full"><div class="keep-full_poster"><img src="/portrait-large.jpg"></div><div class="keep-full_bg"><img class="posterbg" src="/backdrop.jpg"></div></div>
        <div class="keep-detal_text"><p>Полный первый абзац.</p><p>Полный второй абзац.</p></div>"""
        val item=CenterTitle(17,"tv","Сериал",poster="https://catalog.test/small.jpg",provider="zona",sourceUrl=source)
        val result=CenterCatalog.details(Jsoup.parse(html,source),item)
        assertEquals("Полный первый абзац.\n\nПолный второй абзац.",result.title.overview)
        assertEquals("Актёр А, Актёр Б",result.actors);assertEquals("My Show",result.title.original)
        assertEquals("2025",result.title.year);assertEquals(item.key,result.title.key)
        assertEquals("https://catalog.test/portrait-large.jpg",result.title.poster)
        assertEquals("https://catalog.test/backdrop.jpg",result.title.backdrop);assertTrue(result.seasons.isEmpty())
    }
    @Test fun kinoTacReleaseYearAndCastComeFromTheFilmRatherThanTheArticlePublicationDate() {
        val source="https://catalog.test/17-show.html"
        val html="""<meta itemprop="description" content="Короткое описание"><meta itemprop="datePublished" content="2026-10-23">
        <script type="application/ld+json">{"@graph":[{"@type":"Movie","name":"Сериал","mainEntityOfPage":{"@id":"https://catalog.test/17-show.html"},"datePublished":"2026-10-23","description":"Кратко"}]}</script>
        <article class="full"><div class="images"><img itemprop="image" src="/large.jpg"></div>
        <div class="info" itemprop="copyrightYear">2022</div><div class="info" itemprop="actor">Актёр А, Актёр Б</div>
        <div class="descriptionnew" itemprop="description">Полный сюжет выбранного сериала.</div></article>
        <div class="comm-item" itemprop="description">Комментарий зрителя</div>"""
        val result=CenterCatalog.details(Jsoup.parse(html,source),CenterTitle(17,"tv","Сериал",provider="kinotac",sourceUrl=source))
        assertEquals("2022",result.title.year);assertEquals("Полный сюжет выбранного сериала.",result.title.overview)
        assertEquals("Актёр А, Актёр Б",result.actors);assertEquals("https://catalog.test/large.jpg",result.title.poster)
    }
    @Test fun kinoKongDetailsKeepTheCoverAndReadScopedCastAndOriginalReleaseYear() {
        val source="https://catalog.test/17-serial-show.html"
        val html="""<meta itemprop="dateCreated" content="2026-11-06"><div itemprop="actor"><span itemprop="name">Чужой актёр</span></div>
        <div id="full_17"><div class="full-kino-info"><div><span>Год:</span><b>2020</b></div></div>
        <div class="full-kino-story" itemprop="description">Полный сюжет.</div>
        <span itemprop="actor"><a><span itemprop="name">Актёр А</span></a></span><span itemprop="actor"><span itemprop="name">Актёр Б</span></span></div>"""
        val item=CenterTitle(17,"tv","Сериал",poster="https://catalog.test/cover.webp",provider="kinokong",sourceUrl=source)
        val result=CenterCatalog.details(Jsoup.parse(html,source),item)
        assertEquals("2020",result.title.year);assertEquals("Актёр А, Актёр Б",result.actors)
        assertEquals(item.poster,result.title.poster);assertEquals("Полный сюжет.",result.title.overview)
    }
    @Test fun titleDetailsRejectOtherCardsAndDoNotReadCommentsOrRelatedMovieMetadata() {
        val source="https://catalog.test/film/selected-17/"
        val item=CenterTitle(17,"movie","Выбранный фильм",provider="zona",sourceUrl=source)
        val html="""<script type="application/ld+json">[{"@type":"Movie","name":"Другой фильм","url":"https://catalog.test/film/other-99/","description":"Чужой сюжет","actor":[{"name":"Чужой актёр"}]}]</script>
        <div class="comm-item" itemprop="description">Комментарий вместо сюжета</div><meta property="og:description" content="Описание выбранного фильма">"""
        val result=CenterCatalog.details(Jsoup.parse(html,source),item)
        assertEquals("Описание выбранного фильма",result.title.overview);assertEquals("",result.actors)
        try { CenterCatalog.details(Jsoup.parse(html,"https://catalog.test/film/other-99/"),item);fail("Accepted another title") }
        catch(_:IllegalStateException) {}
    }
}
