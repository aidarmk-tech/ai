package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.data.local.SnapshotOutcomeDao
import com.aidar.pumpradar.data.local.SnapshotOutcomeEntity
import com.aidar.pumpradar.domain.model.TrajectoryPoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Исходы для ЛЮБОГО снимка признаков (TRIGGERED / NEAR_MISS / RANDOM_NORMAL), а не
 * только для сигналов. Копит контрольные точки и секундную траекторию, а на 15-й
 * минуте считает время достижения барьеров и порядок (first-barrier) через
 * [BarrierAnalyzer]. Это делает все ~1000 ранее неразмеченных строк пригодными
 * для supervised learning. Ордера не отправляются.
 */
@Singleton
class SnapshotOutcomeTracker @Inject constructor() {

    private class Track(
        val snapshotId: String,
        val symbol: String,
        val snapshotType: String,
        val ref: Double,
        val start: Long
    ) {
        var maxP: Double = ref
        var minP: Double = ref
        var p30: Double? = null
        var p1: Double? = null
        var p3: Double? = null
        var p5: Double? = null
        var p15: Double? = null
        val points = ArrayList<TrajectoryPoint>(TRAJECTORY_MAX_POINTS)
        var lastSampleAt = Long.MIN_VALUE
    }

    private val tracks = ArrayList<Track>()
    private val lock = Any()

    /** Зарегистрировать снимок: сразу пишем pending-исход, чтобы он считался. */
    suspend fun track(
        dao: SnapshotOutcomeDao,
        snapshotId: String, symbol: String, snapshotType: String,
        refPrice: Double, now: Long = System.currentTimeMillis()
    ) {
        if (refPrice <= 0.0) return
        synchronized(lock) {
            tracks.add(Track(snapshotId, symbol, snapshotType, refPrice, now))
        }
        runCatching {
            dao.upsert(
                SnapshotOutcomeEntity(
                    snapshotId = snapshotId, symbol = symbol, snapshotType = snapshotType,
                    referencePrice = refPrice, createdAt = now, completed = false
                )
            )
        }
    }

    fun clear() = synchronized(lock) { tracks.clear() }

    suspend fun onTick(
        now: Long,
        priceOf: (String) -> Double?,
        bookOf: (String) -> Pair<Double, Double>?,
        dao: SnapshotOutcomeDao
    ) {
        val finished = ArrayList<Track>()
        val snapshot = synchronized(lock) { tracks.toList() }
        for (t in snapshot) {
            val price = priceOf(t.symbol) ?: continue
            val elapsed = now - t.start
            if (price > t.maxP) t.maxP = price
            if (price < t.minP) t.minP = price
            if (t.p30 == null && elapsed >= 30_000) t.p30 = price
            if (t.p1 == null && elapsed >= 60_000) t.p1 = price
            if (t.p3 == null && elapsed >= 180_000) t.p3 = price
            if (t.p5 == null && elapsed >= 300_000) t.p5 = price
            if (t.p15 == null && elapsed >= 900_000) t.p15 = price
            if (elapsed <= TRAJECTORY_WINDOW_MS &&
                t.points.size < TRAJECTORY_MAX_POINTS &&
                now - t.lastSampleAt >= TRAJECTORY_MIN_STEP_MS
            ) {
                val ba = bookOf(t.symbol)
                if (ba != null && ba.first > 0.0 && ba.second > 0.0) {
                    t.points.add(TrajectoryPoint(offsetMs = elapsed, bid = ba.first, ask = ba.second))
                    t.lastSampleAt = now
                }
            }
            if (elapsed >= 900_000) finished.add(t)
        }
        for (t in finished) runCatching { dao.upsert(finalize(t)) }
        if (finished.isNotEmpty()) synchronized(lock) { tracks.removeAll(finished) }
    }

    private fun finalize(t: Track): SnapshotOutcomeEntity {
        val mfe = (t.maxP / t.ref - 1.0) * 100.0
        val mae = (t.minP / t.ref - 1.0) * 100.0
        // Ряд (времяMs, доходность%): секундная траектория по mid, иначе 5 точек.
        val samples: List<Pair<Long, Double>> =
            if (t.points.size >= TRAJECTORY_MIN_POINTS) {
                t.points.map { it.offsetMs to (it.mid / t.ref - 1.0) * 100.0 }
            } else {
                fun r(p: Double?, ms: Long): Pair<Long, Double>? =
                    p?.takeIf { it > 0.0 }?.let { ms to (it / t.ref - 1.0) * 100.0 }
                listOfNotNull(
                    r(t.p30, 30_000), r(t.p1, 60_000), r(t.p3, 180_000),
                    r(t.p5, 300_000), r(t.p15, 900_000)
                )
            }
        val l1 = BarrierAnalyzer.analyze(samples, TradeSide.LONG, 0.75, 0.50)
        val l2 = BarrierAnalyzer.analyze(samples, TradeSide.LONG, 1.00, 0.75)
        val l3 = BarrierAnalyzer.analyze(samples, TradeSide.LONG, 2.00, 1.00)
        val s1 = BarrierAnalyzer.analyze(samples, TradeSide.SHORT, 0.75, 0.50)
        val s2 = BarrierAnalyzer.analyze(samples, TradeSide.SHORT, 1.00, 0.75)
        val s3 = BarrierAnalyzer.analyze(samples, TradeSide.SHORT, 2.00, 1.00)
        return SnapshotOutcomeEntity(
            snapshotId = t.snapshotId, symbol = t.symbol, snapshotType = t.snapshotType,
            referencePrice = t.ref, createdAt = t.start,
            price30s = t.p30, price1m = t.p1, price3m = t.p3, price5m = t.p5, price15m = t.p15,
            mfePercent = mfe, maePercent = mae, pointCount = samples.size,
            long075TargetTime = l1.targetTimeMs, long050StopTime = l1.stopTimeMs,
            long100TargetTime = l2.targetTimeMs, long075StopTime = l2.stopTimeMs,
            long200TargetTime = l3.targetTimeMs, long100StopTime = l3.stopTimeMs,
            short075TargetTime = s1.targetTimeMs, short050StopTime = s1.stopTimeMs,
            short100TargetTime = s2.targetTimeMs, short075StopTime = s2.stopTimeMs,
            short200TargetTime = s3.targetTimeMs, short100StopTime = s3.stopTimeMs,
            firstBarrierLong075_050 = l1.first.name, firstBarrierLong100_075 = l2.first.name,
            firstBarrierLong200_100 = l3.first.name, firstBarrierShort075_050 = s1.first.name,
            firstBarrierShort100_075 = s2.first.name, firstBarrierShort200_100 = s3.first.name,
            completed = true
        )
    }

    private companion object {
        const val TRAJECTORY_WINDOW_MS = 300_000L
        const val TRAJECTORY_MIN_STEP_MS = 900L
        const val TRAJECTORY_MAX_POINTS = 340
        const val TRAJECTORY_MIN_POINTS = 5
    }
}
