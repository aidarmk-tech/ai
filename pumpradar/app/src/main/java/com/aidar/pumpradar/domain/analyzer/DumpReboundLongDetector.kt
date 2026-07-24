package com.aidar.pumpradar.domain.analyzer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * DUMP_REBOUND_LONG (теневая стратегия). Ждёт ПРЕКРАЩЕНИЯ продавливания и
 * ПОДТВЕРЖДЁННОГО восстановления покупателей — вход не в падающий нож, а после
 * стабилизации у минимума и разворота потока в сторону покупок.
 *
 * Состояние на символ: IDLE → DUMP (импульс вниз) → BASING (продажи выдыхаются,
 * cvdSlope вверх) → (подтверждение восстановления) → COOLDOWN. Возвращает true
 * один раз. Не торгует.
 */
@Singleton
class DumpReboundLongDetector @Inject constructor() {

    enum class State { IDLE, DUMP, BASING, COOLDOWN }

    private class Row {
        var state = State.IDLE
        var dumpLow = 0.0
        var baseLow = 0.0
        var baseStart = 0L
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
                    row.dumpLow = i.price
                }
                false
            }
            State.DUMP -> {
                if (i.price < row.dumpLow) row.dumpLow = i.price
                // Прекращение продавливания: небольшая стабилизация над минимумом
                // при развороте наклона CVD вверх.
                val up = pctUp(row.dumpLow, i.price)
                if (up >= MIN_STABILIZE && i.cvdSlope > 0.0) {
                    row.state = State.BASING
                    row.baseLow = row.dumpLow
                    row.baseStart = i.now
                }
                false
            }
            State.BASING -> {
                val dur = i.now - row.baseStart
                if (i.price < row.baseLow) {              // новый минимум — продажи не закончились
                    row.state = State.DUMP
                    row.dumpLow = i.price
                    return@synchronized false
                }
                if (dur > MAX_BASE_MS) { row.state = State.IDLE; return@synchronized false }
                val recovery = pctUp(row.baseLow, i.price)
                val confirmed = recovery >= MIN_RECOVERY &&
                    i.cvdSlope > 0.0 &&
                    i.cvd > 0.0 &&                                       // покупатели перевешивают
                    (i.takerBuyRatio ?: 0.0) >= TAKER_BUY_DOM &&
                    (i.obi10 ?: 0.0) >= OBI_POS &&                       // bid-тяжёлый стакан
                    (i.spreadBps ?: Double.MAX_VALUE) <= MAX_SPREAD_BPS &&
                    (i.slippagePercent ?: 0.0) <= MAX_SLIPPAGE &&
                    dur >= MIN_BASE_MS
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
        const val MIN_STABILIZE = 0.3     // % отскока от минимума для перехода к basing
        const val MIN_RECOVERY = 0.6      // % восстановления от baseLow для подтверждения
        const val MIN_BASE_MS = 5_000L
        const val MAX_BASE_MS = 180_000L
        const val TAKER_BUY_DOM = 0.55
        const val OBI_POS = 0.05
        const val MAX_SPREAD_BPS = 60.0
        const val MAX_SLIPPAGE = 0.5
        const val COOLDOWN_MS = 5 * 60_000L
    }
}
