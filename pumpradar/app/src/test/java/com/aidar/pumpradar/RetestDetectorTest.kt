package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.RetestDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RETEST state machine (патч §5). */
class RetestDetectorTest {

    private val d = RetestDetector()

    private fun input(
        now: Long, price: Double, cvd: Double = 2000.0, cvdSlope: Double = 50.0,
        tbr: Double? = 0.70, spread: Double = 20.0, slip: Double = 0.20,
        conf: Int = 80, risk: Int = 20, impulse: Boolean = true
    ) = RetestDetector.Input(now, price, cvd, cvdSlope, tbr, spread, slip, conf, risk, impulse)

    @Test fun confirmsAfterControlledPullback() {
        assertFalse(d.update("X", input(0, 100.0, cvd = 1000.0)))      // импульс стартовал
        assertFalse(d.update("X", input(1000, 105.0, cvd = 2000.0)))   // новый максимум
        assertFalse(d.update("X", input(2000, 102.5, cvd = 1800.0)))   // откат 2.38% → PULLBACK
        assertFalse(d.update("X", input(4000, 103.0, cvd = 1900.0)))   // ещё рано (dur<5с, нет пробоя)
        assertTrue(d.update("X", input(8000, 106.0, cvd = 2500.0)))    // пробой + dur 6с → RETEST
        assertFalse(d.update("X", input(9000, 107.0, cvd = 2600.0)))   // cooldown
    }

    @Test fun tooDeepPullbackResets() {
        d.update("Y", input(0, 100.0, cvd = 1000.0))
        d.update("Y", input(1000, 105.0, cvd = 2000.0))
        d.update("Y", input(2000, 102.5, cvd = 1800.0))
        d.update("Y", input(3000, 96.0, cvd = 1500.0))                 // −8.5% → срыв, IDLE
        assertFalse(d.update("Y", input(9000, 106.0, cvd = 2500.0)))
    }

    @Test fun reclaimTooSoonNotConfirmed() {
        d.update("Z", input(0, 100.0, cvd = 1000.0))
        d.update("Z", input(1000, 105.0, cvd = 2000.0))
        d.update("Z", input(2000, 102.5, cvd = 1800.0))               // откат стартовал
        assertFalse(d.update("Z", input(2500, 106.0, cvd = 2500.0)))  // dur 0.5с < 5с
    }

    @Test fun weakBuysNotConfirmed() {
        d.update("W", input(0, 100.0, cvd = 1000.0))
        d.update("W", input(1000, 105.0, cvd = 2000.0))
        d.update("W", input(2000, 102.5, cvd = 1800.0))
        assertFalse(d.update("W", input(8000, 106.0, cvd = 2500.0, tbr = 0.50))) // покупки < 0.60
    }
}
