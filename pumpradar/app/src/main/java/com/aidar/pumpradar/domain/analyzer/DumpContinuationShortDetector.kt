package com.aidar.pumpradar.domain.analyzer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * DUMP_CONTINUATION_SHORT (теневая стратегия). Зеркально LONG-анализу
 * (RetestDetector) обнаруживает САМОСТОЯТЕЛЬНЫЙ нисходящий импульс и подтверждает
 * его продолжение после контролируемого отскока — вход не в самый низ, а на
 * возобновлении падения.
 *
 * Состояние на символ: IDLE → DUMP (импульс вниз) → BOUNCE (допустимый отскок) →
 * (подтверждение возобновления) → COOLDOWN. Возвращает true один раз. Не торгует.
 */
@Singleton
class DumpContinuationShortDetector @Inject constructor() {

    enum class State { IDLE, DUMP, BOUNCE, COOLDOWN }

    private class Row {
        var state = State.IDLE
        var dumpStartPrice = 0.0
        var dumpLowPrice = 0.0
        var dumpCvdLow = 0.0        // минимум CVD за импульс (самый «продажный»)
        var localBounceLow = 0.0    // минимум перед отскоком (уровень для пробоя вниз)
        var bounceStartTime = 0L
        var cooldownUntil = 0L
    }

    private val rows = HashMap<String, Row>()
    private val lock = Any()

    fun retain(symbols: Set<String>) = synchronized(lock) { rows.keys.retainAll(symbols) }
    fun clear() = synchronized(lock) { rows.clear() }

    fun update(symbol: String, i: ShadowStrategyInput): Boolean = synchronized(lock) {
        val row = rows.getOrPut(symbol) { Row() }
        when (row.state) {
            State.COOLDOWN -> {
                if (i.now >= row.cooldownUntil) row.state = State.IDLE
                false
            }
            State.IDLE -> {
                if (i.isDownImpulse && i.price > 0.0) {
                    row.state = State.DUMP
                    row.dumpStartPrice = i.price
                    row.dumpLowPrice = i.price
                    row.dumpCvdLow = i.cvd
                }
                false
            }
            State.DUMP -> {
                if (i.price < row.dumpLowPrice) row.dumpLowPrice = i.price
                if (i.cvd < row.dumpCvdLow) row.dumpCvdLow = i.cvd
                val bounce = pctUp(row.dumpLowPrice, i.price)   // отскок от минимума, %
                if (bounce >= MIN_PULLBACK && bounce <= MAX_PULLBACK && i.price < row.dumpStartPrice) {
                    row.state = State.BOUNCE
                    row.localBounceLow = row.dumpLowPrice
                    row.bounceStartTime = i.now
                } else if (bounce > MAX_PULLBACK || i.price >= row.dumpStartPrice) {
                    row.state = State.IDLE                       // отскок слишком большой/сорвался
                }
                false
            }
            State.BOUNCE -> {
                val dur = i.now - row.bounceStartTime
                val bounce = pctUp(row.dumpLowPrice, i.price)
                if (bounce > MAX_PULLBACK || i.price >= row.dumpStartPrice || dur > MAX_BOUNCE_MS) {
                    row.state = State.IDLE
                    return@synchronized false
                }
                // Подтверждение возобновления падения (зеркально §5.3).
                val confirmed = i.price < row.localBounceLow &&
                    i.cvdSlope < 0.0 &&
                    i.cvd < 0.0 &&
                    (i.takerBuyRatio ?: 1.0) <= TAKER_SELL_DOM &&
                    i.cvd <= 0.50 * row.dumpCvdLow &&           // dumpCvdLow<0 → cvd ещё «продажнее»
                    (i.spreadBps ?: Double.MAX_VALUE) <= MAX_SPREAD_BPS &&
                    (i.slippagePercent ?: 0.0) <= MAX_SLIPPAGE &&
                    dur >= MIN_BOUNCE_MS
                if (confirmed) {
                    row.state = State.COOLDOWN
                    row.cooldownUntil = i.now + COOLDOWN_MS
                    return@synchronized true
                }
                false
            }
        }
    }

    private fun pctUp(from: Double, to: Double): Double =
        if (from > 0.0) (to - from) / from * 100.0 else 0.0

    private companion object {
        const val MIN_PULLBACK = 0.8      // % отскока вверх
        const val MAX_PULLBACK = 3.5      // %
        const val MIN_BOUNCE_MS = 5_000L
        const val MAX_BOUNCE_MS = 180_000L
        const val TAKER_SELL_DOM = 0.45
        const val MAX_SPREAD_BPS = 60.0
        const val MAX_SLIPPAGE = 0.5
        const val COOLDOWN_MS = 5 * 60_000L
    }
}
