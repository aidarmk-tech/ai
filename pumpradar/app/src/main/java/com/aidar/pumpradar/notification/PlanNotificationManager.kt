package com.aidar.pumpradar.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aidar.pumpradar.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Уведомления о стадиях сопровождения уже выданного LONG-сигнала. */
@Singleton
class PlanNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class State {
        PROTECT_AFTER_1,
        TARGET_3_REACHED,
        EXIT_WEAKENING,
        PROTECTED_EXIT,
        INITIAL_STOP
    }

    @SuppressLint("MissingPermission")
    fun notify(symbol: String, state: State, returnPercent: Double, reasons: List<String> = emptyList()) {
        val title = when (state) {
            State.PROTECT_AFTER_1 -> "🛡 $symbol достиг +1%"
            State.TARGET_3_REACHED -> "🎯 $symbol достиг +3%"
            State.EXIT_WEAKENING -> "⚠ $symbol: поток ослабевает"
            State.PROTECTED_EXIT -> "🟡 $symbol вернулся к защитному уровню"
            State.INITIAL_STOP -> "🔴 $symbol достиг первоначальной защиты"
        }
        val action = when (state) {
            State.PROTECT_AFTER_1 -> "Защитить позицию; остаток сопровождать до +3%."
            State.TARGET_3_REACHED -> "Цель движения +3% достигнута."
            State.EXIT_WEAKENING -> "Не удерживать цель любой ценой: проверить выход вручную."
            State.PROTECTED_EXIT -> "После +1% движение вернулось к защите: проверить закрытие остатка."
            State.INITIAL_STOP -> "Движение не подтвердилось до активации защиты."
        }
        val body = "Текущее движение %+.2f%%. %s".format(returnPercent, action)
        val expanded = buildString {
            append(body)
            if (reasons.isNotEmpty()) {
                append("\n\nПричины:\n")
                reasons.forEach { append("• $it\n") }
            }
        }
        val pi = PendingIntent.getActivity(
            context,
            (symbol + state.name).hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra("symbol", symbol)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.SIGNALS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(("plan-$symbol-${state.name}").hashCode(), notification)
        } catch (_: SecurityException) {
            // Уведомления могут быть запрещены пользователем; мониторинг продолжает работу.
        }
    }
}
