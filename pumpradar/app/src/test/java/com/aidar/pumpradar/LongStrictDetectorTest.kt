package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.LongStrictDetector
import com.aidar.pumpradar.domain.analyzer.ShadowStrategyInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LONG_STRICT: строгий гейт лонга (item 7). */
class LongStrictDetectorTest {

    private val d = LongStrictDetector()

    private fun i(
        now: Long, tbr: Double? = 0.90, ret60: Double? = 1.5, cvd: Double = 2000.0,
        spread: Double = 20.0, slip: Double = 0.2, up: Boolean = true
    ) = ShadowStrategyInput(
        now = now, price = 100.0, cvd = cvd, cvdSlope = 10.0,
        takerBuyRatio = tbr, takerBuyRatio15s = tbr, return60s = ret60,
        spreadBps = spread, slippagePercent = slip, obi10 = 0.2,
        bidNotionalTop10 = 1000.0, askNotionalTop10 = 1000.0,
        isUpImpulse = up, isDownImpulse = false
    )

    @Test fun confirmsWhenStrict() {
        assertTrue(d.update("X", i(0)))
    }

    @Test fun cooldownBlocksImmediateRefire() {
        assertTrue(d.update("Y", i(0)))
        assertFalse(d.update("Y", i(1000)))          // в пределах cooldown
    }

    @Test fun weakBuysRejected() {
        assertFalse(d.update("A", i(0, tbr = 0.80)))  // < 0.85
    }

    @Test fun smallReturnRejected() {
        assertFalse(d.update("B", i(0, ret60 = 0.5))) // < 1%
    }

    @Test fun negativeCvdRejected() {
        assertFalse(d.update("C", i(0, cvd = -100.0)))
    }

    @Test fun wideSpreadRejected() {
        assertFalse(d.update("D", i(0, spread = 80.0)))
    }
}
