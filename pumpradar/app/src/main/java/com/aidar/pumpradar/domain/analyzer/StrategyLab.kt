package com.aidar.pumpradar.domain.analyzer

/**
 * Strategy Lab (патч §13): несколько стратегий получают одни и те же сигналы и
 * решают, сработать ли. Champion управляет уведомлениями (S1_CURRENT), остальные
 * — теневые (challenger). Здесь заданы предикаты срабатывания как чистые функции
 * от полей сигнала — точность считается по истории без изменения движка.
 *
 * Автоматический выбор победителя запрещён на малой выборке (патч §13/§23).
 */
object StrategyLab {

    /** Признаки сигнала, доступные из истории. */
    data class Features(
        val opportunityLabel: String?,
        val liquidityTier: String?,
        val entryRisk: Int?,
        val confidence: Int?,
        val impulse: Int
    )

    data class Strategy(val id: String, val title: String, val triggers: (Features) -> Boolean)

    private val notifyLabels = setOf("EARLY_CLEAN", "RETEST_CONFIRMED")

    val STRATEGIES: List<Strategy> = listOf(
        Strategy("S1_CURRENT", "Текущая (champion)") {
            it.opportunityLabel in notifyLabels
        },
        Strategy("S2_EARLY_CLEAN", "Только EARLY_CLEAN") {
            it.opportunityLabel == "EARLY_CLEAN"
        },
        Strategy("S3_RETEST_ONLY", "Только RETEST") {
            it.opportunityLabel == "RETEST_CONFIRMED"
        },
        Strategy("S4_HIGH_LIQUIDITY", "Только A/B") {
            it.opportunityLabel in notifyLabels && (it.liquidityTier == "A" || it.liquidityTier == "B")
        },
        Strategy("S5_FLOW_FIRST", "Поток+качество") {
            (it.confidence ?: 0) >= 75 && (it.entryRisk ?: 100) <= 40
        },
        Strategy("S6_LOW_ENTRY_RISK", "Низкий риск входа") {
            (it.entryRisk ?: 100) <= 25 && (it.confidence ?: 0) >= 70
        }
    )

    const val CHAMPION_ID = "S1_CURRENT"
}
