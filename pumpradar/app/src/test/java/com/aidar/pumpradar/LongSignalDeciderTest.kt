package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.LongSignalDecider
import com.aidar.pumpradar.domain.analyzer.LongSignalDecider.PeakFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ужесточённый LONG-решатель (items 1,2,3,5,7,8; item 12). */
class LongSignalDeciderTest {

    private fun good() = LongSignalDecider.Input(
        return15s = 0.9, return60s = 1.0, return5m = 1.2,
        takerBuyRatio30s = 0.90, cvd30s = 2000.0, cvdSlope = 50.0,
        volumeZ30s = 5.0, quoteVolume30s = 50_000.0, relativeStrengthVsBtc = 1.0,
        spreadBps = 20.0, slippagePercent = 0.10, impulseScore = 80,
        dataFresh = true, hardVeto = false, buyerPressureDeclining = false,
        newHighWithoutCvdHigh = false, peak = PeakFeatures(), repeatBlocked = false
    )

    @Test fun successfulLongContinuation() {
        val d = LongSignalDecider.decide(good())
        assertEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
        assertEquals(3.0, d.maxTargetPercent, 1e-9)   // строгие условия → цель до 3%
    }

    @Test fun highTargetRequiresTightConditions() {
        // r5m 2.5% > 1.5% → LONG всё ещё возможен, но цель ограничена 1%.
        val d = LongSignalDecider.decide(good().copy(return5m = 2.5))
        assertEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
        assertEquals(1.0, d.maxTargetPercent, 1e-9)
    }

    @Test fun weakTakerRatioRejected() {
        val d = LongSignalDecider.decide(good().copy(takerBuyRatio30s = 0.70, impulseScore = 50))
        assertEquals(LongSignalDecider.NO_TRADE, d.label)
    }

    @Test fun weakReturn15sRejected() {
        val d = LongSignalDecider.decide(good().copy(return15s = 0.5))
        assertNotEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
    }

    @Test fun lateReturn5mRejected() {
        val d = LongSignalDecider.decide(good().copy(return5m = 3.5))
        assertNotEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
    }

    @Test fun volumeAbsorptionVeto() {
        // Экстремальный объём + низкий taker → поглощение, LONG запрещён.
        val d = LongSignalDecider.decide(good().copy(volumeZ30s = 30.0, takerBuyRatio30s = 0.79))
        assertNotEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
        assertTrue(d.reasons.any { it.contains("поглощение") })
    }

    @Test fun fallingCvdRejected() {
        val d = LongSignalDecider.decide(good().copy(cvdSlope = -10.0, impulseScore = 50))
        assertEquals(LongSignalDecider.NO_TRADE, d.label)
    }

    @Test fun lowerHighRejected() {
        val d = LongSignalDecider.decide(good().copy(peak = PeakFeatures(lowerHighDetected = true)))
        assertNotEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
    }

    @Test fun exhaustionRiskState() {
        val d = LongSignalDecider.decide(
            good().copy(
                buyerPressureDeclining = true, cvdSlope = -5.0, newHighWithoutCvdHigh = true,
                peak = PeakFeatures(lowerHighDetected = true, breakoutLevelHeld = false)
            )
        )
        assertEquals(LongSignalDecider.EXHAUSTION_RISK, d.label)
        assertTrue(d.exhaustionRisk >= 70)
    }

    @Test fun reversalWatchState() {
        val d = LongSignalDecider.decide(
            good().copy(takerBuyRatio30s = 0.65, return60s = 0.9, buyerPressureDeclining = true)
        )
        assertEquals(LongSignalDecider.REVERSAL_WATCH, d.label)
    }

    @Test fun repeatSignalBlocked() {
        val d = LongSignalDecider.decide(good().copy(repeatBlocked = true))
        assertEquals(LongSignalDecider.NO_TRADE, d.label)
    }

    @Test fun distanceFromHighRejects() {
        val d = LongSignalDecider.decide(good().copy(peak = PeakFeatures(distanceFromLocalHighPct = 1.0)))
        assertNotEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
    }
}
