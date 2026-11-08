package com.lampplayer.tv.center

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lampplayer.tv.domain.model.CardMeta
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CenterHistory(val url: String, val card: CardMeta, val at: Long)
class CenterStore(context: Context) {
    private val prefs = context.getSharedPreferences("media_center", Context.MODE_PRIVATE)
    private val gson = Gson()
    var autoUpdates: Boolean
        get() = prefs.getBoolean("auto_updates", true)
        set(value) { prefs.edit().putBoolean("auto_updates", value).apply() }
    var indexer: String
        get() = prefs.getString("indexer", "").orEmpty()
        set(value) { prefs.edit().putString("indexer", value.trim()).apply() }
    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(value) { prefs.edit().putString("api_key", value.trim()).apply() }
    var indexerType: String
        get() = prefs.getString("indexer_type", "jacred").orEmpty()
        set(value) { prefs.edit().putString("indexer_type", value).apply() }
    var catalog: String
        get() = prefs.getString("catalog", "tmdb").orEmpty().takeIf { it in CenterCatalog.providers } ?: "tmdb"
        set(value) { prefs.edit().putString("catalog", value).apply() }
    var searchCatalog:String
        get() = prefs.getString("search_catalog",null)?.takeIf { it in CenterCatalog.providers } ?: catalog
        set(value) { require(value in CenterCatalog.providers);prefs.edit().putString("search_catalog",value).apply() }
    /** Browsing preferences belong to each TMDB media type; search text is never persisted here. */
    fun libraryBrowse(type: String): CenterBrowse {
        require(type in listOf("movie","tv"))
        val key="browse_$type"
        return CenterBrowse(type=type,
            mode=prefs.getString("${key}_mode","popular").takeIf { it=="new" } ?: "popular",
            country=prefs.getString("${key}_country","").orEmpty().takeIf { it in listOf("","RU","KZ","US") }.orEmpty(),
            year=prefs.getInt("${key}_year",0).takeIf { it in 1888..2200 },
            genre=prefs.getInt("${key}_genre",0).takeIf { it>0 },
            genreName=prefs.getString("${key}_genre_name","").orEmpty().take(100),
            translatedOnly=prefs.getBoolean("${key}_translated_only",true))
    }
    fun rememberLibraryBrowse(value: CenterBrowse) {
        require(value.type in listOf("movie","tv") && value.query.isBlank())
        require(value.mode in listOf("popular","new") && value.country in listOf("","RU","KZ","US"))
        require(value.year==null||value.year in 1888..2200)
        require(value.genre==null||value.genre>0)
        val key="browse_${value.type}"
        prefs.edit().putString("${key}_mode",value.mode).putString("${key}_country",value.country)
            .putInt("${key}_year",value.year ?: 0).putInt("${key}_genre",value.genre ?: 0)
            .putString("${key}_genre_name",value.genreName.take(100))
            .putBoolean("${key}_translated_only",value.translatedOnly).apply()
    }
    fun catalogHost(provider: String) = prefs.getString("host_$provider", CenterCatalog.defaultHost(provider)).orEmpty()
    fun setCatalogHost(provider: String, host: String) { prefs.edit().putString("host_$provider", host.trim().trimEnd('/')).apply() }
    fun preferredQuality(provider: String?): Int? =
        prefs.getInt("quality_${provider.orEmpty()}", -1).takeIf { it >= 0 }
    fun setPreferredQuality(provider: String?, quality: Int?) {
        val key = "quality_${provider.orEmpty()}"
        val editor = prefs.edit()
        if (quality == null) editor.remove(key) else {
            require(quality >= 0)
            editor.putInt(key, quality)
        }
        editor.apply()
    }
    var exactReleases: Boolean
        get() = prefs.getBoolean("exact_releases", false)
        set(value) { prefs.edit().putBoolean("exact_releases", value).apply() }
    var releaseSort: String
        get() = prefs.getString("release_sort", "seeds").orEmpty().takeIf { it in listOf("seeds", "small", "large") } ?: "seeds"
        set(value) { require(value in listOf("seeds", "small", "large")); prefs.edit().putString("release_sort", value).apply() }
    var seededOnly: Boolean
        get() = prefs.getBoolean("seeded_only", false)
        set(value) { prefs.edit().putBoolean("seeded_only", value).apply() }
    var cardSize: String
        get() = prefs.getString("card_size", "normal").orEmpty().takeIf { it in listOf("compact", "normal", "large") } ?: "normal"
        set(value) { require(value in listOf("compact", "normal", "large")); prefs.edit().putString("card_size", value).apply() }
    fun voice(key: String): String? = prefs.getString("voice_$key", null)
    fun setVoice(key: String, voice: String?) {
        prefs.edit().apply { if (voice == null) remove("voice_$key") else putString("voice_$key", voice) }.apply()
    }
    fun onlineSource(item:CenterTitle,provider:String?=null):CenterTitle? {
        fun saved(p:String)=runCatching { gson.fromJson(prefs.getString("online_"+p+"_"+item.key,null),CenterTitle::class.java) }
            .getOrNull()?.takeIf { it.provider==p&&CenterOnlineMatch.valid(item,it) }
        if(provider!=null)return provider.takeIf { it in CenterOnlineMatch.providers }?.let(::saved)
        val bindings=CenterOnlineMatch.providers.mapNotNull(::saved)
        item.sourceUrl?.let { page -> bindings.firstOrNull { CenterOnline.sameTitleNavigation(it,page) }?.let { return it.copy(sourceUrl=page) } }
        val last=prefs.getString("online_last_"+item.key,null)
        return bindings.firstOrNull { it.provider==last } ?: bindings.firstOrNull { it.provider=="kinovod" }
    }
    fun setOnlineSource(item:CenterTitle,source:CenterTitle) {
        require(CenterOnlineMatch.valid(item,source))
        prefs.edit().putString("online_"+source.provider+"_"+item.key,gson.toJson(source))
            .putString("online_last_"+item.key,source.provider).apply()
    }
    fun searches(): List<String> = runCatching {
        gson.fromJson<List<String>>(prefs.getString("searches", "[]"), object : TypeToken<List<String>>() {}.type)
    }.getOrNull().orEmpty().filter { it.length in 2..120 }.take(20)
    fun rememberSearch(value: String) {
        val term=value.trim().replace(Regex("\\s+"), " ")
        if(term.length !in 2..120) return
        prefs.edit().putString("searches",gson.toJson((listOf(term)+searches().filterNot { it.equals(term,true) }).take(20))).apply()
    }
    fun removeSearch(value: String) { prefs.edit().putString("searches",gson.toJson(searches().filterNot { it.equals(value,true) })).apply() }
    fun clearSearches() { prefs.edit().remove("searches").apply() }
    fun followed(): List<CenterTitle> = runCatching {
        gson.fromJson<List<CenterTitle>>(prefs.getString("followed", "[]"), object : TypeToken<List<CenterTitle>>() {}.type)
    }.getOrNull().orEmpty()
    fun isFollowed(item: CenterTitle) = followed().any { it.key==item.key }
    fun toggleFollow(item: CenterTitle) {
        require(item.type=="tv")
        val next=followed().filterNot { it.key==item.key }.toMutableList()
        if(!isFollowed(item)) next.add(0,item)
        prefs.edit().putString("followed",gson.toJson(next.take(200))).apply()
    }
    fun mySeries(): List<CenterTitle> {
        val recent=history().filter { it.card.contentKey?.contains(":tv:")==true || it.card.seasonNumber!=null || it.card.episodes.isNotEmpty() }.mapNotNull {
            val card=it.card;val parts=card.contentKey?.split(':').orEmpty()
            val provider=parts.firstOrNull()?.takeIf { p -> CenterCatalog.isSiteIdentity(p) }
            val id=if(provider!=null) parts.getOrNull(2)?.toIntOrNull() else card.tmdbId
            id?.let { value -> CenterTitle(value,"tv",card.title,poster=card.posterUrl,provider=provider,sourceUrl=card.sourcePage) }
        }
        return (followed()+recent).distinctBy { it.key }
    }
    fun pinned(): List<CenterTitle> = runCatching {
        gson.fromJson<List<CenterTitle>>(prefs.getString("pinned", "[]"), object : TypeToken<List<CenterTitle>>() {}.type)
    }.getOrNull().orEmpty()
    fun isPinned(item: CenterTitle) = pinned().any { it.key == item.key }
    fun pin(item: CenterTitle) {
        val next = pinned().filterNot { it.key == item.key }.toMutableList()
        if (!isPinned(item)) next.add(0, item)
        prefs.edit().putString("pinned", gson.toJson(next.take(30))).apply()
    }
    fun favorites(): List<CenterTitle> = runCatching {
        gson.fromJson<List<CenterTitle>>(prefs.getString("favorites", "[]"), object : TypeToken<List<CenterTitle>>() {}.type)
    }.getOrNull().orEmpty()
    fun isFavorite(item: CenterTitle) = favorites().any { it.key == item.key }
    fun toggle(item: CenterTitle) {
        val items = favorites().filterNot { it.key == item.key }.toMutableList()
        if (!isFavorite(item)) items.add(0, item)
        prefs.edit().putString("favorites", gson.toJson(items.take(200))).apply()
    }
    fun history(): List<CenterHistory> = runCatching {
        gson.fromJson<List<CenterHistory>>(prefs.getString("history", "[]"), object : TypeToken<List<CenterHistory>>() {}.type)
    }.getOrNull().orEmpty()
    private fun historyKey(card: CardMeta) = card.contentKey?.removePrefix("tmdb:") ?: "${if (card.seasonNumber != null || card.episodes.isNotEmpty()) "tv" else "movie"}:${card.tmdbId ?: card.title}"
    fun removeHistory(card: CardMeta) {
        prefs.edit().putString("history", gson.toJson(history().filterNot { historyKey(it.card) == historyKey(card) })).apply()
    }
    fun clearHistory() { prefs.edit().remove("history").apply() }
    fun remember(url: String, card: CardMeta) {
        val list = history().filterNot { historyKey(it.card) == historyKey(card) }.toMutableList()
        list.add(0, CenterHistory(url, card, System.currentTimeMillis()))
        prefs.edit().putString("history", gson.toJson(list.take(30))).apply()
    }
    companion object {
        fun rebase(url: String, base: String): String {
            val original = url.toHttpUrlOrNull() ?: return url
            val target = base.toHttpUrlOrNull() ?: return url
            return original.newBuilder().scheme(target.scheme).host(target.host).port(target.port).build().toString()
        }
    }
}
