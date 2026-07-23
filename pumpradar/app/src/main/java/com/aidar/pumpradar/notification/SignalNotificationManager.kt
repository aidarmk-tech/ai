package com.aidar.pumpradar.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aidar.pumpradar.MainActivity
import com.aidar.pumpradar.domain.model.LiveSignal
import com.aidar.pumpradar.feature.coin.labelRu
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Уведомления о сигналах с антиспамом (ТЗ раздел 23.3): кулдаун на символ,
 * повторно только при переходе на более высокий уровень.
 */
@Singleton
class SignalNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class Last(val level: Int, val at: Long)
    private val lastBySymbol = HashMap<String, Last>()
    private var idSeq = 200

    @SuppressLint("MissingPermission")
    fun maybeNotify(signal: LiveSignal, cooldownMinutes: Int): Boolean {
        val now = System.currentTimeMillis()
        val levelOrd = levelOrdinal(signal.level)
        val last = lastBySymbol[signal.symbol]
        val cooldownMs = cooldownMinutes * 60_000L
        val allow = when {
            last == null -> true
            levelOrd > last.level -> true
            now - last.at >= cooldownMs -> true
            else -> false
        }
        if (!allow) return false
        lastBySymbol[signal.symbol] = Last(levelOrd, now)

        val emoji = when (signal.opportunityLabel) {
            "TOO_LATE", "STRONG_BUT_RISKY", "EXHAUSTION" -> "🔴"
            "CONFIRMED" -> "🟢"; "EARLY_CLEAN" -> "🟠"; else -> "🔵"
        }
        // Уведомление начинается с OpportunityLabel (ТЗ 0A.12), не с одного score.
        val title = "$emoji ${labelRu(signal.opportunityLabel)} — ${signal.symbol}"
        val body = buildString {
            append("Импульс %d · Риск %d · Достоверн. %d".format(
                signal.score, signal.entryRiskScore, signal.confidenceScore))
            signal.return60s?.let { append(" · 1м %+.1f%%".format(it)) }
        }.ifBlank { "Рыночная аномалия" }
        val expanded = buildString {
            if (signal.reasons.isNotEmpty()) {
                append("Причины:\n")
                signal.reasons.forEach { append("• $it\n") }
            }
            if (signal.risks.isNotEmpty()) {
                append("Риски:\n")
                signal.risks.forEach { append("• $it\n") }
            }
        }

        val pi = PendingIntent.getActivity(
            context, signal.symbol.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra("symbol", signal.symbol)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channel = if (signal.level == "EXTREME") NotificationChannels.RISK else NotificationChannels.SIGNALS
        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n\n$expanded"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(idSeq++ , notif)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun reset() = lastBySymbol.clear()

    private fun levelOrdinal(level: String): Int = when (level) {
        "WATCH" -> 1; "EARLY" -> 2; "STRONG" -> 3; "EXTREME" -> 4; else -> 0
    }
}
