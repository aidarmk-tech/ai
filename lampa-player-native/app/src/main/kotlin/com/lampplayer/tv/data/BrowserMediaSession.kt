package com.lampplayer.tv.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.CookieHandler
import java.net.URI

/** Share browser media sessions without copying a Cookie header between hosts or into saved metadata. */
object BrowserMediaSession : CookieJar {
    @Volatile private var browserCookies: ((String) -> String?)? = null
    private val nativeCookies = object:LinkedHashMap<String,Cookie>(16,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Cookie>?)=size>128
    }
    @Synchronized fun refresh() { nativeCookies.clear() }
    private fun key(cookie:Cookie)=cookie.domain+"|"+cookie.path+"|"+cookie.name

    @Synchronized fun install(lookup:(String)->String?) {
        browserCookies=lookup
        if(CookieHandler.getDefault() !is SessionCookieHandler)CookieHandler.setDefault(SessionCookieHandler(this))
    }

    @Synchronized override fun loadForRequest(url:HttpUrl):List<Cookie> {
        val result=linkedMapOf<String,Cookie>()
        val browser=runCatching { browserCookies?.invoke(url.toString()) }.getOrNull().orEmpty()
        browser.split(';').forEach { pair ->
            val separator=pair.indexOf('=')
            if(separator<=0)return@forEach
            runCatching {
                Cookie.Builder().name(pair.substring(0,separator).trim()).value(pair.substring(separator+1).trim())
                    .hostOnlyDomain(url.host).path("/").apply { if(url.isHttps)secure() }.build()
            }.getOrNull()?.let { result[it.name]=it }
        }
        val now=System.currentTimeMillis()
        // Expired response cookies suppress an older browser value until metadata is refreshed.
        // Preserve distinct path-scoped cookies and prefer a newer native response over its browser value.
        val matching=nativeCookies.values.filter { it.matches(url) }.sortedByDescending { it.path.length }
        matching.forEach { result.remove(it.name) }
        return result.values.toList()+matching.filter { it.expiresAt>now }
    }

    @Synchronized override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>) {
        cookies.forEach { cookie ->
            // OkHttp validates Set-Cookie domains; this extra check also protects direct handler calls.
            if(url.host==cookie.domain || !cookie.hostOnly&&url.host.endsWith("."+cookie.domain))nativeCookies[key(cookie)]=cookie
        }
    }

    internal class SessionCookieHandler(private val jar:CookieJar) : CookieHandler() {
        override fun get(uri:URI,requestHeaders:Map<String,List<String>>):Map<String,List<String>> {
            if(requestHeaders.keys.any { it.equals("Cookie",true) })return emptyMap()
            val url=uri.toString().toHttpUrlOrNull() ?: return emptyMap()
            val cookies=jar.loadForRequest(url)
            return if(cookies.isEmpty())emptyMap() else mapOf("Cookie" to listOf(cookies.joinToString("; ") { it.name+"="+it.value }))
        }
        override fun put(uri:URI,responseHeaders:Map<String,List<String>>) {
            val url=uri.toString().toHttpUrlOrNull() ?: return
            val cookies=responseHeaders.entries.filter { it.key.equals("Set-Cookie",true) }
                .flatMap { it.value }.mapNotNull { Cookie.parse(url,it) }
            jar.saveFromResponse(url,cookies)
        }
    }
}
