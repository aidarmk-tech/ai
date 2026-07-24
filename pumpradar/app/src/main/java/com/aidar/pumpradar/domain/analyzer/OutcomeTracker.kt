package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.data.local.OutcomeDao
import com.aidar.pumpradar.data.local.OutcomeEntity
import com.aidar.pumpradar.data.local.SignalTrajectoryDao
import com.aidar.pumpradar.data.local.SignalTrajectoryEntity
import com.aidar.pumpradar.domain.model.TrajectoryPoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome Tracker (ТЗ раздел 22): после сигнала следит за ценой на контрольных
 * точках (30с/1м/3м/5м/15м), считает MFE/MAE и записывает итог в БД.
 *
 * Двусторонний анализ: дополнительно пишет СЕКУНДНУЮ траекторию best bid/ask на
 * ранний горизонт (≈5 мин, шаг ≈тик движка) — чтобы исход можно было пересчитать
 * по временной последовательности с учётом стороны стакана, спреда и задержки
 * реакции, а не по одним MFE/MAE.
 */
@Singleton
class OutcomeTracker @Inject constructor(
    private val json: Json
) {

    private class Track(
        val signalId: String,
        val symbol: String,
        val ref: Double,
        val start: Long
    ) {
        var maxP: Double = ref
        var minP: Double = ref
        var timeToMfe: Long = 0
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

    fun track(signalId: String, symbol: String, refPrice: Double, now: Long = System.currentTimeMillis()) =
        synchronized(lock) {
            if (refPrice > 0.0) tracks.add(Track(signalId, symbol, refPrice, now))
        }

    fun clear() = synchronized(lock) { tracks.clear() }

    /**
     * Прогон на текущем тике: обновляет активные треки, копит секундную
     * траекторию bid/ask на раннем горизонте, финализирует по 15 мин.
     */
    suspend fun onTick(
        now: Long,
        priceOf: (String) -> Double?,
        dao: OutcomeDao,
        bookOf: ((String) -> Pair<Double, Double>?)? = null,
        trajectoryDao: SignalTrajectoryDao? = null
    ) {
        val finished = ArrayList<Track>()
        val snapshot = synchronized(lock) { tracks.toList() }
        for (t in snapshot) {
            val price = priceOf(t.symbol) ?: continue
            val elapsed = now - t.start
            if (price > t.maxP) { t.maxP = price; t.timeToMfe = elapsed }
            if (price < t.minP) t.minP = price
            if (t.p30 == null && elapsed >= 30_000) t.p30 = price
            if (t.p1 == null && elapsed >= 60_000) t.p1 = price
            if (t.p3 == null && elapsed >= 180_000) t.p3 = price
            if (t.p5 == null && elapsed >= 300_000) t.p5 = price
            if (t.p15 == null && elapsed >= 900_000) t.p15 = price
            // Секундная траектория bid/ask на раннем горизонте.
            if (bookOf != null && elapsed <= TRAJECTORY_WINDOW_MS &&
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
        for (t in finished) {
            val mfe = (t.maxP / t.ref - 1.0) * 100.0
            val mae = (t.minP / t.ref - 1.0) * 100.0
            runCatching {
                dao.upsert(
                    OutcomeEntity(
                        signalId = t.signalId,
                        price30s = t.p30, price1m = t.p1, price3m = t.p3,
                        price5m = t.p5, price15m = t.p15,
                        mfePercent = mfe, maePercent = mae,
                        timeToMfeSeconds = t.timeToMfe / 1000,
                        evaluatedUntil = now, completed = true
                    )
                )
            }
            if (trajectoryDao != null && t.points.size >= TRAJECTORY_MIN_POINTS) {
                runCatching {
                    trajectoryDao.insert(
                        SignalTrajectoryEntity(
                            signalId = t.signalId,
                            symbol = t.symbol,
                            referencePrice = t.ref,
                            startedAt = t.start,
                            resolutionMs = TRAJECTORY_MIN_STEP_MS,
                            pointCount = t.points.size,
                            pointsJson = json.encodeToString(
                                ListSerializer(TrajectoryPoint.serializer()), t.points
                            )
                        )
                    )
                }
            }
        }
        if (finished.isNotEmpty()) synchronized(lock) { tracks.removeAll(finished) }
    }

    private companion object {
        // Ранний горизонт секундной траектории: 5 минут при шаге ≈тик движка (1с)
        // → до ~300 точек. Достаточно для реакции 0.5/2/5с и целей ±0.75…2%.
        const val TRAJECTORY_WINDOW_MS = 300_000L
        const val TRAJECTORY_MIN_STEP_MS = 900L
        const val TRAJECTORY_MAX_POINTS = 340
        const val TRAJECTORY_MIN_POINTS = 5
    }
}
