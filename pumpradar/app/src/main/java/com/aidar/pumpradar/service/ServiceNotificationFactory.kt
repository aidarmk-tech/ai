package com.aidar.pumpradar.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aidar.pumpradar.MainActivity
import com.aidar.pumpradar.domain.model.MonitoringStats
import com.aidar.pumpradar.notification.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Постоянное уведомление foreground-сервиса (ТЗ раздел 5.1). */
class ServiceNotificationFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun build(stats: MonitoringStats, paused: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = servicePending(MonitoringService.ACTION_TOGGLE_PAUSE, 1)
        val stopIntent = servicePending(MonitoringService.ACTION_STOP, 2)

        val last = if (stats.lastMessageAt > 0) fmt.format(Date(stats.lastMessageAt)) else "—"
        val text = "Пар: ${stats.usdtSymbols} · Кандидатов: ${stats.candidates} · Обновление: $last"

        return NotificationCompat.Builder(context, NotificationChannels.SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (paused) "PumpRadar на паузе" else "PumpRadar анализирует рынок")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Открыть", openIntent)
            .addAction(0, if (paused) "Продолжить" else "Пауза", pauseIntent)
            .addAction(0, "Остановить", stopIntent)
            .build()
    }

    private fun servicePending(action: String, code: Int): PendingIntent {
        val i = Intent(context, MonitoringService::class.java).setAction(action)
        return PendingIntent.getService(
            context, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
