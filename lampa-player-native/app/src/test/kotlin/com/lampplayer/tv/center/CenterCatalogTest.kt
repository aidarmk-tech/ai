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
}
