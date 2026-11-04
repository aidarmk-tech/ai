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
    @Test fun playerApiPlaylistSetterIsCollectedBeforeTheSiteInitializes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("""<!doctype html><html><body>
                <script src="/playerjs.js"></script><script>var p=new Playerjs("videoplayer");
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
            server.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("""<!doctype html><html><body><div id="videoplayer"></div><script>
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
            } catch(e:Exception) { throw AssertionError("KinoVod native resolution: ${resolver.diagnostics}",e) }
            assertTrue(result.episodes.isNotEmpty());assertTrue(result.episodes.all { it.number>0&&it.sources.isNotEmpty() })
        }
    }
}
