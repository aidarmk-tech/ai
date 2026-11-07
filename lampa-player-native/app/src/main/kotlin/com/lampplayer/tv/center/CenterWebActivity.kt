package com.lampplayer.tv.center

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lampplayer.tv.player.PlayerActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normal website player fallback; no injected credentials, native bridges or SSL overrides. */
class CenterWebActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private val sitePlayer by lazy { intent.getBooleanExtra("site_player",false) }
    private val kinoGo by lazy {
        sitePlayer && (intent.getStringExtra("catalog_provider")=="kinogo" || intent.getStringExtra("url")?.toHttpUrlOrNull()?.host?.let { it=="kinogo.moi"||it.endsWith(".kinogo.moi") }==true)
    }
    private val kinoKong by lazy { sitePlayer && intent.getStringExtra("catalog_provider")=="kinokong" }
    private val kinoTac by lazy { sitePlayer && intent.getStringExtra("catalog_provider")=="kinotac" }
    private val zona by lazy { sitePlayer && intent.getStringExtra("catalog_provider")=="zona" }
    private var siteTab=0
    private val kinoGoControls by lazy { assets.open(if(kinoTac) "center/kinotac-player-controls.js" else if(zona) "center/zona-player-controls.js" else if(kinoKong) "center/kinokong-player-controls.js" else "center/kinogo-player-controls.js").bufferedReader().use { it.readText() } }
    private lateinit var native: Button
    private var full: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private val streams = linkedMapOf<String, Map<String,String>>()
    private val media by lazy { runCatching { JsonParser.parseString(intent.getStringExtra("lampa_data")).asJsonObject }.getOrElse { JsonObject() } }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra("url")?.toHttpUrlOrNull()
        if (source == null) { finish(); return }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this).apply { setBackgroundColor(Color.rgb(17,28,44)); setPadding(12,5,12,5); gravity = android.view.Gravity.CENTER_VERTICAL }
        fun action(label: String, click: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { click() } }
        toolbar.addView(action("Назад") { back() })
        toolbar.addView(TextView(this).apply {
            text = (if(sitePlayer) "Плеер сайта · " else "") + (media.get("title")?.asString ?: source.host); setTextColor(Color.WHITE); textSize = 17f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(12,0,12,0)
        },LinearLayout.LayoutParams(0,-2,1f))
        if(kinoGo||kinoKong||zona||kinoTac) {
            toolbar.addView(action("Плеер 1") { siteTab=0;focusKinoGo() })
            toolbar.addView(action("Плеер 2") { siteTab=1;focusKinoGo() })
            if(kinoKong||kinoTac)toolbar.addView(action("Плеер 3") { siteTab=2;focusKinoGo() })
        }
        toolbar.addView(action("Обновить") { web.reload() })
        native = action("В наш плеер") { chooseStream() }.apply { visibility = View.GONE }
        toolbar.addView(native)
        body.addView(toolbar,LinearLayout.LayoutParams(-1,-2))
        web = try { WebView(this).apply {
            tag="site_player_web"
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            if(sitePlayer) CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    val next=request.url.toString()
                    if(next.toHttpUrlOrNull()==null)return true
                    if(!sitePlayer||!request.isForMainFrame)return false
                    return !sameCardPage(source.toString(),next,intent.getStringExtra("catalog_provider"))
                }
                @Deprecated("Compatibility with Android 5")
                override fun shouldOverrideUrlLoading(view:WebView,url:String) = url.toHttpUrlOrNull()==null || (sitePlayer&&!sameCardPage(source.toString(),url,intent.getStringExtra("catalog_provider")))
                override fun onPageStarted(view:WebView,url:String,favicon:android.graphics.Bitmap?) {
                    synchronized(streams) { streams.clear() }; native.visibility = View.GONE
                }
                override fun onPageFinished(view:WebView,url:String) {
                    if((kinoGo||kinoKong||zona||kinoTac)&&sameCardPage(source.toString(),url,intent.getStringExtra("catalog_provider")))focusKinoGo()
                }
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    val url=request.url.toString()
                    if (!sitePlayer && isVideo(url)) {
                        val headers = request.requestHeaders.filterKeys { it.equals("Referer",true) || it.equals("User-Agent",true) }
                        synchronized(streams) { if(streams.size < 16) streams[url] = headers }
                        runOnUiThread { if(!isFinishing) native.visibility = View.VISIBLE }
                    }
                    return null
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view:View,callback:CustomViewCallback) {
                    if(full != null) { callback.onCustomViewHidden(); return }
                    full = view; fullCallback = callback
                    root.addView(view,FrameLayout.LayoutParams(-1,-1)); body.visibility = View.GONE
                }
                override fun onHideCustomView() { hideFull(); body.visibility = View.VISIBLE }
            }
        } } catch (e: RuntimeException) {
            Toast.makeText(this,"Системный WebView недоступен на устройстве",Toast.LENGTH_LONG).show(); finish(); return
        }
        body.addView(web,LinearLayout.LayoutParams(-1,0,1f)); root.addView(body); setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
        web.loadUrl(source.toString()); web.requestFocus()
    }
    private fun focusKinoGo() {
        if(!::web.isInitialized||(!kinoGo&&!kinoKong&&!zona&&!kinoTac))return
        val source=intent.getStringExtra("url") ?: return
        if(!sameCardPage(source,web.url ?: return,intent.getStringExtra("catalog_provider")))return
        val name=if(kinoTac) "__lampTacControls" else if(zona) "__lampZonaControls" else if(kinoKong) "__lampKongControls" else "__lampKinogoControls"
        web.evaluateJavascript(kinoGoControls+"\nwindow."+name+".focus("+siteTab+",true);",null)
    }
    private fun hideFull() {
        full?.let { root.removeView(it) }; full = null
        val callback=fullCallback; fullCallback=null; callback?.onCustomViewHidden()
        root.getChildAt(0)?.visibility = View.VISIBLE
    }
    private fun back() { when { full != null -> hideFull(); web.canGoBack() -> web.goBack(); else -> finish() } }
    private fun chooseStream() {
        val choices = synchronized(streams) { streams.toList() }
        if(choices.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Выбери поток для нашего плеера")
            .setItems(choices.map { (url,_) -> url.toHttpUrlOrNull()?.let { "${it.host} · ${it.pathSegments.last().take(65)}" } ?: "Видео" }.toTypedArray()) { _,index ->
                val (url,headers) = choices[index]
                val metadata = media.deepCopy().apply { addProperty("headers",Gson().toJson(headers)) }
                startActivity(Intent(this,PlayerActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setDataAndType(android.net.Uri.parse(url),"video/*").putExtra("lampa_data",metadata.toString()))
            }.setNegativeButton("Остаться на сайте",null).show()
    }
    override fun onPause() {
        if(::web.isInitialized) {
            web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})",null)
            web.onPause()
        }
        super.onPause()
    }
    override fun onResume() { super.onResume(); if(::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        if(::web.isInitialized) { web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }
    companion object {
        fun sameCardPage(source:String,target:String,provider:String?=null):Boolean {
            val original=source.toHttpUrlOrNull() ?: return false
            val next=target.toHttpUrlOrNull() ?: return false
            if(original.scheme!=next.scheme || original.host!=next.host || original.port!=next.port)return false
            val zona=Regex("^/(film|series)/[^/]+-([0-9]+)/?$")
            val selected=if(provider=="zona"||(provider==null&&original.encodedPath.endsWith("/")))zona.matchEntire(original.encodedPath) else null
            if(selected!=null) {
                val match=zona.matchEntire(next.encodedPath) ?: return false
                return match.groupValues[1]==selected.groupValues[1]&&match.groupValues[2]==selected.groupValues[2]
            }
            fun identity(path:String):String? = Regex("^/(?:serial/|film/)?([0-9]+)-[^/]+(?:\\.html)?$").find(path)?.groupValues?.get(1)
            val id=identity(original.encodedPath) ?: return next==original
            return identity(next.encodedPath)==id && (original.encodedPath.startsWith("/serial/")==next.encodedPath.startsWith("/serial/")) &&
                (original.encodedPath.startsWith("/film/")==next.encodedPath.startsWith("/film/"))
        }
        fun isVideo(url:String) = url.toHttpUrlOrNull()?.encodedPath?.let { it.endsWith(".m3u8",true) || it.endsWith(".mp4",true) } == true
    }
}
