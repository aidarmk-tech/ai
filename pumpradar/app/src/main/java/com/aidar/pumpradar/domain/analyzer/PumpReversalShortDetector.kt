package com.aidar.pumpradar.domain.analyzer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * PUMP_REVERSAL_SHORT (теневая стратегия). Разрешает шорт ТОЛЬКО после
 * ПОДТВЕРЖДЁННОГО разворота пампа, а не при первом же откате. Все шесть условий
 * должны совпасть на пробое:
 *   1) lower high — отскок после отката не обновил вершину пампа;
 *   2) пробой локального минимума — цена ушла ниже минимума отката;
 *   3) отрицательный CVD;
 *   4) доминирование taker-продаж (takerBuyRatio ниже порога);
 *   5) отрицательный OBI (стакан ask-тяжёлый);
 *   6) исчезновение bid depth (bidNotionalTop10 просел относительно пика пампа).
 *
 * Состояние на символ: IDLE → PUMP → REVERSAL_WATCH → (подтверждение) → COOLDOWN.
 * Возвращает true один раз в момент подтверждения. НИЧЕГО не торгует.
 */
@Singleton
class PumpReversalShortDetector @Inject constructor() {

    enum class State { IDLE, PUMP, REVERSAL_WATCH, COOLDOWN }

    private class Row {
        var state = State.IDLE
        var pumpHigh = 0.0
        var bidDepthHigh = 0.0
        var swingLow = 0.0        // минимум первичного отката (уровень для пробоя)
        var bounceHigh = 0.0      // максимум отскока (для проверки lower high)
        var lowerHighSeen = false
        var watchStart = 0L
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
                if (i.isUpImpulse && i.price > 0.0) {
                    row.state = State.PUMP
                    row.pumpHigh = i.price
                    row.bidDepthHigh = i.bidNotionalTop10 ?: 0.0
                    row.lowerHighSeen = false
                }
                false
            }
            State.PUMP -> {
                if (i.price > row.pumpHigh) row.pumpHigh = i.price
                val bid = i.bidNotionalTop10 ?: 0.0
                if (bid > row.bidDepthHigh) row.bidDepthHigh = bid
                val drop = pctDown(row.pumpHigh, i.price)   // насколько ниже вершины пампа, %
                if (drop >= MIN_PULLBACK) {
                    row.state = State.REVERSAL_WATCH
                    row.swingLow = i.price
                    row.bounceHigh = i.price
                    row.lowerHighSeen = false
                    row.watchStart = i.now
                }
                false
            }
            State.REVERSAL_WATCH -> {
                if (i.now - row.watchStart > WATCH_MS) { row.state = State.IDLE; return@synchronized false }
                // Новый максимум выше вершины пампа → разворота нет, памп продолжается.
                if (i.price >= row.pumpHigh) {
                    row.state = State.PUMP
                    row.pumpHigh = i.price
                    return@synchronized false
                }
                if (!row.lowerHighSeen) {
                    // Фаза A: ищем минимум отката и первый отскок (lower high).
                    if (i.price < row.swingLow) row.swingLow = i.price
                    val bounce = pctUp(row.swingLow, i.price)
                    if (bounce >= MIN_BOUNCE && i.price < row.pumpHigh) {
                        row.lowerHighSeen = true
                        row.bounceHigh = i.price
                    }
                    false
                } else {
                    // Фаза B: lower high есть — ждём пробой swingLow при подтверждении потока.
                    if (i.price > row.bounceHigh) row.bounceHigh = i.price
                    if (row.bounceHigh >= row.pumpHigh) { row.state = State.PUMP; row.pumpHigh = row.bounceHigh; return@synchronized false }
                    val bidGone = row.bidDepthHigh > 0.0 &&
                        (i.bidNotionalTop10 ?: row.bidDepthHigh) <= BID_DEPTH_FRAC * row.bidDepthHigh
                    // takerBuyRatio15s < 0.45 (короткое окно, откат к 30с если 15с нет).
                    val tbrShort = i.takerBuyRatio15s ?: i.takerBuyRatio ?: 1.0
                    val confirmed = i.price < row.swingLow &&                 // пробой локального минимума
                        i.cvd < 0.0 &&                                        // отрицательный CVD
                        tbrShort < TAKER_SELL_DOM &&                          // доминируют taker-продажи (15с)
                        (i.obi10 ?: 0.0) <= OBI_NEG &&                        // отрицательный OBI
                        bidGone &&                                            // исчезает bid depth
                        (i.spreadBps ?: Double.MAX_VALUE) <= MAX_SPREAD_BPS &&
                        (i.slippagePercent ?: 0.0) <= MAX_SLIPPAGE
                    if (confirmed) {
                        row.state = State.COOLDOWN
                        row.cooldownUntil = i.now + COOLDOWN_MS
                        return@synchronized true
                    }
                    false
                }
            }
        }
    }

    private fun pctDown(from: Double, to: Double): Double =
        if (from > 0.0) (from - to) / from * 100.0 else 0.0

    private fun pctUp(from: Double, to: Double): Double =
        if (from > 0.0) (to - from) / from * 100.0 else 0.0

    private companion object {
        const val MIN_PULLBACK = 0.5      // % падения от вершины пампа для старта наблюдения
        const val MIN_BOUNCE = 0.3        // % отскока от swingLow для фиксации lower high
        const val WATCH_MS = 180_000L
        const val TAKER_SELL_DOM = 0.45
        const val OBI_NEG = -0.05
        const val BID_DEPTH_FRAC = 0.6
        const val MAX_SPREAD_BPS = 60.0
        const val MAX_SLIPPAGE = 0.5
        const val COOLDOWN_MS = 5 * 60_000L
    }
}
