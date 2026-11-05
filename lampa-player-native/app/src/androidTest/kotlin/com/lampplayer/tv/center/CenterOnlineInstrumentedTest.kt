package com.lampplayer.tv.center

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CenterOnlineInstrumentedTest {
    @Test fun aCanonicalRedirectForTheSelectedFilmixIdKeepsResolvingAndUpdatesTheReferer() = runBlocking {
        MockWebServer().use { server ->
            val canonical=server.url("/seria/17-canonical.html").toString()
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location",canonical))
            server.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("""<html><body><script>
                JSON.parse('[{"title":"Сезон 1","folder":[{"title":"2 серия","file":"https://cdn.test/two.m3u8"}]}]');
                </script></body></html>"""))
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"tv","Сериал",provider="filmix",sourceUrl=server.url("/seria/17-old.html").toString()))
                assertEquals(2,result.episodes.single().number);assertEquals(canonical,result.headers["Referer"])
            }
        }
    }
    @Test fun filmixConstructorPlaylistWinsOverOtherPlayersAndKeepsTheActiveVoice() = runBlocking {
        for (serialized in listOf(false, true)) MockWebServer().use { server ->
            val playlist="[{title:'Сезон 2',folder:[{title:'3 серия',file:'[480p]https://cdn.test/three.m3u8,[1080p]https://cdn.test/high.m3u8'}]}]"
            val file=if(serialized) "JSON.stringify($playlist)" else playlist
            server.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("""<!doctype html><html><body>
                <div class="player-main-block"><div class="players" data-player="17"><div id="player"></div></div>
                <ul class="translations"><li class="active"><span data-name="Дубляж">Дубляж</span></li></ul></div>
                <script src="/playerjs.js"></script><script>
                JSON.parse('[{"title":"Серия 99","file":"https://cdn.test/unrelated.m3u8"}]');
                new Playerjs({id:'player',file:$file});
                window.player=new Playerjs({id:'player_trailer',file:[{title:'Сезон 9',folder:[{title:'1 серия',file:'https://cdn.test/trailer.m3u8'}]}]});
                </script></body></html>"""))
            server.enqueue(MockResponse().setHeader("Content-Type","application/javascript")
                .setBody("function Playerjs(options){this.api=function(){return options.id==='player_trailer'?options.file:[];};}"))
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"tv","Сериал",provider="filmix",sourceUrl=server.url("/seria/17-test.html").toString()))
                val episode=result.episodes.single()
                assertEquals(2,episode.season);assertEquals(3,episode.number);assertEquals("Дубляж",episode.voice)
                assertEquals(mapOf(480 to "https://cdn.test/three.m3u8"),episode.sources)
            }
        }
    }
    @Test fun aProviderCountryBlockIsReportedInsteadOfOpeningAnotherVideo() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8")
                .setBody("<html><body><div class='source-notice'>Видео недоступно для просмотра в вашей стране</div></body></html>"))
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                try {
                    resolver.resolve(CenterTitle(17,"tv","Сериал",provider="kinovod",sourceUrl=server.url("/serial/17-test").toString()))
                    fail("Ignored the provider restriction")
                } catch(e:OnlineSourceBlocked) { assertEquals("country",e.reason) }
            }
        }
    }
    @Test fun playerApiPlaylistSetterIsCollectedBeforeTheSiteInitializes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("""<!doctype html><html><head><meta name="viewport" content="width=device-width"></head><body>
                <script src="/playerjs.js"></script><script>if(window.innerWidth<200)throw Error('Player needs a usable viewport');var p=new Playerjs("videoplayer");
                p.api('playlist',[{title:'Сезон 2',folder:[{title:'3 серия',file:'https://cdn.test/three.m3u8'}]}]);</script></body></html>"""))
            server.enqueue(MockResponse().setHeader("Content-Type","application/javascript").setBody("function Playerjs(){this.api=function(){return [];};}"))
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"tv","Сериал",provider="kinovod",sourceUrl=server.url("/serial/17-test").toString()))
                assertEquals(2,result.episodes.single().season);assertEquals(3,result.episodes.single().number)
            }
        }
    }
    @Test fun documentStartCollectorReturnsEpisodesWithoutOpeningAWebsiteActivity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("""<!doctype html><html><body><div id="videoplayer"></div><div id="comments">Пользователь пишет: видео недоступно для просмотра в вашей стране</div><script>
                JSON.parse('[{"title":"Сезон 1","folder":[{"title":"1 серия","file":"https://cdn.test/one.m3u8"},{"title":"2 серия","file":"https://cdn.test/two.m3u8"}]}]');
                </script></body></html>"""))
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline
                scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"tv","Сериал",provider="kinovod",sourceUrl=server.url("/serial/17-test").toString()))
                assertEquals(listOf(1,2),result.episodes.map { it.number })
                assertEquals("https://cdn.test/two.m3u8",result.episodes[1].sources[0])
            }
        }
    }
    @Test fun kinoVodPublicSeriesResolvesToNativeNumberedEpisodes() = runBlocking {
        ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
            lateinit var resolver:CenterOnline
            scenario.onActivity { resolver=CenterOnline(it) }
            val result=try {
                resolver.resolve(CenterTitle(255266,"tv","Нейровася",provider="kinovod",sourceUrl="https://kinovod.pro/serial/255266-neyrovasya"))
            } catch(e:OnlineSourceBlocked) {
                if(e.reason=="country")org.junit.Assume.assumeNoException("KinoVod explicitly blocks this title in the test runner country",e)
                throw e
            } catch(e:Exception) { throw AssertionError("KinoVod native resolution: ${resolver.diagnostics}",e) }
            assertTrue(result.episodes.isNotEmpty());assertTrue(result.episodes.all { it.number>0&&it.sources.isNotEmpty() })
            val media=result.episodes.first().sources.minByOrNull { it.key }!!.value
            val request=okhttp3.Request.Builder().url(media).apply { result.headers.forEach { (name,value) -> header(name,value) } }.build()
            okhttp3.OkHttpClient.Builder().callTimeout(12,java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                assertTrue("KinoVod media HTTP ${response.code}",response.isSuccessful)
                if(media.substringBefore('?').endsWith(".m3u8"))
                    assertTrue("Expected a playable HLS manifest",response.peekBody(4096).string().trimStart('\uFEFF',' ','\r','\n').startsWith("#EXTM3U"))
            }
        }
    }
    @Test fun filmixPublicMovieResolvesOrReportsItsExplicitCountryRestriction() = runBlocking {
        ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
            lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
            val result=try {
                resolver.resolve(CenterTitle(81482,"movie","Интерстеллар",provider="filmix",sourceUrl="https://filmix.gg/film/drama/81482-interstellar-2014.html"))
            } catch(e:OnlineSourceBlocked) {
                if(e.reason=="country")org.junit.Assume.assumeNoException("Filmix explicitly blocks this title in the test runner country",e)
                throw e
            } catch(e:Exception) { throw AssertionError("Filmix native resolution: ${resolver.diagnostics}",e) }
            assertTrue(result.episodes.isNotEmpty())
            val media=result.episodes.first().sources.minByOrNull { it.key }!!.value
            val request=okhttp3.Request.Builder().url(media).apply { result.headers.forEach { (name,value) -> header(name,value) } }.build()
            okhttp3.OkHttpClient.Builder().callTimeout(12,java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                assertTrue("Filmix media HTTP ${response.code}",response.isSuccessful)
                if(media.substringBefore('?').endsWith(".m3u8"))
                    assertTrue("Expected HLS manifest",response.peekBody(4096).string().trimStart('\uFEFF',' ','\r','\n').startsWith("#EXTM3U"))
            }
        }
    }

    @Test fun immediatePlayerAssignmentIsCapturedBeforeTheFirstConstructorCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("""<html><body><div id="player"></div><script>
            window.Playerjs=function(options){this.api=function(){return [];};};
            new window.Playerjs({id:'player',file:'[480p]https://cdn.test/ready.mp4'});
            </script></body></html>"""))
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"movie","Movie",provider="filmix",sourceUrl=server.url("/film/17-test.html").toString()))
                assertEquals("https://cdn.test/ready.mp4",result.episodes.single().sources[480])
            }
        }
    }
}
