package com.aidar.pumpradar.domain.analyzer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Признаки вершины (item 6): локальный максимум, удалённость/откат от него,
 * неудачные попытки обновить максимум, lower high, удержание breakout-уровня, а
 * также «новый максимум цены без нового максимума CVD» (для item 5). Держит
 * короткий ряд (время, цена, CVD) на символ и пересчитывает признаки на тике.
 */
@Singleton
class PeakTracker @Inject constructor() {

    data class Result(
        val features: LongSignalDecider.PeakFeatures,
        val newHighWithoutCvdHigh: Boolean
    )

    private class Sample(val t: Long, val price: Double, val cvd: Double)
    private class Row {
        val samples = ArrayDeque<Sample>()
        var prevPriceHigh = 0.0
        var prevCvdHigh = 0.0
    }

    private val rows = HashMap<String, Row>()
    private val lock = Any()

    fun retain(symbols: Set<String>) = synchronized(lock) { rows.keys.retainAll(symbols) }
    fun clear() = synchronized(lock) { rows.clear() }

    fun update(symbol: String, price: Double, cvd: Double, now: Long): Result = synchronized(lock) {
        val row = rows.getOrPut(symbol) { Row() }
        if (price > 0.0) {
            row.samples.addLast(Sample(now, price, cvd))
            val cutoff = now - WINDOW_MS
            while (row.samples.isNotEmpty() && row.samples.first().t < cutoff) row.samples.removeFirst()
        }
        val s = row.samples
        if (s.size < 2) return Result(LongSignalDecider.PeakFeatures(), false)

        val maxPrice = s.maxOf { it.price }
        val highSample = s.last { it.price >= maxPrice - EPS_PRICE * maxPrice }
        val maxCvd = s.maxOf { it.cvd }

        val distancePct = if (maxPrice > 0.0) (maxPrice - price) / maxPrice * 100.0 else 0.0
        val secondsSinceHigh = ((now - highSample.t) / 1000).coerceAtLeast(0)

        // Откат от максимума: минимум цены ПОСЛЕ момента максимума.
        val postHigh = s.filter { it.t >= highSample.t }
        val minAfterHigh = postHigh.minOfOrNull { it.price } ?: price
        val pullbackPct = if (maxPrice > 0.0) (maxPrice - minAfterHigh) / maxPrice * 100.0 else 0.0

        // Отскок после отката: максимум цены после точки minAfterHigh.
        val minIdxTime = postHigh.firstOrNull { it.price <= minAfterHigh + EPS_PRICE * maxPrice }?.t ?: highSample.t
        val bounce = s.filter { it.t >= minIdxTime }
        val bounceHigh = bounce.maxOfOrNull { it.price } ?: price
        val lowerHigh = pullbackPct >= MIN_PULLBACK_PCT &&
            bounceHigh < maxPrice * (1.0 - EPS_PRICE) &&
            price < bounceHigh

        // breakout-уровень: цена примерно за BREAKOUT_LOOKBACK_MS до максимума.
        val breakoutLevel = s.filter { it.t <= highSample.t - BREAKOUT_LOOKBACK_MS }
            .maxByOrNull { it.t }?.price ?: s.first().price
        val breakoutHeld = price >= breakoutLevel

        // Неудачные попытки обновить максимум: касания зоны у максимума после отката, не пробившие его.
        val failedAttempts = countFailedHighAttempts(postHigh, maxPrice)

        // Новый максимум цены без нового максимума CVD (дивергенция, item 5).
        val newHighNoCvd = price >= maxPrice - EPS_PRICE * maxPrice &&
            maxCvd > 0.0 && cvd < maxCvd * (1.0 - EPS_CVD)
        row.prevPriceHigh = maxPrice
        row.prevCvdHigh = maxCvd

        Result(
            LongSignalDecider.PeakFeatures(
                distanceFromLocalHighPct = distancePct.coerceAtLeast(0.0),
                secondsSinceLocalHigh = secondsSinceHigh,
                pullbackFromHighPct = pullbackPct.coerceAtLeast(0.0),
                failedHighAttempts = failedAttempts,
                lowerHighDetected = lowerHigh,
                breakoutLevelHeld = breakoutHeld
            ),
            newHighWithoutCvdHigh = newHighNoCvd
        )
    }

    /** Считает «подходы к максимуму без пробоя»: пересечения зоны NEAR под максимумом. */
    private fun countFailedHighAttempts(postHigh: List<Sample>, maxPrice: Double): Int {
        val band = maxPrice * (1.0 - NEAR_HIGH_PCT / 100.0)
        var attempts = 0
        var inZone = false
        for (s in postHigh) {
            if (!inZone && s.price >= band && s.price < maxPrice * (1.0 - EPS_PRICE)) {
                inZone = true
            } else if (inZone && s.price < band * (1.0 - RETRACE_PCT / 100.0)) {
                attempts++          // подошёл к максимуму, но откатился, не пробив
                inZone = false
            }
        }
        return attempts
    }

    private companion object {
        const val WINDOW_MS = 300_000L
        const val BREAKOUT_LOOKBACK_MS = 60_000L
        const val EPS_PRICE = 0.0005
        const val EPS_CVD = 0.02
        const val MIN_PULLBACK_PCT = 0.3
        const val NEAR_HIGH_PCT = 0.25
        const val RETRACE_PCT = 0.25
    }
}
