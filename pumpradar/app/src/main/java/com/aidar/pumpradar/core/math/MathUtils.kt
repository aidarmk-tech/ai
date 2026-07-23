package com.aidar.pumpradar.core.math

import kotlin.math.abs

/** Числовая безопасность и статистика по ТЗ (разделы 8, 10, 28). */
object MathUtils {

    const val EPSILON = 1e-9

    fun Double.validMarketNumber(): Boolean = isFinite() && this >= 0.0

    fun safeDivide(numerator: Double, denominator: Double): Double? {
        if (!numerator.isFinite() || !denominator.isFinite()) return null
        if (abs(denominator) < EPSILON) return null
        val r = numerator / denominator
        return if (r.isFinite()) r else null
    }

    /** Доходность окна в процентах: (now/past - 1) * 100. */
    fun returnPercent(priceNow: Double, pricePast: Double): Double? {
        val ratio = safeDivide(priceNow, pricePast) ?: return null
        return (ratio - 1.0) * 100.0
    }

    /**
     * Линейная нормализация в баллы: value<=zeroAt → 0, value>=fullAt → maxPoints,
     * между — линейная интерполяция. Поддерживает и убывающие шкалы (fullAt<zeroAt).
     */
    fun linearScore(value: Double, zeroAt: Double, fullAt: Double, maxPoints: Double): Double {
        if (!value.isFinite()) return 0.0
        if (fullAt == zeroAt) return if (value >= fullAt) maxPoints else 0.0
        val t = (value - zeroAt) / (fullAt - zeroAt)
        return t.coerceIn(0.0, 1.0) * maxPoints
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
    }

    /** Median Absolute Deviation. */
    fun mad(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val m = median(values)
        return median(values.map { abs(it - m) })
    }

    /**
     * Робастный Z-score: 0.6745 * (x - median) / MAD.
     * При MAD==0 возвращает 0 (безопасный fallback, не подмена данных).
     */
    fun robustZScore(current: Double, history: List<Double>): Double {
        if (history.size < 2) return 0.0
        val m = median(history)
        val madv = mad(history)
        if (madv < EPSILON) return 0.0
        return 0.6745 * (current - m) / madv
    }

    fun clampScore(total: Double): Int = total.coerceIn(0.0, 100.0).toInt()
}
