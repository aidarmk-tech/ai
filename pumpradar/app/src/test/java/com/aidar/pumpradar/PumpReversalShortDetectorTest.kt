package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.PumpReversalShortDetector
import com.aidar.pumpradar.domain.analyzer.ShadowStrategyInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PUMP_REVERSAL_SHORT: шорт только после подтверждённого разворота пампа. */
class PumpReversalShortDetectorTest {

    private val d = PumpReversalShortDetector()

    private fun i(
        now: Long, price: Double, cvd: Double = -2000.0, cvdSlope: Double = -50.0,
        tbr: Double? = 0.30, spread: Double = 20.0, slip: Double = 0.2,
        obi: Double? = -0.30, bid: Double = 1000.0, up: Boolean = false
    ) = ShadowStrategyInput(
        now = now, price = price, cvd = cvd, cvdSlope = cvdSlope, takerBuyRatio = tbr,
        spreadBps = spread, slippagePercent = slip, obi10 = obi,
        bidNotionalTop10 = bid, askNotionalTop10 = 1000.0, isUpImpulse = up, isDownImpulse = false
    )

    /** Полный разворот: lower high + пробой минимума + поток + исчезновение bid. */
    @Test fun confirmsAfterReversal() {
        assertFalse(d.update("X", i(0, 100.0, up = true)))
        assertFalse(d.update("X", i(1000, 105.0, up = true)))   // вершина пампа, bid=1000
        assertFalse(d.update("X", i(2000, 104.3)))              // −0.67% → REVERSAL_WATCH
        assertFalse(d.update("X", i(3000, 104.8, bid = 900.0))) // отскок < вершины → lower high
        assertTrue(d.update("X", i(4000, 104.0, bid = 500.0)))  // пробой минимума + bid ушёл → SHORT
        assertFalse(d.update("X", i(5000, 103.0, bid = 400.0))) // cooldown
    }

    /** Без исчезновения bid depth — не подтверждаем. */
    @Test fun bidDepthMustDisappear() {
        d.update("Y", i(0, 100.0, up = true))
        d.update("Y", i(1000, 105.0, up = true))
        d.update("Y", i(2000, 104.3))
        d.update("Y", i(3000, 104.8, bid = 1000.0))
        assertFalse(d.update("Y", i(4000, 104.0, bid = 1000.0)))  // bid не просел
    }

    /** Положительный OBI (bid-тяжёлый стакан) — разворота нет. */
    @Test fun positiveObiBlocks() {
        d.update("Z", i(0, 100.0, up = true))
        d.update("Z", i(1000, 105.0, up = true))
        d.update("Z", i(2000, 104.3))
        d.update("Z", i(3000, 104.8, bid = 900.0))
        assertFalse(d.update("Z", i(4000, 104.0, bid = 500.0, obi = 0.30)))
    }

    /** Прямой пробой вниз без lower high (без отскока) — не шорт. */
    @Test fun needsLowerHighNotStraightDrop() {
        d.update("W", i(0, 100.0, up = true))
        d.update("W", i(1000, 105.0, up = true))
        d.update("W", i(2000, 104.3))                 // WATCH, swingLow=104.3
        d.update("W", i(3000, 103.0, bid = 500.0))    // сразу ниже, отскока нет
        assertFalse(d.update("W", i(4000, 102.0, bid = 500.0)))
    }
}
