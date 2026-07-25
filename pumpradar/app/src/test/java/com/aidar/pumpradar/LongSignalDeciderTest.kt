package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.LongSignalDecider
import com.aidar.pumpradar.domain.analyzer.LongSignalDecider.PeakFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongSignalDeciderTest {

    private fun good() = LongSignalDecider.Input(
        return15s = 0.9,
        return60s = 1.0,
        return5m = 1.2,
        takerBuyRatio30s = 0.90,
        cvd30s = 2000.0,
        cvdSlope = 50.0,
        volumeZ30s = 5.0,
        quoteVolume30s = 50_000.0,
        relativeStrengthVsBtc = 1.0,
        spreadBps = 20.0,
        slippagePercent = 0.10,
        impulseScore = 80,
        dataFresh = true,
        hardVeto = false,
        buyerPressureDeclining = false,
        newHighWithoutCvdHigh = false,
        peak = PeakFeatures(),
        repeatBlocked = false
    )

    @Test
    fun earlyLongUsesProtectedThreePercentPlan() {
        val d = LongSignalDecider.decide(good())
        assertEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
        assertEquals(3.0, d.maxTargetPercent, 1e-9)
        assertTrue(d.reasons.any { it.contains("защита после +1%") })
    }

    @Test
    fun ordinaryContinuationAlsoUsesThreePercentPlan() {
        val d = LongSignalDecider.decide(good().copy(return5m = 2.5))
        assertEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
        assertEquals(3.0, d.maxTargetPercent, 1e-9)
    }

    @Test
    fun controlledPullbackCanSignalBeforeOldHigh() {
        val d = LongSignalDecider.decide(
            good().copy(
                return15s = 0.30,
                return60s = 0.40,
                return5m = 2.0,
                takerBuyRatio30s = 0.70,
                peak = PeakFeatures(
                    distanceFromLocalHighPct = 1.20,
                    pullbackFromHighPct = 2.00,
                    secondsSinceLocalHigh = 25,
                    breakoutLevelHeld = true
                )
            )
        )
        assertEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
        assertEquals(3.0, d.maxTargetPercent, 1e-9)
        assertTrue(d.reasons.any { it.contains("ретест подтверждён") })
    }

    @Test
    fun pullbackWithoutRecoveryIsRejected() {
        val d = LongSignalDecider.decide(
            good().copy(
                return15s = 0.30,
                return60s = 0.40,
                return5m = 2.0,
                takerBuyRatio30s = 0.70,
                impulseScore = 50,
                peak = PeakFeatures(
                    distanceFromLocalHighPct = 1.90,
                    pullbackFromHighPct = 2.00,
                    secondsSinceLocalHigh = 25
                )
            )
        )
        assertEquals(LongSignalDecider.NO_TRADE, d.label)
    }

    @Test fun weakTakerRatioRejected() {
        assertEquals(
            LongSignalDecider.NO_TRADE,
            LongSignalDecider.decide(good().copy(takerBuyRatio30s = 0.60, impulseScore = 50)).label
        )
    }

    @Test fun weakReturn15sRejectedWithoutPullbackSetup() {
        assertNotEquals(
            LongSignalDecider.LONG_CONTINUATION,
            LongSignalDecider.decide(good().copy(return15s = 0.5)).label
        )
    }

    @Test fun lateReturn5mRejected() {
        assertNotEquals(
            LongSignalDecider.LONG_CONTINUATION,
            LongSignalDecider.decide(good().copy(return5m = 3.5)).label
        )
    }

    @Test fun volumeAbsorptionVeto() {
        val d = LongSignalDecider.decide(good().copy(volumeZ30s = 30.0, takerBuyRatio30s = 0.79))
        assertNotEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
        assertTrue(d.reasons.any { it.contains("поглощение") })
    }

    @Test fun fallingCvdRejected() {
        assertEquals(
            LongSignalDecider.NO_TRADE,
            LongSignalDecider.decide(good().copy(cvdSlope = -10.0, impulseScore = 50)).label
        )
    }

    @Test fun lowerHighRejected() {
        assertNotEquals(
            LongSignalDecider.LONG_CONTINUATION,
            LongSignalDecider.decide(good().copy(peak = PeakFeatures(lowerHighDetected = true))).label
        )
    }

    @Test fun exhaustionRiskState() {
        val d = LongSignalDecider.decide(
            good().copy(
                buyerPressureDeclining = true,
                cvdSlope = -5.0,
                newHighWithoutCvdHigh = true,
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
        assertEquals(LongSignalDecider.NO_TRADE, LongSignalDecider.decide(good().copy(repeatBlocked = true)).label)
    }

    @Test fun distanceFromHighRejectsWhenNoRetestRecovery() {
        assertNotEquals(
            LongSignalDecider.LONG_CONTINUATION,
            LongSignalDecider.decide(good().copy(peak = PeakFeatures(distanceFromLocalHighPct = 1.0))).label
        )
    }
}
