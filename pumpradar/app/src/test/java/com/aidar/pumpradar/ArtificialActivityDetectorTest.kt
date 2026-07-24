package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.ArtificialActivityDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Фильтр искусственной активности (патч §8). */
class ArtificialActivityDetectorTest {

    @Test fun calm() {
        assertEquals(0, ArtificialActivityDetector.risk(50, 0.20, 0.40, 0.10, 1.0))
    }

    @Test fun manyTinyTradesFlatPrice() {
        // Много сделок при стоячей цене (30) + мелочь (25) = 55.
        assertEquals(55, ArtificialActivityDetector.risk(150, 0.20, 0.40, 0.70, 0.10))
    }

    @Test fun heavyManipulationPattern() {
        val r = ArtificialActivityDetector.risk(150, 0.60, 0.80, 0.70, 0.10)
        assertEquals(90, r)   // 30+25+20+15
        assertTrue(r >= 85)
    }

    @Test fun nullDistributionSafe() {
        assertEquals(0, ArtificialActivityDetector.risk(50, null, null, null, 1.0))
    }
}
