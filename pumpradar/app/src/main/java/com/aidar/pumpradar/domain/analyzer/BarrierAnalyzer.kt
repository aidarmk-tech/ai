package com.aidar.pumpradar.domain.analyzer

/**
 * Порядок достижения барьеров «цель/стоп» по временной последовательности.
 *
 * Работает на ряду (времяMs, доходность% от опорной цены) — от секундной
 * траектории (если сохранена) или от 5 контрольных точек. Для каждой пары
 * цель/стоп возвращает время первого касания каждого барьера и классификацию
 * порядка. Это и есть разметка для supervised learning вместо вывода по одним
 * MFE/MAE.
 */
object BarrierAnalyzer {

    enum class FirstBarrier { TARGET_FIRST, STOP_FIRST, BOTH_SAME_INTERVAL, NEITHER, DATA_INCOMPLETE }

    data class Barrier(
        val targetTimeMs: Long?,
        val stopTimeMs: Long?,
        val first: FirstBarrier
    )

    /**
     * @param samples (offsetMs, доходность% от опорной цены), long-семантика ряда.
     * @param side LONG: цель = рост +target, стоп = падение −stop.
     *             SHORT: цель = падение −target, стоп = рост +stop.
     */
    fun analyze(
        samples: List<Pair<Long, Double>>,
        side: TradeSide,
        targetPercent: Double,
        stopPercent: Double
    ): Barrier {
        if (samples.isEmpty()) return Barrier(null, null, FirstBarrier.DATA_INCOMPLETE)
        val ordered = samples.sortedBy { it.first }
        var targetTime: Long? = null
        var stopTime: Long? = null
        for ((t, r) in ordered) {
            val targetHit = when (side) {
                TradeSide.LONG -> r >= targetPercent
                TradeSide.SHORT -> r <= -targetPercent
            }
            val stopHit = when (side) {
                TradeSide.LONG -> r <= -stopPercent
                TradeSide.SHORT -> r >= stopPercent
            }
            if (targetTime == null && targetHit) targetTime = t
            if (stopTime == null && stopHit) stopTime = t
            if (targetTime != null && stopTime != null) break
        }
        val first = when {
            targetTime == null && stopTime == null -> FirstBarrier.NEITHER
            targetTime != null && stopTime == null -> FirstBarrier.TARGET_FIRST
            targetTime == null && stopTime != null -> FirstBarrier.STOP_FIRST
            targetTime!! < stopTime!! -> FirstBarrier.TARGET_FIRST
            stopTime < targetTime -> FirstBarrier.STOP_FIRST
            else -> FirstBarrier.BOTH_SAME_INTERVAL   // пересечены в одном интервале
        }
        return Barrier(targetTime, stopTime, first)
    }
}
