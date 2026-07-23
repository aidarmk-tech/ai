package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.core.math.MathUtils
import com.aidar.pumpradar.domain.model.OrderBookMetrics
import com.aidar.pumpradar.domain.model.PartialDepth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Уровень 3 (ТЗ раздел 8.3): OBI, спред и симуляция проскальзывания покупки
 * по top-20 стакану. Только последний снимок на символ (ТЗ раздел 19).
 */
@Singleton
class OrderBookAnalyzer @Inject constructor() {

    private val books = HashMap<String, PartialDepth>()
    private val lastUpdateId = HashMap<String, Long>()
    private val lock = Any()

    fun onDepth(d: PartialDepth) = synchronized(lock) {
        // Не принимать снимок с lastUpdateId <= уже принятого (ТЗ 0A.7).
        if (d.lastUpdateId > 0) {
            val prev = lastUpdateId[d.symbol] ?: -1L
            if (d.lastUpdateId <= prev) return@synchronized
            lastUpdateId[d.symbol] = d.lastUpdateId
        }
        books[d.symbol] = d
    }

    fun retain(symbols: Set<String>) = synchronized(lock) {
        books.keys.retainAll(symbols)
        lastUpdateId.keys.retainAll(symbols)
    }

    fun clear() = synchronized(lock) { books.clear(); lastUpdateId.clear() }

    fun metrics(symbol: String, slippageAmountUsdt: Double): OrderBookMetrics? =
        synchronized(lock) {
            val d = books[symbol] ?: return null
            if (d.bids.isEmpty() || d.asks.isEmpty()) return null
            val bestBid = d.bids.first().price
            val bestAsk = d.asks.first().price
            val mid = (bestBid + bestAsk) / 2.0
            val spreadBps = MathUtils.safeDivide(bestAsk - bestBid, mid)?.times(10_000.0) ?: return null

            fun notional(levels: List<com.aidar.pumpradar.domain.model.DepthLevel>, n: Int) =
                levels.take(n).sumOf { it.price * it.qty }

            val bid10 = notional(d.bids, 10); val ask10 = notional(d.asks, 10)
            val bid20 = notional(d.bids, 20); val ask20 = notional(d.asks, 20)
            val obi10 = imbalance(bid10, ask10)
            val obi20 = imbalance(bid20, ask20)
            val slippage = buySlippage(d, bestAsk, slippageAmountUsdt)

            OrderBookMetrics(
                spreadBps = spreadBps,
                obi10 = obi10,
                obi20 = obi20,
                buySlippagePercent = slippage,
                bidNotionalTop10 = bid10,
                askNotionalTop10 = ask10
            )
        }

    private fun imbalance(bid: Double, ask: Double): Double {
        val denom = bid + ask
        return if (denom > MathUtils.EPSILON) (bid - ask) / denom else 0.0
    }

    /** Виртуальная покупка на amount USDT: средневзвешенная цена vs best ask. */
    private fun buySlippage(
        d: PartialDepth,
        bestAsk: Double,
        amount: Double
    ): Double? {
        var remaining = amount
        var quoteSpent = 0.0
        var baseBought = 0.0
        for (lvl in d.asks) {
            val levelQuote = lvl.price * lvl.qty
            if (levelQuote >= remaining) {
                val baseHere = remaining / lvl.price
                baseBought += baseHere
                quoteSpent += remaining
                remaining = 0.0
                break
            } else {
                baseBought += lvl.qty
                quoteSpent += levelQuote
                remaining -= levelQuote
            }
        }
        if (remaining > MathUtils.EPSILON) return null // InsufficientDepth
        if (baseBought <= 0.0) return null
        val wavg = quoteSpent / baseBought
        return (wavg / bestAsk - 1.0) * 100.0
    }
}
