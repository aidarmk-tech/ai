package com.aidar.pumpradar

import com.aidar.pumpradar.domain.model.LiquidityTier
import org.junit.Assert.assertEquals
import org.junit.Test

/** Границы тиров ликвидности (ТЗ 0A.9). */
class LiquidityTierTest {

    @Test fun boundaries() {
        assertEquals(LiquidityTier.A, LiquidityTier.of(50_000_000.0))
        assertEquals(LiquidityTier.A, LiquidityTier.of(120_000_000.0))
        assertEquals(LiquidityTier.B, LiquidityTier.of(49_999_999.0))
        assertEquals(LiquidityTier.B, LiquidityTier.of(10_000_000.0))
        assertEquals(LiquidityTier.C, LiquidityTier.of(9_999_999.0))
        assertEquals(LiquidityTier.C, LiquidityTier.of(1_000_000.0))
        assertEquals(LiquidityTier.D, LiquidityTier.of(999_999.0))
        assertEquals(LiquidityTier.D, LiquidityTier.of(0.0))
    }
}
