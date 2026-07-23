package com.aidar.pumpradar.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pumpradar_settings")

enum class MonitoringMode { APP_OPEN, BACKGROUND }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Профиль риска по тирам ликвидности (ТЗ v2, раздел 0A.9). */
enum class MonitoringProfile { CAUTIOUS, BALANCED, EXPLORE }

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val monitoringMode: MonitoringMode = MonitoringMode.APP_OPEN,
    val monitoringProfile: MonitoringProfile = MonitoringProfile.BALANCED,
    val minimum24hQuoteVolume: Double = 1_000_000.0,
    val minimumNotificationLevel: String = "STRONG",
    val slippageTestAmountUsdt: Double = 10.0,
    val symbolCooldownMinutes: Int = 15,
    val maxNotificationsPerHour: Int = 12,
    val showRiskDisclaimer: Boolean = true
)

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>
) {
    private object Keys {
        val onboarding = booleanPreferencesKey("onboardingComplete")
        val theme = stringPreferencesKey("theme")
        val mode = stringPreferencesKey("monitoringMode")
        val profile = stringPreferencesKey("monitoringProfile")
        val minVol = doublePreferencesKey("minimum24hQuoteVolume")
        val minNotif = stringPreferencesKey("minimumNotificationLevel")
        val slipAmt = doublePreferencesKey("slippageTestAmountUsdt")
        val cooldown = intPreferencesKey("symbolCooldownMinutes")
        val maxNotif = intPreferencesKey("maxNotificationsPerHour")
        val disclaimer = booleanPreferencesKey("showRiskDisclaimer")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            onboardingComplete = p[Keys.onboarding] ?: false,
            theme = runCatching { ThemeMode.valueOf(p[Keys.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            monitoringMode = runCatching { MonitoringMode.valueOf(p[Keys.mode] ?: "APP_OPEN") }.getOrDefault(MonitoringMode.APP_OPEN),
            monitoringProfile = runCatching { MonitoringProfile.valueOf(p[Keys.profile] ?: "BALANCED") }.getOrDefault(MonitoringProfile.BALANCED),
            minimum24hQuoteVolume = p[Keys.minVol] ?: 1_000_000.0,
            minimumNotificationLevel = p[Keys.minNotif] ?: "STRONG",
            slippageTestAmountUsdt = p[Keys.slipAmt] ?: 10.0,
            symbolCooldownMinutes = p[Keys.cooldown] ?: 15,
            maxNotificationsPerHour = p[Keys.maxNotif] ?: 12,
            showRiskDisclaimer = p[Keys.disclaimer] ?: true
        )
    }

    suspend fun setOnboardingComplete(value: Boolean) = store.edit { it[Keys.onboarding] = value }
    suspend fun setTheme(value: ThemeMode) = store.edit { it[Keys.theme] = value.name }
    suspend fun setMonitoringMode(value: MonitoringMode) = store.edit { it[Keys.mode] = value.name }
    suspend fun setMonitoringProfile(value: MonitoringProfile) = store.edit { it[Keys.profile] = value.name }
    suspend fun setMinimum24hQuoteVolume(value: Double) = store.edit { it[Keys.minVol] = value }
    suspend fun setMinimumNotificationLevel(value: String) = store.edit { it[Keys.minNotif] = value }
    suspend fun setShowRiskDisclaimer(value: Boolean) = store.edit { it[Keys.disclaimer] = value }
}
