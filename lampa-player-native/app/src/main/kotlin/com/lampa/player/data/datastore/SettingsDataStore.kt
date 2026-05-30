package com.lampa.player.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.lampa.player.domain.model.BufferProfileType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "lampa_settings")

data class AppSettings(
    val engine: String = "hls",
    val buffer: BufferProfileType = BufferProfileType.MEDIUM,
    val autonext: Boolean = true,
    val autonextDelay: Int = 10,
    val rememberTracks: Boolean = true,
    val skipIntro: Boolean = true,
    val diag: Boolean = false,
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
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                engine = prefs[Keys.ENGINE] ?: "hls",
                buffer = prefs[Keys.BUFFER]?.let { runCatching { BufferProfileType.valueOf(it.uppercase()) }.getOrNull() }
                    ?: BufferProfileType.MEDIUM,
                autonext = prefs[Keys.AUTONEXT] ?: true,
                autonextDelay = prefs[Keys.AUTONEXT_DELAY] ?: 10,
                rememberTracks = prefs[Keys.REMEMBER_TRACKS] ?: true,
                skipIntro = prefs[Keys.SKIP_INTRO] ?: true,
                diag = prefs[Keys.DIAG] ?: false,
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
}
