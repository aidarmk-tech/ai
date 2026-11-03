package com.lampplayer.tv.center

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class OnlineEpisode(val season:Int,val number:Int,val title:String,val voice:String,val sources:Map<Int,String>)
data class OnlineVideo(val item:CenterTitle,val episodes:List<OnlineEpisode>,val headers:Map<String,String>)

/** Resolves the selected provider's own player metadata, without showing a website or starting video. */
class CenterOnline(private val activity:AppCompatActivity) {
    var diagnostics: String = "not started"; private set
    suspend fun resolve(item:CenterTitle):OnlineVideo = withContext(Dispatchers.Main.immediate) {
        val source=item.sourceUrl?.toHttpUrlOrNull() ?: error("Не указан адрес источника")
        var web:WebView?=null
        try {
            @SuppressLint("SetJavaScriptEnabled")
            val browser=WebView(activity).apply {
                settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                settings.allowFileAccess=false;settings.allowContentAccess=false
                settings.mediaPlaybackRequiresUserGesture=true;settings.setSupportMultipleWindows(false)
                isFocusable=false;importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;alpha=0f
            }
            web=browser
            check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                "Для получения списка серий обнови Android System WebView на устройстве"
            }
            val script=activity.assets.open("center/online-resolver.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(browser,script,setOf("${source.scheme}://${source.host}" + if(source.port!=443&&source.port!=80) ":${source.port}" else ""))
            browser.webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    if(!request.isForMainFrame)return false
                    val target=request.url.toString().toHttpUrlOrNull() ?: return true
                    // Resolver sessions never navigate to a different title, login page or advertising destination.
                    return target.host!=source.host || target.encodedPath!=source.encodedPath
                }
            }
            (activity.findViewById<android.view.View>(android.R.id.content) as ViewGroup).addView(browser,FrameLayout.LayoutParams(1,1))
            browser.loadUrl(source.toString())
            var last="";var stable=0;var filmixRequested=false
            withTimeout(30000) {
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val raw=evaluate(browser,"window.__lampSnapshot ? window.__lampSnapshot() : '{}' ")
                    val snapshot=runCatching { JsonParser.parseString(JsonParser.parseString(raw).asString).asJsonObject }.getOrElse { JsonObject() }
                    diagnostics="files=${snapshot.getAsJsonArray("files")?.size()} lists=${snapshot.getAsJsonArray("lists")?.size()} player=${snapshot.get("playerType")}"
                    snapshot.get("blocked")?.takeIf { !it.isJsonNull }?.asString?.let { error("${CenterCatalog.name(item.provider)}: $it") }
                    if(item.provider=="filmix"&&!filmixRequested) {
                        val started=evaluate(browser,"(function(){var el=document.querySelector('.players[data-player]');if(el&&String(el.getAttribute('data-player'))==='${item.id}'&&typeof getDataPlayer==='function'){getDataPlayer(${item.id});return true;}return false;})()")
                        if(started=="true")filmixRequested=true
                    }
                    val entries=decode(item,snapshot)
                    diagnostics += " entries=${entries.size}"
                    if(entries.isNotEmpty()) {
                        val signature=Gson().toJson(entries)
                        stable=if(signature==last)stable+1 else 0;last=signature
                        if(stable>=4) return@withTimeout OnlineVideo(item,entries,mapOf(
                            "User-Agent" to browser.settings.userAgentString,"Referer" to source.toString(),
                            "Origin" to "${source.scheme}://${source.host}"))
                    }
                    delay(300)
                }
                @Suppress("UNREACHABLE_CODE") error("Нет списка серий")
            }
        } catch(e:TimeoutCancellationException) {
            error("${CenterCatalog.name(item.provider)} не отдал список серий. Попробуй повторить или выбери другой источник")
        } finally {
            web?.let { it.stopLoading();(it.parent as? ViewGroup)?.removeView(it);it.destroy() }
        }
    }
    private suspend fun evaluate(web:WebView,script:String):String = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(script) { if(continuation.isActive)continuation.resume(it ?: "null") }
    }
    companion object {
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
                val seasonMatch=Regex("(?i)(?:сезон|season)\\s*(\\d{1,2})|(\\d{1,2})\\s*(?:сезон|season)").find(title)
                val nextSeason=seasonMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: season
                val folder=obj.get("folder") ?: obj.get("file")?.takeIf { it.isJsonArray }
                if(folder!=null) {
                    walk(folder,nextSeason,if(seasonMatch==null&&title.isNotBlank())title else voice,depth+1);return
                }
                val raw=obj.text("file");if(raw.isBlank())return
                val numbered=Regex("(?i)s(\\d{1,2})[ ._-]*e(\\d{1,3})").find(title)
                val episode=numbered?.groupValues?.get(2)?.toIntOrNull() ?:
                    Regex("(?i)(\\d{1,3})\\s*(?:серия|эпизод|episode)|(?:серия|эпизод|episode)\\s*(\\d{1,3})").find(title)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?:
                    Regex("^(\\d{1,3})(?:[ .:—-]|$)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                if(item.type=="tv"&&(episode==null||episode<=0))return
                val urls=sources(raw,cap);if(urls.isEmpty())return
                found+=OnlineEpisode(if(item.type=="tv") numbered?.groupValues?.get(1)?.toIntOrNull() ?: nextSeason ?: 1 else 0,
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
