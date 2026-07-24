package com.aidar.pumpradar.domain.analyzer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * LONG_STRICT (теневая стратегия). Простой строгий гейт для лонга без FSM:
 *   takerBuyRatio30s ≥ 0.85, return60s ≥ 1%, положительный CVD, приемлемые
 *   спред/проскальзывание. Срабатывает не чаще, чем раз в COOLDOWN, чтобы не
 *   спамить, пока условия держатся. Только SHADOW/PAPER, без ордеров.
 */
@Singleton
class LongStrictDetector @Inject constructor() {

    private val cooldownUntil = HashMap<String, Long>()
    private val lock = Any()

    fun retain(symbols: Set<String>) = synchronized(lock) { cooldownUntil.keys.retainAll(symbols) }
    fun clear() = synchronized(lock) { cooldownUntil.clear() }

    fun update(symbol: String, i: ShadowStrategyInput): Boolean = synchronized(lock) {
        if (i.now < (cooldownUntil[symbol] ?: 0L)) return false
        val ok = i.isUpImpulse &&
            (i.takerBuyRatio ?: 0.0) >= MIN_TAKER_BUY &&
            (i.return60s ?: 0.0) >= MIN_RETURN60 &&
            i.cvd > 0.0 &&
            (i.spreadBps ?: Double.MAX_VALUE) <= MAX_SPREAD_BPS &&
            (i.slippagePercent ?: 0.0) <= MAX_SLIPPAGE
        if (ok) {
            cooldownUntil[symbol] = i.now + COOLDOWN_MS
            return true
        }
        false
    }

    private companion object {
        const val MIN_TAKER_BUY = 0.85
        const val MIN_RETURN60 = 1.0      // %
        const val MAX_SPREAD_BPS = 40.0
        const val MAX_SLIPPAGE = 0.35
        const val COOLDOWN_MS = 5 * 60_000L
    }
}
