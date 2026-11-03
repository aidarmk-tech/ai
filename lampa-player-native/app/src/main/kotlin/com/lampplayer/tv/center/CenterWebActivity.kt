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
            text = media.get("title")?.asString ?: source.host; setTextColor(Color.WHITE); textSize = 17f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(12,0,12,0)
        },LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(action("Обновить") { web.reload() })
        native = action("В наш плеер") { chooseStream() }.apply { visibility = View.GONE }
        toolbar.addView(native)
        body.addView(toolbar,LinearLayout.LayoutParams(-1,-2))
        web = try { WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest) = request.url.toString().toHttpUrlOrNull() == null
                @Deprecated("Compatibility with Android 5")
                override fun shouldOverrideUrlLoading(view:WebView,url:String) = url.toHttpUrlOrNull() == null
                override fun onPageStarted(view:WebView,url:String,favicon:android.graphics.Bitmap?) {
                    synchronized(streams) { streams.clear() }; native.visibility = View.GONE
                }
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    val url=request.url.toString()
                    if (isVideo(url)) {
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
        fun isVideo(url:String) = url.toHttpUrlOrNull()?.encodedPath?.let { it.endsWith(".m3u8",true) || it.endsWith(".mp4",true) } == true
    }
}
