package com.aidar.pumpradar

import com.aidar.pumpradar.core.math.MathUtils
import com.aidar.pumpradar.core.math.MathUtils.validMarketNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathUtilsTest {

    @Test fun returnPercent_basic() {
        assertEquals(10.0, MathUtils.returnPercent(110.0, 100.0)!!, 1e-9)
        assertEquals(-5.0, MathUtils.returnPercent(95.0, 100.0)!!, 1e-9)
    }

    @Test fun returnPercent_zeroPast_null() {
        assertNull(MathUtils.returnPercent(100.0, 0.0))
    }

    @Test fun safeDivide_guards() {
        assertNull(MathUtils.safeDivide(1.0, 0.0))
        assertNull(MathUtils.safeDivide(Double.NaN, 1.0))
        assertEquals(2.0, MathUtils.safeDivide(4.0, 2.0)!!, 1e-9)
    }

    @Test fun linearScore_boundaries() {
        assertEquals(0.0, MathUtils.linearScore(0.2, 0.2, 1.2, 8.0), 1e-9)
        assertEquals(8.0, MathUtils.linearScore(1.2, 0.2, 1.2, 8.0), 1e-9)
        assertEquals(4.0, MathUtils.linearScore(0.7, 0.2, 1.2, 8.0), 1e-9)
        // за пределами — насыщение
        assertEquals(8.0, MathUtils.linearScore(5.0, 0.2, 1.2, 8.0), 1e-9)
        assertEquals(0.0, MathUtils.linearScore(-1.0, 0.2, 1.2, 8.0), 1e-9)
    }

    @Test fun median_oddEven() {
        assertEquals(2.0, MathUtils.median(listOf(1.0, 3.0, 2.0)), 1e-9)
        assertEquals(2.5, MathUtils.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
    }

    @Test fun mad_basic() {
        // median=3, deviations=[2,1,0,1,2], median=1
        assertEquals(1.0, MathUtils.mad(listOf(1.0, 2.0, 3.0, 4.0, 5.0)), 1e-9)
    }

    @Test fun robustZ_madZero_fallback() {
        assertEquals(0.0, MathUtils.robustZScore(10.0, List(12) { 5.0 }), 1e-9)
    }

    @Test fun robustZ_detectsSpike() {
        val history = listOf(1.0, 2.0, 1.5, 2.0, 1.0, 1.8, 2.2, 1.9, 1.1, 2.0)
        assertTrue(MathUtils.robustZScore(10.0, history) > 3.0)
    }

    @Test fun clampScore_range() {
        assertEquals(100, MathUtils.clampScore(140.0))
        assertEquals(0, MathUtils.clampScore(-20.0))
        assertEquals(67, MathUtils.clampScore(67.4))
    }

    @Test fun validMarketNumber() {
        assertTrue(5.0.validMarketNumber())
        assertFalse((-1.0).validMarketNumber())
        assertFalse(Double.NaN.validMarketNumber())
        assertFalse(Double.POSITIVE_INFINITY.validMarketNumber())
    }
}
