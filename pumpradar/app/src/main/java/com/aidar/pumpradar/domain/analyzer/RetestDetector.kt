package com.aidar.pumpradar.domain.analyzer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * RetestDetector (патч §5): вход не в вершину, а после контролируемого отката.
 *
 * Состояние на символ: IDLE → IMPULSE (зафиксирован первый импульс) → PULLBACK
 * (допустимый откат) → подтверждение возобновления → COOLDOWN. Использует
 * доступные 30с-метрики потока; более тонкие 5с/15с окна могут ужесточить
 * подтверждение позже. Возвращает true один раз в момент подтверждения ретеста.
 */
@Singleton
class RetestDetector @Inject constructor() {

    enum class State { IDLE, IMPULSE, PULLBACK, COOLDOWN }

    data class Input(
        val now: Long,
        val price: Double,
        val cvd: Double,
        val cvdSlope: Double,
        val takerBuyRatio: Double?,
        val spreadBps: Double?,
        val slippagePercent: Double?,
        val confidence: Int,
        val entryRisk: Int,
        val isImpulse: Boolean
    )

    private class Row {
        var state = State.IDLE
        var impulseStartPrice = 0.0
        var impulseHighPrice = 0.0
        var localPullbackHigh = 0.0     // = вершина импульса на момент начала отката
        var pullbackStartTime = 0L
        var impulseCvdHigh = 0.0
        var cooldownUntil = 0L
    }

    private val rows = HashMap<String, Row>()
    private val lock = Any()

    fun retain(symbols: Set<String>) = synchronized(lock) { rows.keys.retainAll(symbols) }
    fun clear() = synchronized(lock) { rows.clear() }

    fun update(symbol: String, i: Input): Boolean = synchronized(lock) {
        val row = rows.getOrPut(symbol) { Row() }
        when (row.state) {
            State.COOLDOWN -> {
                if (i.now >= row.cooldownUntil) row.state = State.IDLE
                false
            }
            State.IDLE -> {
                if (i.isImpulse && i.price > 0.0) {
                    row.state = State.IMPULSE
                    row.impulseStartPrice = i.price
                    row.impulseHighPrice = i.price
                    row.impulseCvdHigh = i.cvd
                }
                false
            }
            State.IMPULSE -> {
                if (i.price > row.impulseHighPrice) row.impulseHighPrice = i.price
                if (i.cvd > row.impulseCvdHigh) row.impulseCvdHigh = i.cvd
                val drawdown = pct(row.impulseHighPrice, i.price)   // просадка от вершины, %
                if (drawdown >= MIN_PULLBACK && drawdown <= MAX_PULLBACK && i.price > row.impulseStartPrice) {
                    row.state = State.PULLBACK
                    row.localPullbackHigh = row.impulseHighPrice
                    row.pullbackStartTime = i.now
                } else if (drawdown > MAX_PULLBACK || i.price <= row.impulseStartPrice) {
                    row.state = State.IDLE                          // откат слишком глубокий/сорвался
                }
                false
            }
            State.PULLBACK -> {
                val dur = i.now - row.pullbackStartTime
                val drawdown = pct(row.impulseHighPrice, i.price)
                // Срыв отката.
                if (drawdown > MAX_PULLBACK || i.price <= row.impulseStartPrice || dur > MAX_PULLBACK_MS) {
                    row.state = State.IDLE
                    return@synchronized false
                }
                // Подтверждение возобновления (патч §5.3, по 30с-метрикам).
                val confirmed = i.price > row.localPullbackHigh &&
                    i.cvdSlope > 0.0 &&
                    i.cvd > 0.0 &&
                    (i.takerBuyRatio ?: 0.0) >= 0.60 &&
                    i.cvd >= 0.50 * row.impulseCvdHigh &&
                    (i.spreadBps ?: Double.MAX_VALUE) <= 40.0 &&
                    (i.slippagePercent ?: Double.MAX_VALUE) <= 0.40 &&
                    i.confidence >= 75 &&
                    i.entryRisk <= 45 &&
                    dur >= MIN_PULLBACK_MS
                if (confirmed) {
                    row.state = State.COOLDOWN
                    row.cooldownUntil = i.now + COOLDOWN_MS
                    return@synchronized true
                }
                false
            }
        }
    }

    private fun pct(from: Double, to: Double): Double =
        if (from > 0.0) (from - to) / from * 100.0 else 0.0

    private companion object {
        const val MIN_PULLBACK = 0.8      // %
        const val MAX_PULLBACK = 3.5      // %
        const val MIN_PULLBACK_MS = 5_000L
        const val MAX_PULLBACK_MS = 180_000L
        const val COOLDOWN_MS = 5 * 60_000L
    }
}
