package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.DumpReboundLongDetector
import com.aidar.pumpradar.domain.analyzer.ShadowStrategyInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DUMP_REBOUND_LONG: лонг только после прекращения продавливания и восстановления. */
class DumpReboundLongDetectorTest {

    private val d = DumpReboundLongDetector()

    private fun i(
        now: Long, price: Double, cvd: Double = 1500.0, cvdSlope: Double = 40.0,
        tbr: Double? = 0.60, obi: Double? = 0.30, spread: Double = 20.0, slip: Double = 0.2,
        down: Boolean = false
    ) = ShadowStrategyInput(
        now = now, price = price, cvd = cvd, cvdSlope = cvdSlope, takerBuyRatio = tbr,
        spreadBps = spread, slippagePercent = slip, obi10 = obi,
        bidNotionalTop10 = 1000.0, askNotionalTop10 = 1000.0,
        isUpImpulse = false, isDownImpulse = down
    )

    /** Падение → стабилизация → подтверждённое восстановление покупателей. */
    @Test fun confirmsAfterRecovery() {
        assertFalse(d.update("X", i(0, 100.0, cvd = -1000.0, cvdSlope = -50.0, tbr = 0.30, down = true)))
        assertFalse(d.update("X", i(1000, 95.0, cvd = -1200.0, cvdSlope = -30.0, tbr = 0.35)))  // минимум
        assertFalse(d.update("X", i(2000, 95.4, cvd = 200.0, cvdSlope = 30.0)))                 // стабилизация → BASING
        assertFalse(d.update("X", i(4000, 95.6, cvd = 800.0)))                                  // рано (dur<5с)
        assertTrue(d.update("X", i(8000, 95.7, cvd = 1500.0)))                                  // восстановление → LONG
        assertFalse(d.update("X", i(9000, 96.0)))                                               // cooldown
    }

    /** Новый минимум в фазе basing — продажи не закончились. */
    @Test fun newLowReturnsToDump() {
        d.update("Y", i(0, 100.0, cvd = -1000.0, cvdSlope = -50.0, tbr = 0.30, down = true))
        d.update("Y", i(1000, 95.0, cvd = -1200.0, cvdSlope = -30.0, tbr = 0.35))
        d.update("Y", i(2000, 95.4, cvd = 200.0, cvdSlope = 30.0))    // BASING
        d.update("Y", i(3000, 94.0, cvd = -500.0, cvdSlope = -20.0))  // новый минимум → DUMP
        assertFalse(d.update("Y", i(8000, 94.3, cvd = 1500.0)))       // dur пересчитан, рано
    }

    /** Отрицательный OBI (ask-тяжёлый) — восстановления нет. */
    @Test fun negativeObiBlocks() {
        d.update("Z", i(0, 100.0, cvd = -1000.0, cvdSlope = -50.0, tbr = 0.30, down = true))
        d.update("Z", i(1000, 95.0, cvd = -1200.0, cvdSlope = -30.0, tbr = 0.35))
        d.update("Z", i(2000, 95.4, cvd = 200.0, cvdSlope = 30.0))
        assertFalse(d.update("Z", i(8000, 95.7, cvd = 1500.0, obi = -0.30)))
    }
}
