package com.lampplayer.tv.center

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
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
    @org.junit.Ignore("Filmix is catalog-only in LampMedia; its live online adapter is not exposed")
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

    @Test fun serializedPlaylistFromPlayerApiResolvesSeries() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("""<html><body><div id="player"></div><script>
            window.Playerjs=function(options){this.api=function(command){return command==='playlist_folders'?JSON.stringify([{title:'Сезон 1',folder:[{title:'2 серия',file:'[480p]https://cdn.test/two.mp4'}]}]):null;};};
            new window.Playerjs({id:'player'});
            </script></body></html>"""))
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"tv","Series",provider="filmix",sourceUrl=server.url("/seria/17-test.html").toString()))
                assertEquals(2,result.episodes.single().number)
                assertEquals("https://cdn.test/two.mp4",result.episodes.single().sources[480])
            }
        }
    }
    @Test fun kinogoCollectsTheBoundEmbedPlaylistAndUsesItsRefererWithoutTrailerOrPageJson() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { frameServer ->
            frameServer.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            val dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    val html=when(request.path?.substringBefore('?')) {
                        "/6873-gangsterlend.html" -> """<html><body>
                            <script>JSON.parse('[{"title":"Сезон 9","folder":[{"title":"99 серия","file":"https://cdn.test/unrelated.mp4"}]}]');</script>
                            <iframe data-src="${frameServer.url("/embed/movie/81816").newBuilder().host("127.0.0.1").build()}"></iframe><iframe data-src="/embed/trailer/81816"></iframe>
                            </body></html>"""
                        "/playerjs.js" -> "function Playerjs(options){this.api=function(command){return command==='playlist_folders'?options.file:[];};}"
                        "/embed/movie/81816" -> """<html><body><div id="player"></div><script src="/playerjs.js"></script><script>
                            new Playerjs({id:'player',file:[{title:'Сезон 2',folder:[{title:'Дубляж',folder:[
                                {title:'5 серия',file:'[720p]https://cdn.test/five.m3u8'},
                                {title:'6 серия',file:'[720p]https://cdn.test/six.m3u8'}]}]}]});
                            </script></body></html>"""
                        else -> "<html><body>Trailer or unknown page</body></html>"
                    }
                    return MockResponse().setHeader("Content-Type",if(request.path=="/playerjs.js") "application/javascript" else "text/html; charset=utf-8").setBody(html)
                }
            }
            server.dispatcher=dispatcher
            frameServer.dispatcher=dispatcher
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(6873,"tv","Гангстерленд",provider="kinogo",
                    sourceUrl=server.url("/6873-gangsterlend.html").toString()))
                assertEquals(listOf(5,6),result.episodes.map { it.number })
                assertTrue(result.episodes.all { it.season==2 && it.voice=="Дубляж" })
                assertEquals(frameServer.url("/embed/movie/81816").newBuilder().host("127.0.0.1").build().toString(),result.headers["Referer"])
                assertEquals(frameServer.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'),result.headers["Origin"])
            }
        }
        }
    }

    @Test fun kinogoReportsExplicitRegionalRestrictionFromItsPlayer() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=MockResponse()
                    .setHeader("Content-Type","text/html; charset=utf-8").setBody(
                        if(request.path?.startsWith("/17-")==true) """<html><body><iframe data-src="/embed/movie/9"></iframe></body></html>"""
                        else """<html><body>К сожалению, видео недоступно для вашего региона: US</body></html>""")
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                try {
                    resolver.resolve(CenterTitle(17,"movie","Movie",provider="kinogo",sourceUrl=server.url("/17-movie.html").toString()))
                    fail("Ignored source regional restriction")
                } catch(e:OnlineSourceBlocked) { assertEquals("country",e.reason) }
            }
        }
    }

    @Test fun kinogoFollowsTheSelectedFrameRedirectAndReadsWrappedPlaylistJson() = runBlocking {
        MockWebServer().use { pageServer -> MockWebServer().use { playerServer ->
            playerServer.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            val finalUrl=playerServer.url("/video/player").newBuilder().host("127.0.0.1").build().toString()
            pageServer.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse =
                    if(request.path=="/embed/movie/81816") MockResponse().setResponseCode(302).setHeader("Location",finalUrl)
                    else MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(
                        """<html><body><iframe data-src="/embed/movie/81816"></iframe>
                        <iframe data-src="/embed/trailer/81816"></iframe></body></html>""")
            }
            playerServer.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(
                """<html><body><script>
                JSON.parse('{"data":{"playlist":[{"title":"Сезон 2","folder":[{"title":"5 серия","file":"[720p]https://cdn.test/five.m3u8"}]}]}}');
                </script></body></html>"""))
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(62389,"tv","Гангстерленд",provider="kinogo",
                    sourceUrl=pageServer.url("/62389-gangsterlend.html").toString()))
                assertEquals(2,result.episodes.single().season);assertEquals(5,result.episodes.single().number)
                assertEquals(finalUrl,result.headers["Referer"])
            }
        } }
    }

    @Test fun unrecognizedFrameRepliesRemainVisibleInDiagnostics() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=MockResponse()
                    .setHeader("Content-Type","text/html; charset=utf-8").setBody(
                        if(request.path=="/17-show.html") """<html><body><iframe data-src="/embed/movie/9"></iframe></body></html>"""
                        else """<html><body>Список загружается другим проигрывателем</body></html>""")
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val pending=async {
                    resolver.resolve(CenterTitle(17,"tv","Show",provider="kinogo",sourceUrl=server.url("/17-show.html").toString()))
                }
                try {
                    kotlinx.coroutines.withTimeout(8000) {
                        while(!resolver.diagnostics.contains("replies=1"))kotlinx.coroutines.delay(50)
                    }
                    assertTrue(resolver.diagnostics.contains("files=0"))
                    assertTrue(resolver.diagnostics.contains("undefined"))
                    assertFalse(resolver.diagnostics.contains("player=null"))
                } finally { pending.cancel();pending.join() }
            }
        }
    }


    @Test fun webViewMediaCookiesReachNativeChecksAndExoAndStayScopedAcrossRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            val first=server.url("/entry.m3u8").newBuilder().host("localhost").build().toString()
            val second=server.url("/playlist.m3u8").newBuilder().host("127.0.0.1").build().toString()
            val name="lamp_media_session_fixture"
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    val cookie=request.getHeader("Cookie").orEmpty()
                    if(request.path=="/entry.m3u8")return if(cookie.contains("$name=first")&&!cookie.contains("$name=second"))
                        MockResponse().setResponseCode(302).setHeader("Location",second) else MockResponse().setResponseCode(404)
                    if(!cookie.contains("$name=second")||cookie.contains("$name=first"))return MockResponse().setResponseCode(404)
                    return if(request.path=="/segment.ts")MockResponse().setHeader("Content-Type","video/mp2t").setBody("G")
                    else MockResponse().setBody("#EXTM3U\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST")
                }
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                com.lampplayer.tv.data.BrowserMediaSession.refresh()
                scenario.onActivity {
                    android.webkit.CookieManager.getInstance().apply {
                        setCookie(first,"$name=first; Path=/")
                        setCookie(second,"$name=second; Path=/")
                    }
                }
                try {
                    kotlinx.coroutines.withTimeout(5000) {
                        while(android.webkit.CookieManager.getInstance().getCookie(first)?.contains("$name=first")!=true||
                            android.webkit.CookieManager.getInstance().getCookie(second)?.contains("$name=second")!=true)kotlinx.coroutines.delay(50)
                    }
                    assertEquals(first,CenterOnline.playableUrl(first,emptyMap()))
                    assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("$name=first"))
                    repeat(2) { assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("$name=second")) }
                    val exo=androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).createDataSource()
                    try {
                        exo.open(androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.parse(first)).build())
                        assertTrue(exo.read(ByteArray(16),0,16)>0)
                    } finally { exo.close() }
                    assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("$name=first"))
                    assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("$name=second"))
                } finally {
                    scenario.onActivity {
                        android.webkit.CookieManager.getInstance().setCookie(first,"$name=; Max-Age=0; Path=/")
                        android.webkit.CookieManager.getInstance().setCookie(second,"$name=; Max-Age=0; Path=/")
                    }
                    com.lampplayer.tv.data.BrowserMediaSession.refresh()
                }
            }
        }
    }

    @Test fun kinogoLoadsTheLazySecondTabAndDecodesJsonXhrObjectPlaylist() = runBlocking {
        MockWebServer().use { server ->
            val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    val path=request.path?.substringBefore('?').orEmpty();requests.add(path)
                    return when(path) {
                        "/62389-show.html" -> MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("""
                            <html><body><ul class="player-tabs">
                            <li class="current">Смотреть онлайн</li>
                            <li onclick="document.querySelector('.current').classList.remove('current');this.classList.add('current');setTimeout(function(){document.getElementById('second').innerHTML='&lt;iframe src=&quot;/embed/movie/81817&quot;&gt;&lt;/iframe&gt;';},900);">Плеер 2</li>
                            <li onclick="location.href='/wrong-trailer'">Трейлер</li></ul>
                            <iframe data-src="/embed/movie/81816"></iframe><div id="second"></div></body></html>""")
                        "/embed/movie/81816" -> MockResponse().setHeader("Content-Type","text/html; charset=utf-8")
                            .setBody("<html><body>Видео недоступно для просмотра в вашей стране</body></html>")
                        "/embed/movie/81817" -> MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("""
                            <html><body><script>var request=new XMLHttpRequest();request.open('GET','/playlist');
                            request.responseType='json';request.send();</script></body></html>""")
                        "/playlist" -> MockResponse().setHeader("Content-Type","application/json").setBody(
                            """{"data":"{\"title\":\"Сезон 2\",\"folder\":[{\"title\":\"5 серия\",\"file\":\"[720p]https://cdn.test/five.m3u8\"}]}"}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val video=resolver.resolve(CenterTitle(62389,"tv","Гангстерленд",provider="kinogo",sourceUrl=server.url("/62389-show.html").toString()))
                assertEquals(2,video.episodes.single().season);assertEquals(5,video.episodes.single().number)
                assertEquals("https://cdn.test/five.m3u8",video.episodes.single().sources[720])
                assertEquals(server.url("/embed/movie/81817").toString(),video.headers["Referer"])
                assertTrue(resolver.diagnostics.contains("alternate=true"));assertTrue(resolver.diagnostics.contains("json=1"))
                assertEquals(1,requests.count { it=="/embed/movie/81817" })
                assertFalse(requests.contains("/wrong-trailer"))
            }
        }
    }

    @Test fun kinogoWebsiteOpensAtThePlayerAndToolbarSwitchesTheSameCardWithoutTrailer() = runBlocking {
        MockWebServer().use { server ->
            val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    val path=request.path?.substringBefore('?').orEmpty();requests.add(path)
                    val html=if(path=="/17-show.html") """
                        <html><body><div style="height:1800px">Заголовок и описание выбранного сериала</div>
                        <ul class="player-tabs"><li class="current" onclick="pick(0)">Смотреть онлайн</li>
                        <li onclick="pick(1)">Плеер 2</li><li onclick="location.href='/wrong-trailer'">Трейлер</li></ul>
                        <div id="first"><iframe src="/embed/movie/9"></iframe></div><div id="second" style="display:none"><iframe data-src="/embed/movie/10"></iframe></div>
                        <script>function pick(n){document.querySelector('.current').classList.remove('current');
                        document.querySelectorAll('.player-tabs li')[n].classList.add('current');
                        document.getElementById('first').style.display=n?'none':'block';document.getElementById('second').style.display=n?'block':'none';
                        if(n){var frame=document.querySelector('#second iframe');if(!frame.src)frame.src=frame.dataset.src;}}</script></body></html>"""
                    else "<html><body>Плеер выбранной серии</body></html>"
                    return MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(html)
                }
            }
            val source=server.url("/17-show.html").toString()
            val intent=android.content.Intent(androidx.test.core.app.ApplicationProvider.getApplicationContext(),CenterWebActivity::class.java)
                .putExtra("url",source).putExtra("site_player",true).putExtra("catalog_provider","kinogo")
                .putExtra("lampa_data","""{"title":"Show"}""")
            ActivityScenario.launch<CenterWebActivity>(intent).use { scenario ->
                suspend fun state():com.google.gson.JsonObject {
                    val pending=kotlinx.coroutines.CompletableDeferred<String>()
                    scenario.onActivity { activity ->
                        activity.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.webkit.WebView>("site_player_web")
                            .evaluateJavascript("(function(){return {scroll:scrollY,tab:document.querySelector('.player-tabs .current')?.textContent||'',url:location.href,focused:document.activeElement?.tagName};})()") { pending.complete(it) }
                    }
                    return com.google.gson.JsonParser.parseString(pending.await()).asJsonObject
                }
                kotlinx.coroutines.withTimeout(10000) {
                    while(true) { val s=state();if(s.get("tab").asString=="Смотреть онлайн"&&s.get("scroll").asInt>1000)break;kotlinx.coroutines.delay(100) }
                }
                assertEquals(source,state().get("url").asString)
                scenario.onActivity { activity ->
                    fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)
                        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                    views(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.Button>().first { it.text.toString()=="Плеер 2" }.performClick()
                }
                kotlinx.coroutines.withTimeout(10000) {
                    while(true) { val s=state();if(s.get("tab").asString=="Плеер 2"&&s.get("scroll").asInt>1000&&s.get("focused").asString=="IFRAME"&&requests.contains("/embed/movie/10"))break;kotlinx.coroutines.delay(100) }
                }
                assertEquals(source,state().get("url").asString)
                assertTrue("Second player did not request its document",requests.contains("/embed/movie/10"));assertFalse("A trailer was opened instead of the selected video",requests.contains("/wrong-trailer"))
            }
        }
    }

    private fun kongPage():String = """<html><body><div id="full_17">
        <div style="height:1800px">Описание сериала</div><ul class="newTabs">
        <li class="current" data-iframe="/embed/movie/91" onclick="pick(this)">Смотреть</li>
        <li data-iframe="/provider-two" onclick="pick(this)">2 плеер</li>
        <li data-iframe="/provider-three" onclick="pick(this)">3 плеер</li>
        <li class="trail" data-iframe="/trailer-cdn/17/" onclick="pick(this)">Трейлер</li></ul>
        <div id="preroll"><iframe data-src="/embed/movie/91"></iframe></div>
        <iframe src="/unrelated"></iframe>
        <script>function pick(el){document.querySelector('.newTabs .current').classList.remove('current');el.classList.add('current');
        setTimeout(function(){var f=document.querySelector('#preroll iframe');f.setAttribute('data-src',el.dataset.iframe);f.src=el.dataset.iframe;},500);}
        JSON.parse('[{"title":"Серия 99","file":"https://cdn.test/wrong.m3u8"}]');</script></div></body></html>"""

    @Test fun kinokongTriesAllThreeDeclaredVideoTabsAndKeepsTheEmbedReferer() = runBlocking {
        MockWebServer().use { server ->
            val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    val path=request.path?.substringBefore('?').orEmpty();requests.add(path)
                    val body=when(path) {
                        "/17-show.html" -> kongPage()
                        "/embed/movie/91" -> "<html><body>Видео недоступно для вашего региона</body></html>"
                        "/provider-two" -> "<html><body>Нет рабочего плеера</body></html>"
                        "/provider-three" -> """<html><body><script>window.Playerjs=function(options){this.api=function(){return options.file;};};
                            new Playerjs({id:'videoplayer',file:[{title:'Сезон 2',folder:[{title:'Дубляж',folder:[
                            {title:'5 серия',file:'[720p]https://cdn.test/five.m3u8'},
                            {title:'6 серия',file:'[720p]https://cdn.test/six.m3u8'}]}]}]});</script></body></html>"""
                        else -> """<html><body><script>JSON.parse('[{"title":"Серия 99","file":"https://cdn.test/wrong.m3u8"}]');</script></body></html>"""
                    }
                    return MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(body)
                }
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"tv","Show",provider="kinokong",sourceUrl=server.url("/17-show.html").toString()))
                assertEquals(listOf(5,6),result.episodes.map { it.number })
                assertTrue(result.episodes.all { it.season==2&&it.voice=="Дубляж" })
                assertEquals(server.url("/provider-three").toString(),result.headers["Referer"])
                assertTrue(requests.contains("/provider-two"));assertTrue(requests.contains("/provider-three"))
                assertFalse(requests.any { it.startsWith("/trailer-cdn/") })
            }
        }
    }

    @Test fun kinokongMovieReadsTheBoundPlayerWithoutBorrowingPageOrAdvertisingJson() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(
                    if(request.path=="/17-movie.html") """<html><body><div id="full_17"></div><ul class="newTabs"><li class="current" data-iframe="/video">Смотреть</li>
                        <li class="trail" data-iframe="/trailer-cdn/17/">Трейлер</li></ul><div id="preroll"><iframe data-src="/video"></iframe></div>
                        <script>JSON.parse('{"file":"https://cdn.test/wrong.mp4"}');</script></body></html>"""
                    else """<html><body><script>window.Playerjs=function(options){this.api=function(){return [];};};
                        new Playerjs({id:'player',file:'[720p]https://cdn.test/movie.mp4,[1080p]https://cdn.test/movie-high.mp4'});
                        new Playerjs({id:'trailer',file:'https://cdn.test/wrong.mp4'});</script></body></html>""")
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"movie","Movie",provider="kinokong",sourceUrl=server.url("/17-movie.html").toString()))
                assertEquals(setOf(720,1080),result.episodes.single().sources.keys)
                assertEquals("https://cdn.test/movie-high.mp4",result.episodes.single().sources[1080])
                assertEquals(server.url("/video").toString(),result.headers["Referer"])
            }
        }
    }

    @Test fun kinokongWebsiteFocusesItsPlayerAndSwitchesToThirdTabWithoutTrailer() = runBlocking {
        MockWebServer().use { server ->
            val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    requests.add(request.path.orEmpty())
                    return MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(
                        if(request.path=="/17-show.html")kongPage() else "<html><body>Плеер выбранной серии</body></html>")
                }
            }
            val source=server.url("/17-show.html").toString()
            val intent=android.content.Intent(androidx.test.core.app.ApplicationProvider.getApplicationContext(),CenterWebActivity::class.java)
                .putExtra("url",source).putExtra("site_player",true).putExtra("catalog_provider","kinokong").putExtra("lampa_data","""{"title":"Show"}""")
            ActivityScenario.launch<CenterWebActivity>(intent).use { scenario ->
                suspend fun state():com.google.gson.JsonObject {
                    val result=kotlinx.coroutines.CompletableDeferred<String>()
                    scenario.onActivity { it.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.webkit.WebView>("site_player_web")
                        .evaluateJavascript("({scroll:scrollY,tab:document.querySelector('.newTabs .current')?.textContent||'',url:location.href,focused:document.activeElement?.tagName})") { value -> result.complete(value) } }
                    return com.google.gson.JsonParser.parseString(result.await()).asJsonObject
                }
                kotlinx.coroutines.withTimeout(10000) { while(state().get("scroll").asInt<1000)kotlinx.coroutines.delay(100) }
                scenario.onActivity { activity ->
                    fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                    views(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.Button>().first { it.text.toString()=="Плеер 3" }.performClick()
                }
                kotlinx.coroutines.withTimeout(10000) { while(true) { val s=state()
                    if(s.get("tab").asString=="3 плеер"&&s.get("focused").asString=="IFRAME"&&requests.contains("/provider-three"))break
                    kotlinx.coroutines.delay(100)
                } }
                assertEquals(source,state().get("url").asString);assertFalse(requests.any { it.startsWith("/trailer-cdn/") })
            }
        }
    }

    @Test fun kinokongNativeBootstrapReadsDuplicateMobileTabsWithoutExecutingBrokenSiteScripts() = runBlocking {
        MockWebServer().use { server ->
            val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    val path=request.path?.substringBefore('?').orEmpty();requests.add(path)
                    val html=when(path) {
                        "/82174-nevesta.html" -> """<html><body><div id="full_82174">
                            <div style="display:none"><ul class="newTabs">
                            <li data-iframe="/embed/movie/72142">Collaps</li><li data-iframe="/provider-two">alloha</li>
                            <li class="trail" data-iframe="/trailer-cdn/82174/">Трейлер</li></ul><div id="preroll"><iframe data-src="/embed/movie/72142" loading="lazy"></iframe></div></div>
                            <div><ul class="newTabs"><li data-iframe="/embed/movie/72142">Collaps</li>
                            <li data-iframe="/provider-two">alloha</li></ul><div id="preroll"><iframe data-src="/embed/movie/72142" loading="lazy"></iframe></div></div>
                            <iframe src="/advert"></iframe><script src="/broken-site.js"></script></div></body></html>"""
                        "/embed/movie/72142" -> """<html><body><script>JSON.parse('[{"title":"Сезон 4","folder":[{"title":"550 серия","file":"[720p]https://cdn.test/550.m3u8"}]}]');</script></body></html>"""
                        else -> "<html><body>Not a video player</body></html>"
                    }
                    return MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(html)
                }
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(82174,"tv","Невеста",provider="kinokong",sourceUrl=server.url("/82174-nevesta.html").toString()))
                assertEquals(4,result.episodes.single().season);assertEquals(550,result.episodes.single().number)
                assertEquals(server.url("/embed/movie/72142").toString(),result.headers["Referer"])
                assertTrue(resolver.diagnostics.contains("bootstrap=native prepared=2"))
                assertTrue(resolver.diagnostics.contains("frames=2"));assertTrue(resolver.diagnostics.contains("replies=1"))
                assertFalse(requests.contains("/broken-site.js"));assertFalse(requests.contains("/advert"))
                assertFalse(requests.any { it.startsWith("/trailer-cdn/") })
            }
        }
    }

    @Test fun kinokongMissingVideoTabsReportsThePageProblemInsteadOfWaitingForAnAlternate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html; charset=utf-8")
                .setBody("""<html><body><div id="full_82174">Карточка без плеера</div></body></html>"""))
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val failure=runCatching { resolver.resolve(CenterTitle(82174,"tv","Невеста",provider="kinokong",sourceUrl=server.url("/82174-nevesta.html").toString())) }.exceptionOrNull()
                assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("не найдены видеоплееры"))
                assertEquals("bootstrap=native stage=page",resolver.diagnostics)
            }
        }
    }

    @Test fun kinokongReadsVenomSeasonsAndQualityMapsFromTheBoundFactory() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    val html=when(request.path) {
                        "/17-show.html" -> """<html><body><div id="full_17"><ul class="newTabs"><li data-iframe="/modern-frame">Смотреть</li></ul></div></body></html>"""
                        "/modern-frame" -> """<html><body><div id="player"></div><div id="trailer"></div><script src="/venom.js"></script><script>
                            function makePlayer(options){return VenomPlayer.make(options);}
                            var middleCount=2;
                            makePlayer({container:document.getElementById('player'),translation:'Voice',
                            restrictQuality:function(q){return q>720?'Unavailable':false;},
                            playlist:{seasons:[{season:2,episodes:[
                            {episode:'5',title:'Пятая',source:{hls:'https://cdn.test/five.m3u8'}},
                            {episode:'6',title:'Шестая',source:{hls:'https://cdn.test/six.m3u8'}},
                            {episode:'7',blocked:true,source:{hls:'https://cdn.test/blocked.m3u8'}}]},
                            {season:3,episodes:[{episode:'1',source:{file:{720:'https://cdn.test/three.mp4',1080:'https://cdn.test/unavailable.mp4'}}}]}]}});
                            makePlayer({container:document.getElementById('trailer'),playlist:{seasons:[{season:9,episodes:[
                            {episode:'99',source:{hls:'https://cdn.test/trailer.m3u8'}}]}]}});
                            </script></body></html>"""
                        "/venom.js" -> """window.VenomPlayer={};Object.defineProperty(VenomPlayer,'make',{enumerable:true,get:function(){return function(options){return {options:options};};}});"""
                        else -> "<html><body>Missing</body></html>"
                    }
                    return MockResponse().setHeader("Content-Type",if(request.path=="/venom.js")"application/javascript" else "text/html; charset=utf-8").setBody(html)
                }
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val video=resolver.resolve(CenterTitle(17,"tv","Show",provider="kinokong",sourceUrl=server.url("/17-show.html").toString()))
                assertEquals(listOf(2 to 5,2 to 6,3 to 1),video.episodes.map { it.season to it.number })
                assertTrue(video.episodes.all { it.voice=="Voice" });assertEquals(setOf(0),video.episodes.first().sources.keys)
                assertEquals(setOf(720),video.episodes.last().sources.keys);assertNull(video.notice)
                assertEquals(server.url("/modern-frame").toString(),video.headers["Referer"])
                assertTrue(resolver.diagnostics.contains("Collaps.makePlayer"));assertTrue(resolver.diagnostics.contains("rows"))
            }
        }
    }

    @Test fun kinokongVenomPlaylistJsonSupportsExplicitEpisodesAndExcludesBlockedRows() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse = when(request.path) {
                    "/17-show.html" -> MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(
                        """<div id="full_17"><ul class="newTabs"><li data-iframe="/frame">Смотреть</li></ul></div>""")
                    "/frame" -> MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(
                        """<script>var x=new XMLHttpRequest();x.open('GET','/playlist');x.responseType='json';x.send();</script>""")
                    "/playlist" -> MockResponse().setHeader("Content-Type","application/json").setBody(
                        """{"seasons":[{"season":4,"episodes":[{"episode":"37","title":"37 серия","source":{"hls":"https://cdn.test/37.m3u8"}},
                        {"episode":"38","blocked":true,"source":{"hls":"https://cdn.test/blocked.m3u8"}}]}],"current":{"season":4,"episode":"37"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"tv","Show",provider="kinokong",sourceUrl=server.url("/17-show.html").toString()))
                assertEquals(4,result.episodes.single().season);assertEquals(37,result.episodes.single().number)
                assertEquals("https://cdn.test/37.m3u8",result.episodes.single().sources[0]);assertNull(result.notice)
            }
        }
    }

    @Test fun kinokongAllohaCorrelatesOnlyDeclaredMediaAndReportsItsPartialLinkSet() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse = when(request.path) {
                    "/17-show.html" -> MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody(
                        """<div id="full_17"><ul class="newTabs"><li data-iframe="/frame">Смотреть</li></ul></div>""")
                    "/frame" -> MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setBody("""
                        <html><body><script>
                        JSON.parse('{"type":"serial","active":{"season":2,"episode":5},"all":{"2":{"5":{"v":{"id":"m55","translation":"Дубляж"}},"6":{"v":{"id":"m66","translation":"Дубляж"}}}},"translationList":{"v":"Дубляж"}}');
                        ['/api/movies/999','/api/movies/m55'].forEach(function(url){var x=new XMLHttpRequest();x.open('GET',url);x.responseType='json';x.send();});
                        </script></body></html>""")
                    "/api/movies/m55" -> MockResponse().setHeader("Content-Type","application/json").setBody(
                        """{"hlsSource":[{"default":true,"quality":{"720":"https://cdn.test/five.m3u8 or https://mirror.test/five.m3u8"}}]}""")
                    else -> MockResponse().setHeader("Content-Type","application/json").setBody(
                        """{"hlsSource":[{"default":true,"quality":{"720":"https://cdn.test/wrong.m3u8"}}]}""")
                }
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"tv","Show",provider="kinokong",sourceUrl=server.url("/17-show.html").toString()))
                val episode=result.episodes.single()
                assertEquals(2,episode.season);assertEquals(5,episode.number);assertEquals("Дубляж",episode.voice)
                assertEquals(listOf("https://cdn.test/five.m3u8","https://mirror.test/five.m3u8"),episode.alternatives[720])
                assertNotNull(result.notice);assertTrue(resolver.diagnostics.contains("Alloha.hlsSource"))
            }
        }
    }

    @Test fun kinokongVenomMovieSourceMapsKeepOnlyDeclaredQualitiesAndIgnoreAdvertisingJson() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse = MockResponse()
                    .setHeader("Content-Type","text/html; charset=utf-8").setBody(if(request.path=="/17-movie.html")
                        """<div id="full_17"><ul class="newTabs"><li data-iframe="/frame">Смотреть</li></ul></div>"""
                    else """<html><body><div id="player"></div><script>
                        JSON.parse('{"source":{"hls":"https://cdn.test/ad.m3u8"}}');
                        window.VenomPlayer={make:function(opts){return opts;}};
                        VenomPlayer.make({container:document.getElementById('player'),translation:'Voice',source:{file:{
                        720:'https://cdn.test/movie.mp4',1080:'https://cdn.test/movie-high.mp4'}}});
                        </script></body></html>""")
            }
            ActivityScenario.launch(MediaCenterActivity::class.java).use { scenario ->
                lateinit var resolver:CenterOnline;scenario.onActivity { resolver=CenterOnline(it) }
                val result=resolver.resolve(CenterTitle(17,"movie","Movie",provider="kinokong",sourceUrl=server.url("/17-movie.html").toString()))
                assertEquals(setOf(720,1080),result.episodes.single().sources.keys)
                assertEquals("Voice",result.episodes.single().voice);assertFalse(result.episodes.single().sources.values.any { it.contains("/ad.") })
            }
        }
    }
}
