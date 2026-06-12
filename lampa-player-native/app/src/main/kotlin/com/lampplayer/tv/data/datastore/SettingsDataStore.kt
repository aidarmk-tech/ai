package com.lampplayer.tv.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.lampplayer.tv.domain.model.BufferProfileType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "lampa_settings")

data class AppSettings(
    // Player engine: "auto" | "exoplayer" | "vlc" (see engine.EngineType)
    val engine: String = "auto",
    val buffer: BufferProfileType = BufferProfileType.MEDIUM,
    val autonext: Boolean = true,
    val autonextDelay: Int = 10,
    val rememberTracks: Boolean = true,
    val skipIntro: Boolean = true,
    val diag: Boolean = false,
    // Sleep timer in minutes; 0 = off.
    val sleepTimerMin: Int = 0,
    // OSD auto-hide after N seconds of inactivity.
    val osdTimeoutSec: Int = 10,
    // Remembered between sessions: volume boost % (100–200) and video scale mode name.
    val volumeBoost: Int = 100,
    val scaleMode: String = "AUTO",
    // AFR: switch the display refresh rate to match the content frame rate.
    val afr: Boolean = false,
)

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ENGINE = stringPreferencesKey("engine")
        val BUFFER = stringPreferencesKey("buffer")
        val AUTONEXT = booleanPreferencesKey("autonext")
        val AUTONEXT_DELAY = intPreferencesKey("autonext_delay")
        val REMEMBER_TRACKS = booleanPreferencesKey("remember_tracks")
        val SKIP_INTRO = booleanPreferencesKey("skip_intro")
        val DIAG = booleanPreferencesKey("diag")
        val SLEEP_TIMER = intPreferencesKey("sleep_timer_min")
        val OSD_TIMEOUT = intPreferencesKey("osd_timeout_sec")
        val VOLUME_BOOST = intPreferencesKey("volume_boost")
        val SCALE_MODE = stringPreferencesKey("scale_mode")
        val AFR = booleanPreferencesKey("afr")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                engine = com.lampplayer.tv.engine.EngineType.normalize(prefs[Keys.ENGINE]),
                buffer = prefs[Keys.BUFFER]?.let { runCatching { BufferProfileType.valueOf(it.uppercase()) }.getOrNull() }
                    ?: BufferProfileType.MEDIUM,
                autonext = prefs[Keys.AUTONEXT] ?: true,
                autonextDelay = prefs[Keys.AUTONEXT_DELAY] ?: 10,
                rememberTracks = prefs[Keys.REMEMBER_TRACKS] ?: true,
                skipIntro = prefs[Keys.SKIP_INTRO] ?: true,
                diag = prefs[Keys.DIAG] ?: false,
                sleepTimerMin = prefs[Keys.SLEEP_TIMER] ?: 0,
                osdTimeoutSec = prefs[Keys.OSD_TIMEOUT] ?: 10,
                volumeBoost = prefs[Keys.VOLUME_BOOST] ?: 100,
                scaleMode = prefs[Keys.SCALE_MODE] ?: "AUTO",
                afr = prefs[Keys.AFR] ?: false,
            )
        }

    suspend fun update(block: suspend MutablePreferences.() -> Unit) {
        context.settingsDataStore.edit(block)
    }

    suspend fun setEngine(engine: String) = update { this[Keys.ENGINE] = engine }
    suspend fun setBuffer(type: BufferProfileType) = update { this[Keys.BUFFER] = type.name.lowercase() }
    suspend fun setAutonext(enabled: Boolean) = update { this[Keys.AUTONEXT] = enabled }
    suspend fun setAutonextDelay(seconds: Int) = update { this[Keys.AUTONEXT_DELAY] = seconds }
    suspend fun setRememberTracks(enabled: Boolean) = update { this[Keys.REMEMBER_TRACKS] = enabled }
    suspend fun setSkipIntro(enabled: Boolean) = update { this[Keys.SKIP_INTRO] = enabled }
    suspend fun setDiag(enabled: Boolean) = update { this[Keys.DIAG] = enabled }
    suspend fun setSleepTimer(minutes: Int) = update { this[Keys.SLEEP_TIMER] = minutes }
    suspend fun setOsdTimeout(seconds: Int) = update { this[Keys.OSD_TIMEOUT] = seconds }
    suspend fun setVolumeBoost(percent: Int) = update { this[Keys.VOLUME_BOOST] = percent }
    suspend fun setScaleMode(mode: String) = update { this[Keys.SCALE_MODE] = mode }
    suspend fun setAfr(enabled: Boolean) = update { this[Keys.AFR] = enabled }
}
