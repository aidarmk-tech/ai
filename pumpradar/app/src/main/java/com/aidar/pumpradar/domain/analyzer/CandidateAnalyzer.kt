package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.core.math.MathUtils
import com.aidar.pumpradar.domain.model.AggTrade
import com.aidar.pumpradar.domain.model.BookTicker
import com.aidar.pumpradar.domain.model.CandidateMetrics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Уровень 2 (ТЗ раздел 8.2): агрессивный поток покупок/продаж, интенсивность,
 * аномалия объёма (robust Z по 10с-бакетам), спред. Хранит сделки за 10 минут.
 *
 * База объёма (bucket-история) отделена от сырых сделок и может прогреваться из
 * 1s-клайнов (warm start, ТЗ 0A.4), поэтому «Объём Z» готов сразу, а не через
 * минуту накопления live-сделок.
 */
@Singleton
class CandidateAnalyzer @Inject constructor() {

    private data class Trade(val t: Long, val quote: Double, val buy: Boolean)
    private class Row {
        val trades = ArrayDeque<Trade>()
        val volBuckets = HashMap<Long, Double>()   // bucketIdx -> суммарный quote-объём
        var book: BookTicker? = null
        var seeded = false
        var lastAggId = -1L        // дедуп/порядок aggTrade (ТЗ 0A.7)
        var lastGapAt = -1L        // время последнего разрыва потока сделок
        var lastBookUpdateId = -1L // монотонность bookTicker
    }

    private val rows = HashMap<String, Row>()
    private val lock = Any()

    private companion object {
        // Сырые сделки держим 10 мин (хватает для окон CVD/taker ≤60с).
        const val TRADE_MAX_AGE_MS = 10 * 60 * 1000L
        // База объёма — 60 мин: длинный baseline (ТЗ 0A.8), устойчивее к одному всплеску.
        const val BUCKET_MAX_AGE_MS = 60 * 60 * 1000L
        // Бакет 10с и минимум 6 завершённых бакетов → прогрев ~70–80с (warm start мгновенно).
        const val BUCKET_MS = 10_000L
        const val MIN_BUCKETS = 6
        const val SHORT_BUCKETS = 60          // короткий baseline ~10 мин
        const val GAP_RECOVERY_MS = 60_000L   // окно «недавнего разрыва» (ТЗ 0A.7)
        const val TINY_TRADE_QUOTE = 10.0     // сделка < 10 USDT считается «мелкой» (§8)
    }

    fun onAggTrade(t: AggTrade) = synchronized(lock) {
        val row = rows.getOrPut(t.symbol) { Row() }
        val now = System.currentTimeMillis()
        // Дедуп и порядок по aggregateTradeId (ТЗ 0A.7).
        if (t.aggId > 0 && row.lastAggId >= 0) {
            if (t.aggId <= row.lastAggId) return   // дубликат/из прошлого — пропустить
            if (t.aggId > row.lastAggId + 1) row.lastGapAt = now  // пропущены сделки → разрыв
        }
        if (t.aggId > row.lastAggId) row.lastAggId = t.aggId
        row.trades.addLast(Trade(t.tradeTime, t.quoteValue, t.isAggressiveBuy))
        val cutoff = now - TRADE_MAX_AGE_MS
        while (row.trades.isNotEmpty() && row.trades.first().t < cutoff) row.trades.removeFirst()
        val idx = t.tradeTime / BUCKET_MS
        row.volBuckets[idx] = (row.volBuckets[idx] ?: 0.0) + t.quoteValue
        pruneBuckets(row, now)
    }

    fun onBookTicker(b: BookTicker) = synchronized(lock) {
        val row = rows.getOrPut(b.symbol) { Row() }
        // Не принимать снимок старше уже принятого (ТЗ 0A.7).
        if (b.updateId > 0 && b.updateId <= row.lastBookUpdateId) return
        if (b.updateId > 0) row.lastBookUpdateId = b.updateId
        row.book = b
    }

    /**
     * Warm start (ТЗ 0A.4): засеять базу объёма из 1s-клайнов
     * (пары closeTime→quoteVolume). Выполняется один раз на символ.
     */
    fun seedVolume(symbol: String, klines: List<Pair<Long, Double>>) = synchronized(lock) {
        if (klines.isEmpty()) return
        val row = rows.getOrPut(symbol) { Row() }
        if (row.seeded) return
        for ((time, quote) in klines) {
            val idx = time / BUCKET_MS
            row.volBuckets[idx] = (row.volBuckets[idx] ?: 0.0) + quote
        }
        row.seeded = true
        pruneBuckets(row, System.currentTimeMillis())
    }

