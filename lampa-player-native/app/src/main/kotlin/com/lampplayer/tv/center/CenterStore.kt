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
        get() = prefs.getString("catalog", "tmdb").orEmpty().takeIf { it in listOf("tmdb", "kinovod", "filmix") } ?: "tmdb"
        set(value) { prefs.edit().putString("catalog", value).apply() }
    fun catalogHost(provider: String) = prefs.getString("host_$provider", if (provider == "filmix") "https://filmix.gg" else "https://kinovod.pro").orEmpty()
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
            val provider=parts.firstOrNull()?.takeIf { p -> p in listOf("kinovod","filmix") }
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
    private fun historyKey(card: CardMeta) = card.contentKey ?: "${if (card.seasonNumber != null || card.episodes.isNotEmpty()) "tv" else "movie"}:${card.tmdbId ?: card.title}"
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
