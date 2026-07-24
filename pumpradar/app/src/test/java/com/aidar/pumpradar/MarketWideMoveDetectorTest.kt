package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.MarketWideMoveDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Фильтр общерыночного движения (патч §9). */
class MarketWideMoveDetectorTest {

    @Test fun uniqueMoveNoRisk() {
        // Рынок спокоен (breadth 0.4), монета сама по себе растёт.
        assertEquals(0, MarketWideMoveDetector.risk(2.0, 0.1, 0.40))
    }

    @Test fun marketWide_blocks() {
        // Растёт весь рынок (breadth 0.90), монета не отрывается (residual −0.1).
        val r = MarketWideMoveDetector.risk(0.8, 0.9, 0.90)
        assertEquals(70, r)   // 40 (breadth) + 30 (residual<=0)
        assertTrue(r >= 70)
    }

    @Test fun strongResidualAllowed() {
        // Даже при широком рынке сильный отрыв (residual +2.5) не блокирует.
        assertEquals(40, MarketWideMoveDetector.risk(3.0, 0.5, 0.90))
    }

    @Test fun moderateBreadthSmallResidual() {
        // breadth 0.80 (25) + residual 0.1<0.3 (15) = 40.
        assertEquals(40, MarketWideMoveDetector.risk(1.0, 0.9, 0.80))
    }
}
