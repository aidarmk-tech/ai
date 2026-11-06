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
                    while(true) { val s=state();if(s.get("tab").asString=="Плеер 2"&&s.get("scroll").asInt>1000&&s.get("focused").asString=="IFRAME")break;kotlinx.coroutines.delay(100) }
                }
                assertEquals(source,state().get("url").asString)
                assertTrue(requests.contains("/embed/movie/10"));assertFalse(requests.contains("/wrong-trailer"))
            }
        }
    }
}
