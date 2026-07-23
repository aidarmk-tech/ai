package com.aidar.pumpradar.domain.model

/** Уровни и стадии сигнала (ТЗ раздел 12). */
enum class SignalLevel(val min: Int, val max: Int) {
    NORMAL(0, 39),
    WATCH(40, 54),
    EARLY(55, 69),
    STRONG(70, 84),
    EXTREME(85, 100);

    companion object {
        fun fromScore(score: Int): SignalLevel =
            entries.firstOrNull { score in it.min..it.max } ?: NORMAL
    }
}

enum class SignalStage {
    NORMAL, WARMING, WATCH, EARLY, STRONG, EXTREME, RETEST, EXHAUSTION, CANCELLED, STALE
}

/** Тир ликвидности по 24ч объёму в USDT (ТЗ v2, раздел 0A.9). */
enum class LiquidityTier {
    A, B, C, D;

    companion object {
        fun of(quoteVolume24h: Double): LiquidityTier = when {
            quoteVolume24h >= 50_000_000.0 -> A
            quoteVolume24h >= 10_000_000.0 -> B
            quoteVolume24h >= 1_000_000.0 -> C
            else -> D
        }
    }
}

/** Состояние сервиса мониторинга (ТЗ раздел 32). */
sealed interface MonitoringState {
    data object Stopped : MonitoringState
    data object Starting : MonitoringState
    data class Running(val since: Long) : MonitoringState
    data class Degraded(val message: String) : MonitoringState
    data class Error(val message: String, val recoverable: Boolean) : MonitoringState
}

/** Живая сводка мониторинга для Dashboard/Diagnostics. */
data class MonitoringStats(
    val usdtSymbols: Int = 0,
    val candidates: Int = 0,
    val depthSymbols: Int = 0,
    val signalsToday: Int = 0,
    val strongToday: Int = 0,
    val marketWsConnected: Boolean = false,
    val candidateWsConnected: Boolean = false,
    val depthWsConnected: Boolean = false,
    val lastMessageAt: Long = 0L,
    val reconnects: Int = 0
)
