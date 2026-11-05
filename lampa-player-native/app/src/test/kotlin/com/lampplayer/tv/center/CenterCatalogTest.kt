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
        for(provider in listOf("rezka","kazplay")) {
            store.catalog=provider;assertEquals(provider,CenterStore(app).catalog)
            assertTrue(store.catalogHost(provider).startsWith("https://"))
            store.setCatalogHost(provider,"https://$provider.test")
            assertEquals("https://$provider.test",CenterStore(app).catalogHost(provider))
        }
        assertEquals("https://rezka.test",store.catalogHost("rezka"))
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

}
