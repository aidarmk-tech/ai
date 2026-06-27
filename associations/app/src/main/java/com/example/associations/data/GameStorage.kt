package com.example.associations.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.associations.model.GameState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "associations")

/** Пользовательские настройки приложения. */
data class Settings(
    val soundEnabled: Boolean = true,
    val darkTheme: Boolean = false
)

/**
 * Локальное сохранение партии и настроек через DataStore.
 * Бэкенда нет — всё хранится на устройстве.
 */
class GameStorage(private val context: Context) {

    private object Keys {
        val SAVED_GAME = stringPreferencesKey("saved_game")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val DARK = booleanPreferencesKey("dark_theme")
        val LEVEL = intPreferencesKey("current_level")
    }

    val settingsFlow: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            soundEnabled = prefs[Keys.SOUND] ?: true,
            darkTheme = prefs[Keys.DARK] ?: false
        )
    }

    suspend fun saveGame(state: GameState) {
        val json = GameSerializer.toJson(state)
        context.dataStore.edit { it[Keys.SAVED_GAME] = json }
    }

    suspend fun loadGame(): GameState? {
        val prefs = context.dataStore.data.first()
        val json = prefs[Keys.SAVED_GAME] ?: return null
        return GameSerializer.fromJson(json)
    }

    suspend fun clearGame() {
        context.dataStore.edit { it.remove(Keys.SAVED_GAME) }
    }

    suspend fun hasSavedGame(): Boolean {
        val prefs = context.dataStore.data.first()
        val json = prefs[Keys.SAVED_GAME] ?: return false
        val state = GameSerializer.fromJson(json)
        return state != null && !state.isWon
    }

    suspend fun loadLevel(): Int {
        val prefs = context.dataStore.data.first()
        return (prefs[Keys.LEVEL] ?: 1).coerceAtLeast(1)
    }

    suspend fun saveLevel(level: Int) {
        context.dataStore.edit { it[Keys.LEVEL] = level.coerceAtLeast(1) }
    }

    suspend fun setSound(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND] = enabled }
    }

    suspend fun setDark(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK] = enabled }
    }
}
