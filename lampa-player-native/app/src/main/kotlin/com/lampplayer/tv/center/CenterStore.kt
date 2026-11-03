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
    fun favorites(): List<CenterTitle> = runCatching {
        gson.fromJson<List<CenterTitle>>(prefs.getString("favorites", "[]"), object : TypeToken<List<CenterTitle>>() {}.type)
    }.getOrNull().orEmpty()
    fun isFavorite(item: CenterTitle) = favorites().any { it.id == item.id && it.type == item.type }
    fun toggle(item: CenterTitle) {
        val items = favorites().filterNot { it.id == item.id && it.type == item.type }.toMutableList()
        if (!isFavorite(item)) items.add(0, item)
        prefs.edit().putString("favorites", gson.toJson(items.take(200))).apply()
    }
    fun history(): List<CenterHistory> = runCatching {
        gson.fromJson<List<CenterHistory>>(prefs.getString("history", "[]"), object : TypeToken<List<CenterHistory>>() {}.type)
    }.getOrNull().orEmpty()
    fun remember(url: String, card: CardMeta) {
        fun key(value: CardMeta) = "${if (value.seasonNumber != null || value.episodes.isNotEmpty()) "tv" else "movie"}:${value.tmdbId ?: value.title}"
        val list = history().filterNot { key(it.card) == key(card) }.toMutableList()
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
