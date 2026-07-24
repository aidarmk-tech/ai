package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.core.math.MathUtils
import com.aidar.pumpradar.domain.model.OrderBookMetrics
import com.aidar.pumpradar.domain.model.PartialDepth
import com.aidar.pumpradar.domain.model.SlippageProbe
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
            // buySlippagePercent сохраняет контракт «null = недостаточно глубины»
            // на настраиваемую сумму (от него зависят скоринг/вето/EARLY_CLEAN).
            val configProbe = probe(d, bestAsk, slippageAmountUsdt)
            val slippage = if (configProbe.shortfallUsdt > MathUtils.EPSILON) null
                else configProbe.slippagePercent
            // item 6: кривая 10/50/100 USDT + стоимость спреда отдельно.
            val spreadCostPercent = (bestAsk - bestBid) / mid * 100.0

            OrderBookMetrics(
                spreadBps = spreadBps,
                obi10 = obi10,
                obi20 = obi20,
                buySlippagePercent = slippage,
                bidNotionalTop10 = bid10,
                askNotionalTop10 = ask10,
                slippage10 = probe(d, bestAsk, 10.0),
                slippage50 = probe(d, bestAsk, 50.0),
                slippage100 = probe(d, bestAsk, 100.0),
                spreadCostPercent = spreadCostPercent
            )
        }

    private fun imbalance(bid: Double, ask: Double): Double {
        val denom = bid + ask
        return if (denom > MathUtils.EPSILON) (bid - ask) / denom else 0.0
    }

    /**
     * Виртуальная покупка на amount USDT: средневзвешенная цена vs best ask.
     * Возвращает фактическое проскальзывание (не округляем малое к нулю),
     * исполненный объём и нехватку глубины (item 6). slippagePercent = null,
     * если глубины совсем нет (ни один уровень не исполнен).
     */
    private fun probe(d: PartialDepth, bestAsk: Double, amount: Double): SlippageProbe {
        var remaining = amount
        var quoteSpent = 0.0
        var baseBought = 0.0
        for (lvl in d.asks) {
            val levelQuote = lvl.price * lvl.qty
            if (levelQuote >= remaining) {
                baseBought += remaining / lvl.price
                quoteSpent += remaining
                remaining = 0.0
                break
            } else {
                baseBought += lvl.qty
                quoteSpent += levelQuote
                remaining -= levelQuote
            }
        }
        val filled = amount - remaining
        val shortfall = remaining.coerceAtLeast(0.0)
        val slip = if (baseBought > 0.0 && bestAsk > 0.0) {
            (quoteSpent / baseBought / bestAsk - 1.0) * 100.0
        } else null
        return SlippageProbe(
            amountUsdt = amount,
            slippagePercent = slip,       // фактическое, включая очень малые значения
            filledUsdt = filled,
            shortfallUsdt = shortfall
        )
    }
}
