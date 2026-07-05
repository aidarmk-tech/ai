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

    fun showKey(card: CardMeta): String? {
        val id = card.tmdbId?.toString() ?: card.imdbId ?: card.title
        return id.let { "s${it.replace(Regex("\\W"), "")}" }
    }

    /** Per-episode key that survives balancer URL rotation (tokens change every launch). */
    fun episodeKey(card: CardMeta?): String? {
        if (card == null || card.iptv) return null
        val id = card.tmdbId?.toString() ?: card.imdbId ?: return null
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

    /** URL key first (exact stream), then the stable episode key (URL rotated). */
    suspend fun getPosition(url: String, card: CardMeta?): PlaybackPosition? {
        getPosition(url)?.let { return it }
        val k = episodeKey(card) ?: return null
        val json = context.positionDataStore.data
            .map { it[stringPreferencesKey(k)] }
            .firstOrNull() ?: return null
        return runCatching { gson.fromJson(json, PlaybackPosition::class.java) }.getOrNull()
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
}
