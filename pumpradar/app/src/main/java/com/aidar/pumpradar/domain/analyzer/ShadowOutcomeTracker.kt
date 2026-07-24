package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.data.local.ShadowSignalDao
import com.aidar.pumpradar.data.local.ShadowSignalEntity
import com.aidar.pumpradar.domain.model.TrajectoryPoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Отслеживание исходов ТЕНЕВЫХ (SHADOW/PAPER) сигналов двусторонних стратегий.
 * Зеркально [OutcomeTracker], но пишет в отдельную таблицу с тегом стратегии и
 * стороны и копит секундную траекторию bid/ask. Ордера не отправляются.
 *
 * Строка вставляется в момент срабатывания (completed=false), чтобы стратегия
 * сразу считалась, и дополняется контрольными точками, MFE/MAE и траекторией
 * через 15 минут.
 */
@Singleton
class ShadowOutcomeTracker @Inject constructor(
    private val json: Json
) {

    private class Track(
        val id: String,
        val strategy: String,
        val side: String,
        val symbol: String,
        val ref: Double,
        val spreadBps: Double?,
        val slippagePercent: Double?,
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

    /** Зарегистрировать теневой сигнал: сразу пишем pending-строку и трек. */
    suspend fun track(
        dao: ShadowSignalDao,
        id: String, strategy: String, side: String, symbol: String,
        refPrice: Double, spreadBps: Double?, slippagePercent: Double?,
        now: Long = System.currentTimeMillis()
    ) {
        if (refPrice <= 0.0) return
        synchronized(lock) {
            tracks.add(Track(id, strategy, side, symbol, refPrice, spreadBps, slippagePercent, now))
        }
        runCatching {
            dao.upsert(
                ShadowSignalEntity(
                    id = id, strategy = strategy, side = side, symbol = symbol,
                    createdAt = now, referencePrice = refPrice,
                    spreadBps = spreadBps, slippagePercent = slippagePercent, completed = false
                )
            )
        }
    }

    fun clear() = synchronized(lock) { tracks.clear() }

    suspend fun onTick(
        now: Long,
        priceOf: (String) -> Double?,
        bookOf: (String) -> Pair<Double, Double>?,
        dao: ShadowSignalDao
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
        for (t in finished) {
            val mfe = (t.maxP / t.ref - 1.0) * 100.0
            val mae = (t.minP / t.ref - 1.0) * 100.0
            val pointsJson = if (t.points.size >= TRAJECTORY_MIN_POINTS) {
                runCatching {
                    json.encodeToString(ListSerializer(TrajectoryPoint.serializer()), t.points)
                }.getOrNull()
            } else null
            runCatching {
                dao.upsert(
                    ShadowSignalEntity(
                        id = t.id, strategy = t.strategy, side = t.side, symbol = t.symbol,
                        createdAt = t.start, referencePrice = t.ref,
                        spreadBps = t.spreadBps, slippagePercent = t.slippagePercent,
                        price30s = t.p30, price1m = t.p1, price3m = t.p3,
                        price5m = t.p5, price15m = t.p15,
                        mfePercent = mfe, maePercent = mae,
                        pointsJson = pointsJson, completed = true
                    )
                )
            }
        }
        if (finished.isNotEmpty()) synchronized(lock) { tracks.removeAll(finished) }
    }

    private companion object {
        const val TRAJECTORY_WINDOW_MS = 300_000L
        const val TRAJECTORY_MIN_STEP_MS = 900L
        const val TRAJECTORY_MAX_POINTS = 340
        const val TRAJECTORY_MIN_POINTS = 5
    }
}
