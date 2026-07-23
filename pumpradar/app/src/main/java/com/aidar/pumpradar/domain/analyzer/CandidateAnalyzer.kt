package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.core.math.MathUtils
import com.aidar.pumpradar.domain.model.AggTrade
import com.aidar.pumpradar.domain.model.BookTicker
import com.aidar.pumpradar.domain.model.CandidateMetrics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Уровень 2 (ТЗ раздел 8.2): агрессивный поток покупок/продаж, интенсивность,
 * аномалия объёма (robust Z по 30с-бакетам), спред. Хранит сделки за 10 минут.
 */
@Singleton
class CandidateAnalyzer @Inject constructor() {

    private data class Trade(val t: Long, val quote: Double, val buy: Boolean)
    private class Row {
        val trades = ArrayDeque<Trade>()
        var book: BookTicker? = null
    }

    private val rows = HashMap<String, Row>()
    private val lock = Any()

    private companion object {
        const val MAX_AGE_MS = 10 * 60 * 1000L
        const val BUCKET_MS = 30_000L
        const val MIN_BUCKETS = 10
    }

    fun onAggTrade(t: AggTrade) = synchronized(lock) {
        val row = rows.getOrPut(t.symbol) { Row() }
        row.trades.addLast(Trade(t.tradeTime, t.quoteValue, t.isAggressiveBuy))
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        while (row.trades.isNotEmpty() && row.trades.first().t < cutoff) row.trades.removeFirst()
    }

    fun onBookTicker(b: BookTicker) = synchronized(lock) {
        rows.getOrPut(b.symbol) { Row() }.book = b
    }

    /** Оставить только активные символы, остальные освободить. */
    fun retain(symbols: Set<String>) = synchronized(lock) {
        rows.keys.retainAll(symbols)
    }

    fun clear() = synchronized(lock) { rows.clear() }

    fun metrics(symbol: String, now: Long = System.currentTimeMillis()): CandidateMetrics? =
        synchronized(lock) {
            val row = rows[symbol] ?: return null
            val trades = row.trades
            if (trades.isEmpty() && row.book == null) return null

            var buy30 = 0.0; var sell30 = 0.0; var count30 = 0
            var buy60 = 0.0; var sell60 = 0.0
            for (tr in trades) {
                val age = now - tr.t
                if (age <= 60_000) {
                    if (tr.buy) buy60 += tr.quote else sell60 += tr.quote
                }
                if (age <= 30_000) {
                    if (tr.buy) buy30 += tr.quote else sell30 += tr.quote
                    count30++
                }
            }
            val total30 = buy30 + sell30
            val takerBuyRatio = if (total30 > MathUtils.EPSILON) buy30 / total30 else null
            val cvd30 = buy30 - sell30
            val cvd60 = buy60 - sell60
            // Наклон CVD: последние 30с минус предыдущие 30с.
            val cvdSlope = cvd30 - (cvd60 - cvd30)

            val volumeZ = volumeAnomalyZ(trades, now)
            val spreadBps = row.book?.let { b ->
                val mid = (b.bidPrice + b.askPrice) / 2.0
                MathUtils.safeDivide(b.askPrice - b.bidPrice, mid)?.times(10_000.0)
            }
            val ready = count30 >= 3 && volumeZ != null

            CandidateMetrics(
                ready = ready,
                quoteVolume30s = total30,
                tradeCount30s = count30,
                tradesPerSecond = count30 / 30.0,
                takerBuyRatio30s = takerBuyRatio,
                cvd30s = cvd30,
                cvdSlope = cvdSlope,
                volumeZ30s = volumeZ,
                spreadBps = spreadBps
            )
        }

    /** robust Z-score объёма текущего завершённого 30с-бакета к истории. */
    private fun volumeAnomalyZ(trades: ArrayDeque<Trade>, now: Long): Double? {
        if (trades.size < 10) return null
        val buckets = HashMap<Long, Double>()
        for (tr in trades) {
            val idx = tr.t / BUCKET_MS
            buckets[idx] = (buckets[idx] ?: 0.0) + tr.quote
        }
        val currentIdx = now / BUCKET_MS
        // Завершённые бакеты (исключая текущий незавершённый), по возрастанию.
        val completed = buckets.filterKeys { it < currentIdx }.toSortedMap()
        if (completed.size < MIN_BUCKETS + 1) return null
        val vals = completed.values.toList()
        val current = vals.last()
        val history = vals.dropLast(1)
        return MathUtils.robustZScore(current, history)
    }
}
