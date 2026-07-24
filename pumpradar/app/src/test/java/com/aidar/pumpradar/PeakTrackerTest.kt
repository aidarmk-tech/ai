package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.PeakTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Признаки вершины (item 6). */
class PeakTrackerTest {

    private val p = PeakTracker()

    @Test fun atHighDistanceZero() {
        p.update("X", 100.0, 100.0, 0)
        val r = p.update("X", 101.0, 200.0, 1000)
        assertEquals(0.0, r.features.distanceFromLocalHighPct, 1e-6)
        assertFalse(r.features.lowerHighDetected)
    }

    @Test fun pullbackGivesDistanceAndSeconds() {
        p.update("Y", 100.0, 100.0, 0)
        p.update("Y", 105.0, 500.0, 1000)
        val r = p.update("Y", 103.0, 400.0, 2000)
        assertEquals(1.905, r.features.distanceFromLocalHighPct, 0.05)
        assertEquals(1L, r.features.secondsSinceLocalHigh)
    }

    @Test fun lowerHighDetected() {
        p.update("Z", 100.0, 100.0, 0)
        p.update("Z", 105.0, 500.0, 1000)   // локальный максимум
        p.update("Z", 102.0, 400.0, 2000)   // откат 2.86%
        p.update("Z", 104.0, 450.0, 3000)   // отскок ниже максимума
        val r = p.update("Z", 103.0, 430.0, 4000) // разворот вниз
        assertTrue(r.features.lowerHighDetected)
    }

    @Test fun newHighWithoutCvdHigh() {
        p.update("W", 100.0, 1000.0, 0)     // цена ниже, но CVD высокий
        val r = p.update("W", 102.0, 500.0, 1000) // новый максимум цены, CVD ниже
        assertTrue(r.newHighWithoutCvdHigh)
    }
}
