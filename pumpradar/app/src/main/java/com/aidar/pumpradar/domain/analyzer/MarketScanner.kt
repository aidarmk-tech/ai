package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.core.math.MathUtils
import com.aidar.pumpradar.domain.model.Candidate
import com.aidar.pumpradar.domain.model.MiniTicker
import com.aidar.pumpradar.domain.model.PriceSample
import com.aidar.pumpradar.domain.model.SymbolInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Уровень 1 (ТЗ раздел 8.1). Держит кольцевой буфер секундных цен по каждой
 * паре, считает доходности/скорость/ускорение и отбирает кандидатов по PreScore.
 * Потокобезопасен через грубую синхронизацию (пишет WS-поток, читает движок).
 */
@Singleton
class MarketScanner @Inject constructor() {

    private data class Row(
        val info: SymbolInfo,
        val samples: ArrayDeque<PriceSample> = ArrayDeque(),
        var price: Double = 0.0,
        var quoteVolume24h: Double = 0.0
    )

    private val rows = HashMap<String, Row>()
    private val lock = Any()

    private companion object {
        const val MAX_AGE_MS = 10 * 60 * 1000L   // 10 минут истории
        const val MIN_SAMPLE_GAP_MS = 900L        // ~1 сэмпл/сек
        const val CONFIGURED_ACCEL = 0.30
    }

    fun setUniverse(universe: List<SymbolInfo>) = synchronized(lock) {
        val allowed = universe.associateBy { it.symbol }
        rows.keys.retainAll(allowed.keys)
        for ((sym, info) in allowed) {
            if (!rows.containsKey(sym)) rows[sym] = Row(info)
        }
    }

    fun symbolCount(): Int = synchronized(lock) { rows.size }

    fun priceOf(symbol: String): Double? = synchronized(lock) {
        rows[symbol]?.price?.takeIf { it > 0.0 }
    }

    fun onTicks(tickers: List<MiniTicker>, now: Long = System.currentTimeMillis()) =
        synchronized(lock) {
            for (t in tickers) {
                val row = rows[t.symbol] ?: continue
                row.price = t.close
                row.quoteVolume24h = t.quoteVolume
                val dq = row.samples
                if (dq.isEmpty() || now - dq.last().time >= MIN_SAMPLE_GAP_MS) {
                    dq.addLast(PriceSample(now, t.close))
                    while (dq.isNotEmpty() && now - dq.first().time > MAX_AGE_MS) dq.removeFirst()
                }
            }
        }

    /** Кандидаты первого уровня, отсортированные по PreScore. */
    fun computeCandidates(
        minVolume: Double,
        maxCandidates: Int,
        now: Long = System.currentTimeMillis()
    ): List<Candidate> = synchronized(lock) {
        val btcRet60 = rows["BTCUSDT"]?.let { returnOverWindow(it, 60_000, now) }
        val out = ArrayList<Candidate>()
        for (row in rows.values) {
            if (row.price <= 0.0) continue
            if (row.quoteVolume24h < minVolume) continue
            val r15 = returnOverWindow(row, 15_000, now)
            val r60 = returnOverWindow(row, 60_000, now)
            val r5m = returnOverWindow(row, 300_000, now)
            val accel = acceleration(row, now)
            val passes = (r15 != null && r15 >= 0.35) ||
                (r60 != null && r60 >= 0.80) ||
                (accel != null && accel >= CONFIGURED_ACCEL)
            if (!passes) continue
            val relBtc = if (r60 != null && btcRet60 != null) r60 - btcRet60 else null
            out.add(
                Candidate(
                    symbol = row.info.symbol,
                    price = row.price,
                    return15s = r15,
                    return60s = r60,
                    return5m = r5m,
                    acceleration = accel,
                    quoteVolume24h = row.quoteVolume24h,
                    relativeStrengthVsBtc = relBtc,
                    preScore = preScore(r15, r60, accel, relBtc, row.quoteVolume24h)
                )
            )
        }
        out.sortByDescending { it.preScore }
        out.subList(0, min(maxCandidates, out.size)).toList()
    }

    // ── Внутреннее ──

    private fun returnOverWindow(row: Row, windowMs: Long, now: Long): Double? {
        val dq = row.samples
        if (dq.size < 2) return null
        val target = now - windowMs
        if (dq.first().time > target) return null // недостаточно истории
        var past = dq.first().price
        for (s in dq) {
            if (s.time <= target) past = s.price else break
        }
        return MathUtils.returnPercent(row.price, past)
    }

    private fun returnBetween(row: Row, olderMs: Long, newerMs: Long, now: Long): Double? {
        val older = priceAt(row, now - olderMs) ?: return null
        val newer = priceAt(row, now - newerMs) ?: return null
        return MathUtils.returnPercent(newer, older)
    }

    private fun priceAt(row: Row, targetTime: Long): Double? {
        val dq = row.samples
        if (dq.isEmpty() || dq.first().time > targetTime) return null
        var p = dq.first().price
        for (s in dq) {
            if (s.time <= targetTime) p = s.price else break
        }
        return p
    }

    /** Ускорение = скорость(0..15с) − скорость(15..30с). */
    private fun acceleration(row: Row, now: Long): Double? {
        val vNow = returnBetween(row, 15_000, 0, now) ?: return null
        val vPrev = returnBetween(row, 30_000, 15_000, now) ?: return null
        return vNow - vPrev
    }

    private fun preScore(
        r15: Double?, r60: Double?, accel: Double?, relBtc: Double?, quoteVol: Double
    ): Double {
        fun clamp(v: Double?, div: Double, lo: Double, hi: Double): Double =
            ((v ?: 0.0) / div).coerceIn(lo, hi)
        val liquidity = (kotlin.math.log10((quoteVol / 1_000_000.0).coerceAtLeast(0.1)) / 2.0)
            .coerceIn(0.0, 1.0)
        return clamp(r15, 0.5, 0.0, 3.0) * 20 +
            clamp(r60, 1.0, 0.0, 3.0) * 25 +
            clamp(accel, 0.3, 0.0, 3.0) * 20 +
            clamp(relBtc, 0.5, -2.0, 3.0) * 15 +
            liquidity * 20
    }

    fun clear() = synchronized(lock) { rows.clear() }
}
