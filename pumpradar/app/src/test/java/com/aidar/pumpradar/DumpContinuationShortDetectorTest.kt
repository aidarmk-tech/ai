package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.DumpContinuationShortDetector
import com.aidar.pumpradar.domain.analyzer.ShadowStrategyInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DUMP_CONTINUATION_SHORT: зеркальный LONG-анализ нисходящего импульса. */
class DumpContinuationShortDetectorTest {

    private val d = DumpContinuationShortDetector()

    private fun i(
        now: Long, price: Double, cvd: Double = -2000.0, cvdSlope: Double = -50.0,
        tbr: Double? = 0.30, spread: Double = 20.0, slip: Double = 0.2, down: Boolean = false
    ) = ShadowStrategyInput(
        now = now, price = price, cvd = cvd, cvdSlope = cvdSlope, takerBuyRatio = tbr,
        spreadBps = spread, slippagePercent = slip, obi10 = -0.2,
        bidNotionalTop10 = 1000.0, askNotionalTop10 = 1000.0,
        isUpImpulse = false, isDownImpulse = down
    )

    /** Импульс вниз → отскок → возобновление падения. */
    @Test fun confirmsAfterControlledBounce() {
        assertFalse(d.update("X", i(0, 100.0, cvd = -1000.0, down = true)))  // импульс вниз
        assertFalse(d.update("X", i(1000, 95.0, cvd = -2000.0)))             // новый минимум
        assertFalse(d.update("X", i(2000, 97.5, cvd = -1500.0)))             // отскок 2.6% → BOUNCE
        assertFalse(d.update("X", i(4000, 96.5, cvd = -1500.0)))             // рано (dur<5с, нет пробоя)
        assertTrue(d.update("X", i(8000, 94.0, cvd = -2500.0)))              // пробой + dur 6с → SHORT
        assertFalse(d.update("X", i(9000, 93.0, cvd = -2600.0)))             // cooldown
    }

    /** Слишком глубокий отскок — срыв. */
    @Test fun tooDeepBounceResets() {
        d.update("Y", i(0, 100.0, cvd = -1000.0, down = true))
        d.update("Y", i(1000, 95.0, cvd = -2000.0))
        d.update("Y", i(2000, 99.5, cvd = -1200.0))     // +4.7% отскок → срыв, IDLE
        assertFalse(d.update("Y", i(8000, 94.0, cvd = -2500.0)))
    }

    /** Слабые продажи (taker покупки перевешивают) — не подтверждаем. */
    @Test fun weakSellsNotConfirmed() {
        d.update("W", i(0, 100.0, cvd = -1000.0, down = true))
        d.update("W", i(1000, 95.0, cvd = -2000.0))
        d.update("W", i(2000, 97.5, cvd = -1500.0))
        assertFalse(d.update("W", i(8000, 94.0, cvd = -2500.0, tbr = 0.55)))  // taker sell не доминирует
    }
}
