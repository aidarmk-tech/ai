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

data class OnlineEpisode(val season:Int,val number:Int,val title:String,val voice:String,val sources:Map<Int,String>)
data class OnlineVideo(val item:CenterTitle,val episodes:List<OnlineEpisode>,val headers:Map<String,String>,val fetchedAt:Long=System.currentTimeMillis())
class OnlineSourceBlocked(val reason:String,message:String):IllegalStateException(message)

/** Resolves the selected provider's own player metadata, without showing a website or starting video. */
class CenterOnline(private val activity:AppCompatActivity) {
    var diagnostics: String = "not started"; private set
    suspend fun resolve(item:CenterTitle):OnlineVideo = withContext(Dispatchers.Main.immediate) {
        val source=source(item)
        var web:WebView?=null
        val issues=mutableListOf<String>()
        val active=AtomicBoolean(true)
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
            WebViewCompat.addDocumentStartJavaScript(browser,script,setOf("${source.scheme}://${source.host}" + if(source.port!=443&&source.port!=80) ":${source.port}" else ""))
            browser.webViewClient=object:WebViewClient() {
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    val url=request.url.toString().toHttpUrlOrNull() ?: return null
                    if(request.isForMainFrame||url.host!=source.host||!url.encodedPath.endsWith("playerjs.js",true))return null
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
            var last="";var stable=0;var filmixRequested=false
            withTimeout(30000) {
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val raw=evaluate(browser,"window.__lampSnapshot ? window.__lampSnapshot() : '{}' ")
                    val snapshot=runCatching { JsonParser.parseString(JsonParser.parseString(raw).asString).asJsonObject }.getOrElse { JsonObject() }
                    diagnostics="files=${snapshot.getAsJsonArray("files")?.size()} lists=${snapshot.getAsJsonArray("lists")?.size()} player=${snapshot.get("playerType")} ids=${snapshot.get("ids")} xhr=${snapshot.get("xhr")}"
                    snapshot.get("blocked")?.takeIf { !it.isJsonNull }?.asString?.let {
                        throw OnlineSourceBlocked(if(it.contains("стране",true)) "country" else if(it.contains("правообладателя",true)) "rights" else "unavailable",
                            "${CenterCatalog.name(item.provider)}: $it")
                    }
                    if(item.provider=="filmix"&&!filmixRequested) {
                        val started=evaluate(browser,"(function(){var el=document.querySelector('.players[data-player]');if(el&&String(el.getAttribute('data-player'))==='${item.id}'&&typeof getDataPlayer==='function'){getDataPlayer(${item.id});return true;}return false;})()")
                        if(started=="true")filmixRequested=true
                    }
                    val entries=decode(item,snapshot)
                    diagnostics += " entries=${entries.size} errors=$issues"
                    if(entries.isNotEmpty()) {
                        val signature=Gson().toJson(entries)
                        stable=if(signature==last)stable+1 else 0;last=signature
                        if(stable>=4) {
                            val referer=browser.url?.takeIf { sameTitleNavigation(item,it) } ?: source.toString()
                            return@withTimeout OnlineVideo(item,entries,mapOf(
                                "User-Agent" to browser.settings.userAgentString,"Referer" to referer,
                                "Origin" to "${source.scheme}://${source.host}"))
                        }
                    }
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
    private suspend fun evaluate(web:WebView,script:String):String = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(script) { if(continuation.isActive)continuation.resume(it ?: "null") }
    }
    companion object {
        /** Validate the media URL and only use a direct MP4 fallback after a 404 for its HLS manifest. */
        suspend fun playableUrl(url:String,headers:Map<String,String>):String {
            suspend fun probe(target:String):Pair<Int,Boolean> = suspendCancellableCoroutine { continuation ->
                val call=libraryHttp.newBuilder().followRedirects(true).build().newCall(Request.Builder().url(target)
                    .apply { headers.forEach { (key,value)->header(key,value) };header("Range","bytes=0-4095") }.build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object:okhttp3.Callback {
                    override fun onFailure(call:Call,e:java.io.IOException) { if(continuation.isActive)continuation.resumeWith(Result.failure(e)) }
                    override fun onResponse(call:Call,response:okhttp3.Response) {
                        val result=runCatching { response.use {
                            val body=response.body
                            val data=body?.byteStream()?.let { stream -> val bytes=ByteArray(4096);val count=stream.read(bytes);if(count>0)bytes.copyOf(count) else byteArrayOf() } ?: byteArrayOf()
                            val prefix=data.toString(Charsets.UTF_8).trimStart()
                            val media=prefix.startsWith("#EXTM3U") || (data.size>=8 && String(data,4,4,Charsets.US_ASCII)=="ftyp") ||
                                (body?.contentType()?.type=="video" && data.isNotEmpty())
                            response.code to (response.isSuccessful && media)
                        } }
                        if(continuation.isActive)continuation.resumeWith(result)
                    }
                })
            }
            val original=probe(url)
            if(original.second)return url
            val parsed=url.toHttpUrlOrNull() ?: error("Некорректный адрес видео")
            val path=parsed.encodedPath
            if(original.first==404 && Regex("(?i)\\.mp4/[^/]+\\.m3u8$").containsMatchIn(path)) {
                val fallback=parsed.newBuilder().encodedPath(path.substringBeforeLast('/')).build().toString()
                if(probe(fallback).second)return fallback
            }
            error(if(original.first==404) "Видео по этой ссылке не найдено (HTTP 404). Обнови ссылки или выбери другой источник."
                else "Источник не отдал видео: HTTP ${original.first}")
        }
        private val libraryHttp=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).build()
        fun source(item:CenterTitle):okhttp3.HttpUrl {
            val url=item.sourceUrl?.toHttpUrlOrNull() ?: error("Не указан адрес источника")
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
                    if(item.type=="movie")sources(value.asString,cap).takeIf { it.isNotEmpty() }?.let { found+=OnlineEpisode(0,0,item.title,voice.ifBlank { "Основная озвучка" },it) }
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
                val urls=sources(raw,cap);if(urls.isEmpty())return
                found+=OnlineEpisode(if(item.type=="tv") numbered?.groupValues?.get(1)?.toIntOrNull() ?: nextSeason ?: 0 else 0,
                    if(item.type=="tv")episode!! else 0,title.ifBlank { item.title },voice.ifBlank { if(item.type=="movie"&&title.isNotBlank())title else "Основная озвучка" },urls)
            }
            snapshot.getAsJsonArray("lists")?.forEach { walk(it,null,"",0) }
            if(item.type=="movie")snapshot.getAsJsonArray("files")?.forEach { walk(it,null,"",0) }
            // Repeated JSON observations are one entry. Conflicting encodes remain explicit choices, never automatic next.
            return found.distinctBy { listOf(it.season,it.number,it.voice,it.sources) }.sortedWith(compareBy({it.season},{it.voice},{it.number}))
        }
        fun sources(text:String,cap:Int=10000):Map<Int,String> {
            if(text.length>30000||text.startsWith("#"))return emptyMap()
            val parts=if(text.trimStart().startsWith("["))text.split(Regex(",(?=\\s*\\[)")) else listOf(text)
            val result=linkedMapOf<Int,String>();var ambiguous=false
            parts.take(12).forEach { part ->
                val label=Regex("^\\s*\\[([^]]+)]").find(part)
                val labeledQuality=label?.groupValues?.get(1)?.let { Regex("[0-9]{3,4}").find(it)?.value?.toIntOrNull() }
                val raw=if(label==null)part.trim() else part.substring(label.range.last+1).trim()
                val url=(if(raw.startsWith("//"))"https:$raw" else raw).toHttpUrlOrNull() ?: return@forEach
                val path=url.encodedPath.lowercase(Locale.ROOT)
                val quality=labeledQuality ?: Regex("[_.-](2160|1440|1080|720|480|360)p?(?:[_.-]|$)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if(quality>cap||!Regex("\\.(m3u8|mp4|mkv|m4v|webm|mov|avi)$").containsMatchIn(path))return@forEach
                if(result[quality]!=null&&result[quality]!=url.toString())ambiguous=true
                result[quality]=url.toString()
            }
            return if(ambiguous)emptyMap() else result
        }
    }
}
