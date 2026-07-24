package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.ExhaustionDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustion Filter (патч §6). */
class ExhaustionDetectorTest {

    @Test fun calmNoRisk() {
        assertEquals(0, ExhaustionDetector.risk(2.0, 1.0, 100.0, 0.70, 20.0))
    }

    @Test fun lateWithDivergence_belowBlock() {
        // Поздно (5м +13% → 30) + дивергенция CVD (25) = 55 < 70.
        assertEquals(55, ExhaustionDetector.risk(13.0, 1.0, -50.0, 0.70, 20.0))
    }

    @Test fun threeEvidence_blocks() {
        // Поздно (30) + дивергенция (25) + слабые покупки (20) = 75 → блок.
        val r = ExhaustionDetector.risk(13.0, 1.0, -50.0, 0.40, 20.0)
        assertEquals(75, r)
        assertTrue(r >= 70)
    }

    @Test fun veryLate_capped() {
        // 40 + 25 + 20 + 15 = 100.
        assertEquals(100, ExhaustionDetector.risk(20.0, 1.0, -50.0, 0.40, 70.0))
    }
}
