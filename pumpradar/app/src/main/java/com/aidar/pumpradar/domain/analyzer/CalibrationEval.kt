package com.aidar.pumpradar.domain.analyzer

/**
 * Настраиваемый критерий исхода для калибровки: «цель +T% раньше стопа −S%
 * в пределах горизонта H» по сохранённым контрольным точкам цены.
 *
 * Оценка грубая (5 точек: 30с/1м/3м/5м/15м) — межточечные всплески не видны,
 * поэтому это консервативная нижняя граница. Но, в отличие от одной жёсткой
 * пары +2%/−1%, позволяет подобрать цель/стоп/горизонт под свою стратегию.
 */
object CalibrationEval {

    /** Стандартные времена контрольных точек в секундах. */
    val CHECKPOINTS = intArrayOf(30, 60, 180, 300, 900)

    /**
     * @param returnsByTime пары (времяСек, доходность%) — только доступные точки.
     * @return true, если цель достигнута на точке не позже первой точки стопа.
     */
    fun targetBeforeStop(
        returnsByTime: List<Pair<Int, Double>>,
        targetPercent: Double,
        stopPercent: Double,
        horizonSec: Int
    ): Boolean {
        val within = returnsByTime.filter { it.first <= horizonSec }.sortedBy { it.first }
        var firstTarget = Int.MAX_VALUE
        var firstStop = Int.MAX_VALUE
        for ((t, r) in within) {
            if (firstTarget == Int.MAX_VALUE && r >= targetPercent) firstTarget = t
            if (firstStop == Int.MAX_VALUE && r <= -stopPercent) firstStop = t
        }
        return firstTarget != Int.MAX_VALUE && firstTarget <= firstStop
    }

    /** Доходности контрольных точек в % относительно опорной цены. */
    fun checkpointReturns(
        referencePrice: Double?,
        p30s: Double?, p1m: Double?, p3m: Double?, p5m: Double?, p15m: Double?
    ): List<Pair<Int, Double>> {
        val ref = referencePrice ?: return emptyList()
        if (ref <= 0.0) return emptyList()
        fun r(p: Double?, t: Int): Pair<Int, Double>? =
            p?.takeIf { it > 0.0 }?.let { t to (it / ref - 1.0) * 100.0 }
        return listOfNotNull(
            r(p30s, 30), r(p1m, 60), r(p3m, 180), r(p5m, 300), r(p15m, 900)
        )
    }
}
