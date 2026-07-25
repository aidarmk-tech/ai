package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.data.local.SnapshotOutcomeDao
import com.aidar.pumpradar.data.local.SnapshotOutcomeEntity
import com.aidar.pumpradar.data.local.TrainingSnapshotDao
import com.aidar.pumpradar.domain.model.TrajectoryPoint
import com.aidar.pumpradar.notification.PlanNotificationManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Исходы для ЛЮБОГО снимка признаков. Копит секундную bid/ask-траекторию на
 * горизонте 15 минут, считает порядок барьеров и защищённый план до +3%.
 *
 * Для TRIGGERED-снимков с LONG_CONTINUATION дополнительно выдаёт стадийные
 * уведомления: защита после +1%, цель +3%, защитный выход и подтверждённое
 * ослабление потока. Ордера не отправляются.
 */
@Singleton
class SnapshotOutcomeTracker @Inject constructor(
    private val trainingSnapshotDao: TrainingSnapshotDao,
    private val analyzer: CandidateAnalyzer,
    private val signalConfig: SignalConfig,
    private val planNotifier: PlanNotificationManager
) {

    private class Track(
        val snapshotId: String,
        val symbol: String,
        val snapshotType: String,
        val ref: Double,
        val start: Long,
        val planLong: Boolean
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
        var planActivated = false
        var planClosed = false
        var planWeakTicks = 0
        var maxReturnPercent = 0.0
    }

    private val tracks = ArrayList<Track>()
    private val lock = Any()

    suspend fun track(
        dao: SnapshotOutcomeDao,
        snapshotId: String, symbol: String, snapshotType: String,
        refPrice: Double, now: Long = System.currentTimeMillis()
    ) {
        if (refPrice <= 0.0) return
        val planLong = runCatching {
            trainingSnapshotDao.get(snapshotId)?.opportunityLabel == LongSignalDecider.LONG_CONTINUATION
        }.getOrDefault(false)
        synchronized(lock) {
            tracks.add(Track(snapshotId, symbol, snapshotType, refPrice, now, planLong))
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
            if (price > t.maxP) { t.maxP = price; t.timeToMfe = elapsed }
            if (price < t.minP) t.minP = price
            updateLivePlan(t, price)
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

    private fun updateLivePlan(t: Track, price: Double) {
        if (!t.planLong || t.planClosed) return
        val returnPct = (price / t.ref - 1.0) * 100.0
        if (returnPct > t.maxReturnPercent) t.maxReturnPercent = returnPct

        if (!t.planActivated) {
            when {
                returnPct <= -signalConfig.initialStopPercent -> {
                    t.planClosed = true
                    planNotifier.notify(
                        t.symbol,
                        PlanNotificationManager.State.INITIAL_STOP,
                        returnPct
                    )
                }
                returnPct >= signalConfig.target3Percent -> {
                    t.planActivated = true
                    t.planClosed = true
                    planNotifier.notify(
                        t.symbol,
                        PlanNotificationManager.State.TARGET_3_REACHED,
                        returnPct
                    )
                }
                returnPct >= signalConfig.protectionActivationPercent -> {
                    t.planActivated = true
                    planNotifier.notify(
                        t.symbol,
                        PlanNotificationManager.State.PROTECT_AFTER_1,
                        returnPct
                    )
                }
            }
            return
        }

        when {
            returnPct >= signalConfig.target3Percent -> {
                t.planClosed = true
                planNotifier.notify(
                    t.symbol,
                    PlanNotificationManager.State.TARGET_3_REACHED,
                    returnPct
                )
            }
            returnPct <= signalConfig.protectedStopPercent -> {
                t.planClosed = true
                planNotifier.notify(
                    t.symbol,
                    PlanNotificationManager.State.PROTECTED_EXIT,
                    returnPct
                )
            }
            else -> {
                val m = analyzer.metrics(t.symbol)
                val assessment = ThreePercentLivePolicy.assess(
                    ThreePercentLivePolicy.Input(
                        currentReturnPercent = returnPct,
                        maxReturnPercent = t.maxReturnPercent,
                        takerBuyRatio5s = m?.takerBuyRatio5s,
                        takerBuyRatio15s = m?.takerBuyRatio15s,
                        takerBuyRatio30s = m?.takerBuyRatio30s,
                        cvdSlope = m?.cvdSlope
                    ),
                    signalConfig
                )
                t.planWeakTicks = if (assessment.flowWeak) t.planWeakTicks + 1 else 0
                if (t.planWeakTicks >= signalConfig.weakeningConfirmTicks) {
                    t.planClosed = true
                    planNotifier.notify(
                        t.symbol,
                        PlanNotificationManager.State.EXIT_WEAKENING,
                        returnPct,
                        assessment.reasons
                    )
                }
            }
        }
    }

    private fun finalize(t: Track): SnapshotOutcomeEntity {
        val mfe = (t.maxP / t.ref - 1.0) * 100.0
        val mae = (t.minP / t.ref - 1.0) * 100.0
        val samples: List<Pair<Long, Double>> =
            if (t.points.size >= TRAJECTORY_MIN_POINTS) {
                t.points.map { it.offsetMs to ((it.bid + it.ask) / 2.0 / t.ref - 1.0) * 100.0 }
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
        val l4 = BarrierAnalyzer.analyze(samples, TradeSide.LONG, 3.00, 1.00)
        val s1 = BarrierAnalyzer.analyze(samples, TradeSide.SHORT, 0.75, 0.50)
        val s2 = BarrierAnalyzer.analyze(samples, TradeSide.SHORT, 1.00, 0.75)
        val s3 = BarrierAnalyzer.analyze(samples, TradeSide.SHORT, 2.00, 1.00)
        val plan3 = ThreePercentPlanEvaluator.evaluate(samples)
        return SnapshotOutcomeEntity(
            snapshotId = t.snapshotId, symbol = t.symbol, snapshotType = t.snapshotType,
            referencePrice = t.ref, createdAt = t.start,
            price30s = t.p30, price1m = t.p1, price3m = t.p3, price5m = t.p5, price15m = t.p15,
            mfePercent = mfe, maePercent = mae, pointCount = samples.size,
            long075TargetTime = l1.targetTimeMs, long050StopTime = l1.stopTimeMs,
            long100TargetTime = l2.targetTimeMs, long075StopTime = l2.stopTimeMs,
            long200TargetTime = l3.targetTimeMs, long100StopTime = l3.stopTimeMs,
            long300TargetTime = l4.targetTimeMs,
            short075TargetTime = s1.targetTimeMs, short050StopTime = s1.stopTimeMs,
            short100TargetTime = s2.targetTimeMs, short075StopTime = s2.stopTimeMs,
            short200TargetTime = s3.targetTimeMs, short100StopTime = s3.stopTimeMs,
            firstBarrierLong075_050 = l1.first.name,
            firstBarrierLong100_075 = l2.first.name,
            firstBarrierLong200_100 = l3.first.name,
            firstBarrierLong300_100 = l4.first.name,
            firstBarrierShort075_050 = s1.first.name,
            firstBarrierShort100_075 = s2.first.name,
            firstBarrierShort200_100 = s3.first.name,
            plan3ActivationTime = plan3.activationTimeMs,
            plan3TargetTime = plan3.targetTimeMs,
            plan3ExitTime = plan3.exitTimeMs,
            plan3Result = plan3.result.name,
            plan3GrossReturnPercent = plan3.grossReturnPercent,
            completed = true
        )
    }

    private companion object {
        const val TRAJECTORY_WINDOW_MS = 900_000L
        const val TRAJECTORY_MIN_STEP_MS = 900L
        const val TRAJECTORY_MAX_POINTS = 1_020
        const val TRAJECTORY_MIN_POINTS = 5
    }
}
