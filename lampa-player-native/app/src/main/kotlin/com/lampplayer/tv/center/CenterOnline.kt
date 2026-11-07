package com.lampplayer.tv.center

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okio.Buffer
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.coroutines.resume

data class OnlineEpisode(val season:Int,val number:Int,val title:String,val voice:String,val sources:Map<Int,String>,val alternatives:Map<Int,List<String>> = emptyMap())
data class OnlineVideo(val item:CenterTitle,val episodes:List<OnlineEpisode>,val headers:Map<String,String>,val fetchedAt:Long=System.currentTimeMillis(),val catalogItem:CenterTitle?=null) {
    val identity get()=catalogItem ?: item
}
class OnlineSourceBlocked(val reason:String,message:String):IllegalStateException(message)
class OnlineMediaUnavailable(val status:Int, val detail:String, message:String):IllegalStateException(message)

/** Resolves the selected provider's own player metadata, without showing a website or starting video. */
class CenterOnline(private val activity:AppCompatActivity) {
    var diagnostics: String = "not started"; private set
    suspend fun resolve(item:CenterTitle):OnlineVideo = withContext(Dispatchers.Main.immediate) {
        val source=source(item)
        com.lampplayer.tv.data.BrowserMediaSession.refresh()
        var web:WebView?=null
        val issues=mutableListOf<String>()
        val active=AtomicBoolean(true)
        var preparedFrames=0
        val libraryCalls=Collections.synchronizedSet(mutableSetOf<Call>())
        try {
            @SuppressLint("SetJavaScriptEnabled")
            val browser=WebView(activity).apply {
                settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                settings.allowFileAccess=false;settings.allowContentAccess=false
                settings.mediaPlaybackRequiresUserGesture=true;settings.setSupportMultipleWindows(false)
                settings.cacheMode=WebSettings.LOAD_NO_CACHE
                isFocusable=false;importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;alpha=0f
            }
            web=browser
            val preparedPage=if(item.provider=="kinokong") {
                diagnostics="bootstrap=native stage=page"
                val frames=CenterCatalog().onlineFrames(item);preparedFrames=frames.size
                diagnostics="bootstrap=native prepared=$preparedFrames stage=webview"
                kinokongDocument(item,frames).toByteArray(Charsets.UTF_8)
            } else null
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(browser,true)
            browser.webChromeClient=object:WebChromeClient() {
                override fun onConsoleMessage(message:ConsoleMessage):Boolean {
                    if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR&&message.sourceId().toHttpUrlOrNull()?.host==source.host&&issues.size<4)
                        issues+=message.message().replace(Regex("https?://\\S+"),"<url>").take(100)
                    return false
                }
            }
            check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                "Для получения списка серий обнови Android System WebView на устройстве"
            }
            val script=activity.assets.open("center/online-resolver.js").bufferedReader().use { it.readText() }
            val injected = if(item.provider in listOf("kinogo","kinokong")) kinogoScript(source,script,item.provider.orEmpty()) else script
            val origins = if(item.provider in listOf("kinogo","kinokong")) setOf("*") else setOf("${source.scheme}://${source.host}" + if(source.port!=443&&source.port!=80) ":${source.port}" else "")
            WebViewCompat.addDocumentStartJavaScript(browser,injected,origins)
            browser.webViewClient=object:WebViewClient() {
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    val url=request.url.toString().toHttpUrlOrNull() ?: return null
                    if(request.isForMainFrame&&preparedPage!=null&&sameTitleNavigation(item,url.toString()))
                        return WebResourceResponse("text/html","UTF-8",ByteArrayInputStream(preparedPage))
                    if(request.isForMainFrame||!url.encodedPath.endsWith("playerjs.js",true)||(item.provider !in listOf("kinogo","kinokong")&&url.host!=source.host))return null
                    val call=libraryHttp.newCall(Request.Builder().url(url).apply {
                        request.requestHeaders.filterKeys { it.equals("User-Agent",true)||it.equals("Referer",true)||it.equals("Cookie",true) }
                            .forEach { (name,value) -> header(name,value) }
                    }.build())
                    synchronized(libraryCalls) { if(!active.get())return null;libraryCalls.add(call) }
                    return try { call.execute().use { response ->
                        if(!response.isSuccessful)return null
                        val body=response.body ?: return null
                        val buffer=Buffer();val limit=2*1024*1024L
                        while(buffer.size<=limit)if(body.source().read(buffer,minOf(8192L,limit+1-buffer.size))==-1L)break
                        if(buffer.size>limit)return null
                        val patch="\n;window.__lampInstallPlayer&&window.__lampInstallPlayer();\n".toByteArray(Charsets.US_ASCII)
                        WebResourceResponse("application/javascript",body.contentType()?.charset()?.name() ?: "UTF-8",ByteArrayInputStream(buffer.readByteArray()+patch))
                    } } catch(_:Exception) { null } finally { libraryCalls.remove(call) }
                }
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    if(!request.isForMainFrame)return false
                    // Resolver sessions never navigate to a different title, login page or advertising destination.
                    return !sameTitleNavigation(item,request.url.toString())
                }
            }
            // Use a real viewport: responsive players may skip initialization in a 1px box.
            // The catalog stays above this transparent, non-focusable metadata view.
            (activity.findViewById<android.view.View>(android.R.id.content) as ViewGroup).addView(browser,0,FrameLayout.LayoutParams(-1,-1))
            browser.doOnLayout { if(active.get())browser.loadUrl(source.toString()) }
            var last="";var stable=0;var filmixRequested=false;var emptyPasses=0
            withTimeout(30000) {
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val raw=evaluate(browser,if(item.provider in listOf("kinogo","kinokong"))
                        "window.__lampKinogoSnapshot ? window.__lampKinogoSnapshot() : '{}'"
                        else "window.__lampSnapshot ? window.__lampSnapshot() : '{}' ")
                    val observed=runCatching { JsonParser.parseString(JsonParser.parseString(raw).asString).asJsonObject }.getOrElse { JsonObject() }
                    var frameReferer: String?=null
                    var frameInfo=""
                    var allowEntries=true
                    val snapshot=if(item.provider in listOf("kinogo","kinokong")) {
                        val candidates=observed.getAsJsonArray("candidates")?.toList().orEmpty()
                            .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                        val usable=candidates.firstOrNull { entry ->
                            val data=entry.get("snapshot")?.takeIf { it.isJsonObject }?.asJsonObject
                            data!=null && (data.get("blocked")==null || data.get("blocked").isJsonNull) && decode(item,data).isNotEmpty()
                        }
                        frameReferer=usable?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                        val pending=observed.get("expected")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                        val alternateAvailable=observed.get("alternateAvailable")?.asBoolean ?: false
                        val alternateRequested=observed.get("alternate")?.asBoolean ?: false
                        val alternatePending=observed.get("alternatePending")?.asBoolean ?: false
                        val allBlocked=(!alternateAvailable||alternateRequested&&!alternatePending) && pending>0 && candidates.size==pending &&
                            candidates.all { e -> e.getAsJsonObject("snapshot")?.get("blocked")?.isJsonNull==false }
                        frameInfo=(if(item.provider=="kinokong")"bootstrap=native prepared=$preparedFrames root=${observed.get("root")} " else "")+"frames=$pending replies=${candidates.size} alternate=$alternateRequested waiting=$alternatePending " + candidates.mapIndexed { index, entry ->
                            val data=entry.getAsJsonObject("snapshot") ?: JsonObject()
                            val host=entry.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.toHttpUrlOrNull()?.host.orEmpty()
                            "frame$index[host=$host player=${data.get("playerType")} ids=${data.get("ids")} files=${data.getAsJsonArray("files")?.size()} lists=${data.getAsJsonArray("lists")?.size()} xhr=${data.get("xhr")} shapes=${data.get("shapes")} json=${data.get("jsonXhr")} blocked=${data.get("blocked")?.isJsonNull==false}] "
                        }.joinToString("")
                        allowEntries=usable!=null
                        // Preserve the actual packet for diagnostics even when its playlist is not understood.
                        (usable ?: candidates.firstOrNull())?.getAsJsonObject("snapshot")?.deepCopy()?.apply {
                            if(!allBlocked)remove("blocked")
                        } ?: JsonObject()
                    } else observed
                    diagnostics=frameInfo+"files=${snapshot.getAsJsonArray("files")?.size()} lists=${snapshot.getAsJsonArray("lists")?.size()} player=${snapshot.get("playerType")} ids=${snapshot.get("ids")} xhr=${snapshot.get("xhr")}"
                    snapshot.get("blocked")?.takeIf { !it.isJsonNull }?.asString?.let {
                        throw OnlineSourceBlocked(if(it.contains("стране",true)||it.contains("регион",true)) "country" else if(it.contains("правообладателя",true)) "rights" else "unavailable",
                            "${CenterCatalog.name(item.provider)}: $it")
                    }
                    if(item.provider=="filmix"&&!filmixRequested) {
                        val started=evaluate(browser,"(function(){var el=document.querySelector('.players[data-player]');if(el&&String(el.getAttribute('data-player'))==='${item.id}'&&typeof getDataPlayer==='function'){getDataPlayer(${item.id});return true;}return false;})()")
                        if(started=="true")filmixRequested=true
                    }
                    val entries=if(allowEntries)decode(item,snapshot) else emptyList()
                    diagnostics += " entries=${entries.size} mirrors=${entries.sumOf { e -> e.alternatives.values.sumOf { maxOf(0,it.size-1) } }} hosts=${entries.flatMap { e -> e.alternatives.values.flatten().ifEmpty { e.sources.values.toList() } }.mapNotNull { it.toHttpUrlOrNull()?.host }.distinct().take(6)} errors=$issues"
                    if(entries.isNotEmpty()) {
                        val signature=Gson().toJson(entries)
                        stable=if(signature==last)stable+1 else 0;last=signature
                        if(stable>=4) {
                            val referer=frameReferer ?: browser.url?.takeIf { sameTitleNavigation(item,it) } ?: source.toString()
                            val origin=referer.toHttpUrlOrNull() ?: source
                            return@withTimeout OnlineVideo(item,entries,mapOf(
                                "User-Agent" to browser.settings.userAgentString,"Referer" to referer,
                                "Origin" to origin.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')))
                        }
                    }
                    if(item.provider in listOf("kinogo","kinokong")&&entries.isEmpty()&&++emptyPasses>=20)
                        evaluate(browser,"window.__lampKinogoTryAlternate ? window.__lampKinogoTryAlternate() : false")
                    delay(300)
                }
                @Suppress("UNREACHABLE_CODE") error("Нет списка серий")
            }
        } catch(e:TimeoutCancellationException) {
            error("${CenterCatalog.name(item.provider)} не отдал список серий. Попробуй повторить или выбери другой источник")
        } finally {
            active.set(false);synchronized(libraryCalls) { libraryCalls.toList() }.forEach { it.cancel() }
            web?.let { it.stopLoading();(it.parent as? ViewGroup)?.removeView(it);it.destroy() }
        }
    }
    private fun kinogoScript(source:okhttp3.HttpUrl, common:String,provider:String):String {
        val config=JsonObject().apply {
            addProperty("session",java.util.UUID.randomUUID().toString())
            addProperty("origin",source.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
            addProperty("id",source.encodedPath.substringAfter('/').substringBefore('-').toInt())
        }
        val controls=activity.assets.open(if(provider=="kinokong") "center/kinokong-player-controls.js" else "center/kinogo-player-controls.js").bufferedReader().use { it.readText() }
        val bridge=activity.assets.open(if(provider=="kinokong") "center/kinokong-resolver.js" else "center/kinogo-resolver.js").bufferedReader().use { it.readText() }
        return "(function(){var cfg="+config.toString()+";"+
            "var p=location.pathname;var root=window===window.top&&location.origin===cfg.origin&&p.indexOf('/'+cfg.id+'-')===0&&p.lastIndexOf('/')===0&&p.endsWith('.html');"+
            "var frame=window!==window.top&&window.parent===window.top&&/^https?:$/.test(location.protocol);"+
            "if(!root&&!frame)return;window.__lampKinogoConfig=cfg;window.__lampMetadataFrame=frame;"+
            common+"\n"+controls+"\n"+bridge+"\n})();"
    }
    private suspend fun evaluate(web:WebView,script:String):String = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(script) { if(continuation.isActive)continuation.resume(it ?: "null") }
    }
    companion object {

        /** A native HTML shell keeps the source origin/Referer, without running catalog advertising/layout scripts. */
        fun kinokongDocument(item:CenterTitle,frames:List<String>):String {
            require(item.provider=="kinokong"&&frames.isNotEmpty()&&frames.size<=3)
            source(item)
            val document=org.jsoup.nodes.Document.createShell(item.sourceUrl!!)
            document.head().appendElement("meta").attr("charset","UTF-8")
            val root=document.body().appendElement("div").attr("id","full_${item.id}")
            val tabs=root.appendElement("ul").addClass("newTabs")
            frames.forEachIndexed { index,url -> tabs.appendElement("li").attr("data-iframe",url)
                .apply { if(index==0)addClass("current") }.text("Плеер ${index+1}") }
            root.appendElement("div").attr("id","preroll").appendElement("iframe")
                .attr("src",frames.first()).attr("data-src",frames.first()).attr("loading","eager")
                .attr("width","100%").attr("height","480").attr("style","display:block;width:100%;height:70vh;min-height:360px;border:0")
            document.body().appendElement("script").appendChild(org.jsoup.nodes.DataNode("""
                document.querySelectorAll('.newTabs > li').forEach(function(tab){tab.addEventListener('click',function(){
                    document.querySelectorAll('.newTabs > li').forEach(function(el){el.classList.remove('current');});
                    tab.classList.add('current');var frame=document.querySelector('#preroll iframe');
                    var url=tab.getAttribute('data-iframe');frame.setAttribute('data-src',url);
                    if(frame.getAttribute('src')!==url)frame.setAttribute('src',url);
                });});
            """.trimIndent()))
            return document.outerHtml()
        }

        fun onlineSupported(provider: String?) = provider in listOf("kinogo","kinovod","kinokong")
        private data class MediaProbe(val status:Int,val kind:String,val bytes:ByteArray,val resolved:okhttp3.HttpUrl) {
            val text get()=bytes.toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
            val success get()=status in 200..299
        }
        /** Bound all reads; verify the HLS chain before launching another player. */
        suspend fun playableUrl(url:String,headers:Map<String,String>):String {
            suspend fun probe(target:String,range:Boolean=false):MediaProbe = suspendCancellableCoroutine { continuation ->
                val call=libraryHttp.newBuilder().cookieJar(if(headers.keys.any { it.equals("Cookie",true) })okhttp3.CookieJar.NO_COOKIES else com.lampplayer.tv.data.BrowserMediaSession).followRedirects(true).build().newCall(Request.Builder().url(target)
                    .apply {
                        mediaHeaders(target,headers).forEach { (key,value)->header(key,value) }
                        // HLS clients request manifests without a byte range. Reads remain capped below.
                        if(range)header("Range","bytes=0-65535")
                    }.build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object:okhttp3.Callback {
                    override fun onFailure(call:Call,e:java.io.IOException) { if(continuation.isActive)continuation.resumeWith(Result.failure(e)) }
                    override fun onResponse(call:Call,response:okhttp3.Response) {
                        val result=runCatching { response.use {
                            val bytes=java.io.ByteArrayOutputStream()
                            response.body?.byteStream()?.use { stream ->
                                val buffer=ByteArray(4096)
                                while(bytes.size()<65536) {
                                    val read=stream.read(buffer,0,minOf(buffer.size,65536-bytes.size()))
                                    if(read<0)break
                                    if(read>0)bytes.write(buffer,0,read)
                                }
                            }
                            val data=bytes.toByteArray()
                            val prefix=data.toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
                            val kind=when {
                                prefix.startsWith("#EXTM3U")->"hls"
                                data.size>=8 && String(data,4,4,Charsets.US_ASCII)=="ftyp"->"mp4"
                                prefix.startsWith("<")->"html"
                                response.body?.contentType()?.type=="video" && data.isNotEmpty()->"video"
                                else->"unknown"
                            }
                            MediaProbe(response.code,kind,data,response.request.url)
                        } }
                        if(continuation.isActive)continuation.resumeWith(result)
                    }
                })
            }
            suspend fun verify(target:String):Pair<MediaProbe,String> {
                var current=probe(target,range=!target.toHttpUrlOrNull()!!.encodedPath.endsWith(".m3u8",true))
                if(!current.success || current.kind !in listOf("hls","mp4","video"))return current to "адрес видео"
                repeat(3) { depth ->
                    if(current.kind!="hls")return current to "фрагмент"
                    val next=current.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty()&&!it.startsWith("#") }
                        ?: return current.copy(kind="empty-hls") to "пустой плейлист"
                    val uri=current.resolved.resolve(next) ?: return current.copy(kind="invalid-hls") to "адрес в плейлисте"
                    current=probe(uri.toString())
                    if(!current.success || current.kind=="html" || current.bytes.isEmpty())
                        return current to if(depth==0) "HLS/фрагмент" else "фрагмент"
                }
                return current to "HLS"
            }
            val original=verify(url)
            fun accepted(p:MediaProbe)=p.success && p.bytes.isNotEmpty() && p.kind !in listOf("html","empty-hls","invalid-hls","unknown")
            if(accepted(original.first)||original.first.success&&original.second=="фрагмент"&&original.first.bytes.isNotEmpty()&&original.first.kind=="unknown")return url
            val parsed=url.toHttpUrlOrNull() ?: error("Некорректный адрес видео")
            var detail="${original.second}: HTTP ${original.first.status}, ${original.first.kind}"
            if(original.first.status==404 && Regex("(?i)\\.mp4/[^/]+\\.m3u8$").containsMatchIn(parsed.encodedPath)) {
                val fallback=parsed.newBuilder().encodedPath(parsed.encodedPath.substringBeforeLast('/')).build().toString()
                val direct=probe(fallback,range=true)
                if(direct.success && direct.kind in listOf("mp4","video"))return fallback
                detail+="; MP4: HTTP ${direct.status}, ${direct.kind}"
            }
            throw OnlineMediaUnavailable(original.first.status,detail,
                "Источник не отдал видео. $detail. Обнови ссылку или открой плеер сайта.")
        }
        /** Match the browser's default strict-origin-when-cross-origin referrer behavior. */
        fun mediaHeaders(target:String,headers:Map<String,String>):Map<String,String> {
            val ref=headers.entries.firstOrNull { it.key.equals("Referer",true) } ?: return headers
            val from=ref.value.toHttpUrlOrNull() ?: return headers
            val to=target.toHttpUrlOrNull() ?: return headers
            val result=headers.filterKeys { !it.equals("Referer",true) }.toMutableMap()
            if(from.isHttps&&!to.isHttps)return result
            result["Referer"]=if(from.scheme==to.scheme&&from.host==to.host&&from.port==to.port)
                from.newBuilder().fragment(null).build().toString()
            else from.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
            return result
        }
        suspend fun firstPlayable(urls:List<String>,headers:Map<String,String>,
            verify:suspend (String,Map<String,String>)->String = { url,values -> playableUrl(url,values) }):String {
            require(urls.isNotEmpty()&&urls.size<=4) { "Нет адреса выбранного видео" }
            var missing:Exception?=null
            for(url in urls.distinct()) {
                try { return verify(url,headers) } catch(error:Exception) {
                    if(error is CancellationException)throw error
                    if(!generateSequence<Throwable>(error) { it.cause }.any { it is OnlineMediaUnavailable&&it.status==404 })throw error
                    missing=error
                }
            }
            throw missing ?: IllegalStateException("Источник не отдал видео")
        }
        private val libraryHttp=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).build()
        fun source(item:CenterTitle):okhttp3.HttpUrl {
            val url=item.sourceUrl?.toHttpUrlOrNull() ?: error("Не указан адрес источника")
            if(item.provider in listOf("kinogo","kinokong")) {
                val id=Regex("^/([0-9]+)-[^/]+\\.html$").find(url.encodedPath)?.groupValues?.get(1)?.toIntOrNull()
                check(id==item.id && item.type in listOf("movie","tv")) { "Адрес ${CenterCatalog.name(item.provider)} не соответствует выбранному фильму или сериалу" }
                return url
            }
            val match=when(item.provider) {
                "kinovod" -> Regex("^/(film|serial)/(\\d+)-").find(url.encodedPath)
                "filmix" -> Regex("^/(film|seria)/(?:[^/]+/)*(\\d+)-").find(url.encodedPath)
                else -> null
            }
            check(match!=null&&match.groupValues[2].toIntOrNull()==item.id&&
                (if(match.groupValues[1]=="film") "movie" else "tv")==item.type) { "Адрес источника не соответствует выбранному фильму или сериалу" }
            return url
        }
        fun sameTitleNavigation(item:CenterTitle,target:String):Boolean {
            val original=runCatching { source(item) }.getOrNull() ?: return false
            val next=target.toHttpUrlOrNull() ?: return false
            if(next.scheme!=original.scheme||next.host!=original.host||next.port!=original.port)return false
            return runCatching { source(item.copy(sourceUrl=next.toString())) }.isSuccess
        }
        private fun JsonObject.text(key:String)=get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun decode(item:CenterTitle,snapshot:JsonObject):List<OnlineEpisode> {
            val found=mutableListOf<OnlineEpisode>();var nodes=0
            val cap=if(item.provider=="filmix") snapshot.get("cap")?.takeIf { it.isJsonPrimitive }?.asInt ?: 480 else 10000
            fun walk(value:JsonElement,season:Int?,voice:String,depth:Int) {
                if(depth>12||nodes++>3000)return
                if(value.isJsonArray) { value.asJsonArray.take(1000).forEach { walk(it,season,voice,depth+1) };return }
                if(value.isJsonPrimitive) {
                    if(item.type=="movie")sourceCandidates(value.asString,cap).takeIf { it.isNotEmpty() }?.let { found+=OnlineEpisode(0,0,item.title,voice.ifBlank { "Основная озвучка" },it.mapValues { e -> e.value.first() },it) }
                    return
                }
                if(!value.isJsonObject)return
                val obj=value.asJsonObject;val title=org.jsoup.Jsoup.parse(obj.text("title")).text().take(200)
                val seasonMatch=Regex("(?iu)(?:сезон|season)\\s*(\\d{1,2})|(\\d{1,2})\\s*(?:сезон|season)").find(title)
                val nextSeason=obj.text("season").toIntOrNull()?.takeIf { it>0 } ?: seasonMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: season
                val folder=obj.get("folder") ?: obj.get("file")?.takeIf { it.isJsonArray }
                if(folder!=null) {
                    walk(folder,nextSeason,if(seasonMatch==null&&title.isNotBlank())title else voice,depth+1);return
                }
                val raw=obj.text("file");if(raw.isBlank())return
                val numbered=Regex("(?iu)s(\\d{1,2})[ ._-]*e(\\d{1,3})").find(title+" "+obj.text("id"))
                val episode=obj.text("episode").toIntOrNull()?.takeIf { it>0 } ?: numbered?.groupValues?.get(2)?.toIntOrNull() ?:
                    Regex("(?iu)(\\d{1,3})\\s*(?:серия|эпизод|episode)|(?:серия|эпизод|episode)\\s*(\\d{1,3})").find(title)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?:
                    Regex("^(\\d{1,3})(?:[ .:—-]|$)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                if(item.type=="tv"&&(episode==null||episode<=0))return
                val alternatives=sourceCandidates(raw,cap);val urls=alternatives.mapValues { it.value.first() };if(urls.isEmpty())return
                found+=OnlineEpisode(if(item.type=="tv") numbered?.groupValues?.get(1)?.toIntOrNull() ?: nextSeason ?: 0 else 0,
                    if(item.type=="tv")episode!! else 0,title.ifBlank { item.title },voice.ifBlank { if(item.type=="movie"&&title.isNotBlank())title else "Основная озвучка" },urls,alternatives)
            }
            snapshot.getAsJsonArray("lists")?.forEach { walk(it,null,"",0) }
            if(item.type=="movie")snapshot.getAsJsonArray("files")?.forEach { walk(it,null,"",0) }
            // Repeated JSON observations are one entry. Conflicting encodes remain explicit choices, never automatic next.
            return found.distinctBy { listOf(it.season,it.number,it.voice,it.sources,it.alternatives) }.sortedWith(compareBy({it.season},{it.voice},{it.number}))
        }
        fun sources(text:String,cap:Int=10000):Map<Int,String> =
            sourceCandidates(text,cap).mapValues { it.value.first() }

        /** Playerjs "or"/"and" operators declare mirrors of the same quality, not part of a URL. */
        fun sourceCandidates(text:String,cap:Int=10000):Map<Int,List<String>> {
            if(text.length>30000||text.startsWith("#"))return emptyMap()
            val parts=if(text.trimStart().startsWith("["))text.split(Regex(",(?=\\s*\\[)")) else listOf(text)
            val result=linkedMapOf<Int,List<String>>();var ambiguous=false
            parts.take(12).forEach { part ->
                val label=Regex("^\\s*\\[([^]]+)]").find(part)
                val labeledQuality=label?.groupValues?.get(1)?.let { Regex("[0-9]{3,4}").find(it)?.value?.toIntOrNull() }
                val raw=if(label==null)part.trim() else part.substring(label.range.last+1).trim()
                val declared=raw.split(Regex("\\s+(?:or|and)\\s+(?=(?:https?:)?//)"))
                if(declared.size>4)return@forEach
                val urls=declared.mapNotNull { candidate ->
                    val value=candidate.trim()
                    val url=(if(value.startsWith("//"))"https:$value" else value).toHttpUrlOrNull() ?: return@mapNotNull null
                    if(!Regex("(?i)\\.(m3u8|mp4|mkv|m4v|webm|mov|avi)$").containsMatchIn(url.encodedPath))return@mapNotNull null
                    url
                }
                if(urls.size!=declared.size||urls.isEmpty())return@forEach
                val path=urls.first().encodedPath.lowercase(Locale.ROOT)
                val quality=labeledQuality ?: Regex("[_.-](2160|1440|1080|720|480|360)p?(?:[_.-]|$)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if(quality>cap)return@forEach
                val choices=urls.map { it.toString() }.distinct()
                if(result[quality]!=null&&result[quality]!=choices)ambiguous=true
                result[quality]=choices
            }
            return if(ambiguous)emptyMap() else result
        }
    }
}
