package com.aidar.pumpradar.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Каналы уведомлений (ТЗ раздел 23.1). */
@Singleton
class NotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun createAll() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SERVICE, "Мониторинг", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(SIGNALS, "Рыночные сигналы", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(RISK, "Риск-оповещения", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(DIAGNOSTICS, "Диагностика", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val SERVICE = "monitoring_service"
        const val SIGNALS = "market_signals"
        const val RISK = "risk_alerts"
        const val DIAGNOSTICS = "diagnostics"
    }
}
