package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.ThreePercentPlanEvaluator
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreePercentPlanEvaluatorTest {

    @Test fun targetThreeAfterProtection() {
        val r = ThreePercentPlanEvaluator.evaluate(
            listOf(0L to 0.0, 10_000L to 1.1, 30_000L to 2.0, 60_000L to 3.1)
        )
        assertEquals(ThreePercentPlanEvaluator.Result.TARGET_3, r.result)
        assertEquals(10_000L, r.activationTimeMs)
        assertEquals(60_000L, r.targetTimeMs)
        assertEquals(2.6, r.grossReturnPercent!!, 1e-9)
    }

    @Test fun stopBeforeProtection() {
        val r = ThreePercentPlanEvaluator.evaluate(
            listOf(0L to 0.0, 5_000L to -0.8, 20_000L to 1.2)
        )
        assertEquals(ThreePercentPlanEvaluator.Result.STOP_BEFORE_PROTECTION, r.result)
        assertEquals(-0.75, r.grossReturnPercent!!, 1e-9)
    }

    @Test fun protectedExitAfterOnePercent() {
        val r = ThreePercentPlanEvaluator.evaluate(
            listOf(0L to 0.0, 10_000L to 1.1, 30_000L to 1.8, 50_000L to 0.1)
        )
        assertEquals(ThreePercentPlanEvaluator.Result.PROTECTED_EXIT, r.result)
        assertEquals(0.32, r.grossReturnPercent!!, 1e-9)
    }

    @Test fun horizonExitBlendsPartialAndRemainder() {
        val r = ThreePercentPlanEvaluator.evaluate(
            listOf(0L to 0.0, 10_000L to 1.0, 300_000L to 2.0)
        )
        assertEquals(ThreePercentPlanEvaluator.Result.HORIZON_EXIT, r.result)
        assertEquals(1.8, r.grossReturnPercent!!, 1e-9)
    }
}
