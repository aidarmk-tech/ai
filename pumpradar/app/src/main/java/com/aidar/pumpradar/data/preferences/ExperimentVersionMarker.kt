package com.aidar.pumpradar.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Фиксирует точный момент первого запуска мониторинга после установки новой
 * экспериментальной версии. Это позволяет экспортировать корректную версию
 * алгоритма без ручных временных cutoff и без переписывания старой истории.
 */
@Singleton
class ExperimentVersionMarker @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markStarted(now: Long = System.currentTimeMillis()) {
        if (prefs.getString(KEY_VERSION, null) == CURRENT_VERSION) return
        prefs.edit()
            .putString(KEY_VERSION, CURRENT_VERSION)
            .putLong(KEY_STARTED_AT, now)
            .apply()
    }

    fun version(): String = prefs.getString(KEY_VERSION, null) ?: ""

    fun startedAt(): Long = prefs.getLong(KEY_STARTED_AT, 0L)

    companion object {
        const val CURRENT_VERSION = "4.2.0"
        private const val PREFS_NAME = "pumpradar_experiment_marker"
        private const val KEY_VERSION = "version"
        private const val KEY_STARTED_AT = "started_at"
    }
}