    fun isSeeded(symbol: String): Boolean = synchronized(lock) { rows[symbol]?.seeded == true }

    /** Оставить только активные символы, остальные освободить. */
    fun retain(symbols: Set<String>) = synchronized(lock) {
        rows.keys.retainAll(symbols)
    }

    fun clear() = synchronized(lock) { rows.clear() }

    fun metrics(symbol: String, now: Long = System.currentTimeMillis()): CandidateMetrics? =
        synchronized(lock) {
            val row = rows[symbol] ?: return null
            val trades = row.trades
            if (trades.isEmpty() && row.book == null && row.volBuckets.isEmpty()) return null

            var buy30 = 0.0; var sell30 = 0.0; var count30 = 0
            var buy60 = 0.0; var sell60 = 0.0
            var tinyCount = 0
            val quotes30 = ArrayList<Double>()
            for (tr in trades) {
                val age = now - tr.t
                if (age <= 60_000) {
                    if (tr.buy) buy60 += tr.quote else sell60 += tr.quote
                }
                if (age <= 30_000) {
                    if (tr.buy) buy30 += tr.quote else sell30 += tr.quote
                    count30++
                    quotes30.add(tr.quote)
                    if (tr.quote < TINY_TRADE_QUOTE) tinyCount++
                }
            }
            val total30 = buy30 + sell30
            val takerBuyRatio = if (total30 > MathUtils.EPSILON) buy30 / total30 else null
            val cvd30 = buy30 - sell30
            val cvd60 = buy60 - sell60
            val cvdSlope = cvd30 - (cvd60 - cvd30)

            val volumeZ = volumeAnomalyZ(row, now)
            val spreadBps = row.book?.let { b ->
                val mid = (b.bidPrice + b.askPrice) / 2.0
                MathUtils.safeDivide(b.askPrice - b.bidPrice, mid)?.times(10_000.0)
            }
            val ready = count30 >= 3 && volumeZ != null
            val tradeGap = row.lastGapAt > 0 && now - row.lastGapAt < GAP_RECOVERY_MS

            // Распределение размеров сделок за 30с (патч §8).
            val largestShare: Double?
            val top3Share: Double?
            val tinyShare: Double?
            if (total30 > MathUtils.EPSILON && quotes30.isNotEmpty()) {
                val sorted = quotes30.sortedDescending()
                largestShare = sorted[0] / total30
                top3Share = sorted.take(3).sum() / total30
                tinyShare = tinyCount.toDouble() / quotes30.size
            } else {
                largestShare = null; top3Share = null; tinyShare = null
            }

            CandidateMetrics(
                ready = ready,
                quoteVolume30s = total30,
                tradeCount30s = count30,
                tradesPerSecond = count30 / 30.0,
                takerBuyRatio30s = takerBuyRatio,
                cvd30s = cvd30,
                cvdSlope = cvdSlope,
                volumeZ30s = volumeZ,
                spreadBps = spreadBps,
                tradeGap = tradeGap,
                largestTradeShare = largestShare,
                top3TradeShare = top3Share,
                tinyTradeShare = tinyShare
            )
        }

    private fun pruneBuckets(row: Row, now: Long) {
        val minIdx = (now - BUCKET_MAX_AGE_MS) / BUCKET_MS
        val it = row.volBuckets.keys.iterator()
        while (it.hasNext()) if (it.next() < minIdx) it.remove()
    }

    /**
     * robust Z-score объёма последнего завершённого бакета. Двойной baseline
     * (ТЗ 0A.8): аномалия должна держаться и относительно короткого (~10 мин),
     * и относительно длинного (до 60 мин) окна — берём консервативный минимум,
     * чтобы одиночный всплеск не выдавался за устойчивую аномалию.
     */
    private fun volumeAnomalyZ(row: Row, now: Long): Double? {
        val currentIdx = now / BUCKET_MS
        val completed = row.volBuckets.filterKeys { it < currentIdx }.toSortedMap()
        if (completed.size < MIN_BUCKETS + 1) return null
        val vals = completed.values.toList()
        val current = vals.last()
        val history = vals.dropLast(1)
        val shortZ = MathUtils.robustZScore(current, history.takeLast(SHORT_BUCKETS))
        val longZ = if (history.size > SHORT_BUCKETS) MathUtils.robustZScore(current, history) else null
        return if (longZ != null) minOf(shortZ, longZ) else shortZ
    }
}
