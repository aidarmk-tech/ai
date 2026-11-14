package com.lampplayer.tv.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.domain.model.PlaybackPosition
import com.lampplayer.tv.domain.model.ShowData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private val Context.positionDataStore: DataStore<Preferences> by preferencesDataStore(name = "lampa_positions")

@Singleton
class PositionDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    fun keyOf(url: String): String {
        var h = 0
        for (char in url) {
            h = ((h shl 5) - h) + char.code
            h = h or 0
        }
        return "r${abs(h)}"
    }

    private fun stableIdentity(card:CardMeta):String? {
        val key=card.contentKey
        if (!key.isNullOrBlank()) return key
        return card.tmdbId?.let { "tmdb:${if(card.seasonNumber!=null || card.episodes.isNotEmpty()) "tv" else "movie"}:$it" } ?: card.imdbId
    }
    fun showKey(card: CardMeta): String? {
        val id = stableIdentity(card) ?: card.title
        return id.let { "s${it.replace(Regex("\\W"), "")}" }
    }

    /** Per-episode key that survives balancer URL rotation (tokens change every launch). */
    fun episodeKey(card: CardMeta?): String? {
        if (card == null || card.iptv) return null
        if (card.contentKey?.contains(":tv:") == true && (card.episodeNumber == null || card.seasonNumber == null)) return null
        val id = stableIdentity(card) ?: return null
        return "e${id}_${card.seasonNumber ?: 0}_${card.episodeNumber ?: 0}"
    }

    suspend fun savePosition(url: String, position: PlaybackPosition) = savePosition(url, null, position)

    /** Writes under the URL key and — when the card identifies the episode — a stable key too. */
    suspend fun savePosition(url: String, card: CardMeta?, position: PlaybackPosition) {
        val urlKey = stringPreferencesKey(keyOf(url))
        val epKey = episodeKey(card)?.let { stringPreferencesKey(it) }
        context.positionDataStore.edit { prefs ->
            val json = gson.toJson(position)
            prefs[urlKey] = json
            if (epKey != null) prefs[epKey] = json
        }
    }

    /** Compare one atomic snapshot: returning to an old URL must not rewind the episode. */
    suspend fun getPosition(url: String, card: CardMeta?): PlaybackPosition? {
        val prefs=context.positionDataStore.data.firstOrNull() ?: return null
        fun read(key:String?) = key?.let { prefs[stringPreferencesKey(it)] }?.let {
            runCatching { gson.fromJson(it,PlaybackPosition::class.java) }.getOrNull()
        }
        val episode=episodeKey(card)
        // Old numeric episode keys are unambiguous only with a complete TV episode tuple.
        val legacy=card?.takeIf { it.tmdbId!=null && it.seasonNumber!=null && it.episodeNumber!=null &&
            (it.contentKey==null || it.contentKey=="tmdb:tv:${it.tmdbId}") }
            ?.let { "e${it.tmdbId}_${it.seasonNumber}_${it.episodeNumber}" }
        return listOfNotNull(read(episode),read(keyOf(url)),read(legacy)).maxByOrNull { it.updated }
    }

    suspend fun getPosition(url: String): PlaybackPosition? {
        val key = stringPreferencesKey(keyOf(url))
        val json = context.positionDataStore.data
            .map { it[key] }
            .firstOrNull() ?: return null
        return runCatching { gson.fromJson(json, PlaybackPosition::class.java) }.getOrNull()
    }

    suspend fun saveShowData(card: CardMeta, showData: ShowData) {
        val keyStr = showKey(card) ?: return
        val key = stringPreferencesKey(keyStr)
        context.positionDataStore.edit { prefs ->
            val existing = prefs[key]?.let {
                runCatching { gson.fromJson(it, ShowData::class.java) }.getOrNull()
            } ?: ShowData()
            val merged = existing.copy(
                audio = showData.audio ?: existing.audio,
                subtitle = showData.subtitle ?: existing.subtitle,
                introEnd = showData.introEnd ?: existing.introEnd,
                creditsStart = showData.creditsStart ?: existing.creditsStart,
            )
            prefs[key] = gson.toJson(merged)
        }
    }

    suspend fun getShowData(card: CardMeta): ShowData? {
        val keyStr = showKey(card) ?: return null
        val key = stringPreferencesKey(keyStr)
        val json = context.positionDataStore.data
            .map { it[key] }
            .firstOrNull() ?: return null
        return runCatching { gson.fromJson(json, ShowData::class.java) }.getOrNull()
    }
    suspend fun backupEntries():Map<String,String> = context.positionDataStore.data.firstOrNull()?.asMap().orEmpty()
        .mapNotNull { (key,value) -> (value as? String)?.let { json ->
            key.name to if(key.name.startsWith("s"))json else runCatching {
                val position=gson.fromJson(json,PlaybackPosition::class.java)
                gson.toJson(position.copy(percent=position.percent.coerceIn(0,100)))
            }.getOrDefault(json)
        } }.toMap()

    suspend fun mergeBackup(entries:Map<String,String>):Int {
        validateBackup(entries)
        var imported=0
        context.positionDataStore.edit { prefs -> entries.forEach { (name,json) ->
            val key=stringPreferencesKey(name);val existing=prefs[key]
            val replace=existing==null || (!name.startsWith("s")&&runCatching {
                gson.fromJson(json,PlaybackPosition::class.java).updated > gson.fromJson(existing,PlaybackPosition::class.java).updated
            }.getOrDefault(true))
            if(replace){prefs[key]=json;imported++}
        } }
        return imported
    }
    fun validateBackup(entries:Map<String,String>) {
        require(entries.size<=50000){"Слишком много записей прогресса"}
        entries.forEach { (key,json) ->
            require(key.length in 2..500&&key.first() in "res"&&json.length<=8000){"Неверная запись прогресса"}
            require(com.google.gson.JsonParser.parseString(json).isJsonObject){"Неверный формат прогресса"}
            if(key.startsWith("s")) {
                val data=gson.fromJson(json,ShowData::class.java)
                require(listOfNotNull(data.introEnd,data.creditsStart).all { it.isFinite()&&it>=0 }){"Неверные метки интро"}
                require(listOfNotNull(data.audio,data.subtitle).all { it>=-1 }){"Неверные настройки дорожек"}
            } else {
                val data=gson.fromJson(json,PlaybackPosition::class.java)
                require(data.time.isFinite()&&data.duration.isFinite()&&data.time>=0&&data.duration>=0&&data.percent in 0..100&&data.updated>=0){"Неверное время просмотра"}
            }
        }
    }

}
