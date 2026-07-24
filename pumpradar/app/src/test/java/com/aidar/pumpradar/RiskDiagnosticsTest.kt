package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.RiskDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Диагностика сработавших риск-условий (item 5). */
class RiskDiagnosticsTest {

    @Test fun cleanEarlyEventFiresNoExhaustion() {
        // Типичное TRIGGERED/EARLY_CLEAN: рост умеренный, поток бычий, ликвидно.
        val f = RiskDiagnostics.firedConditions(
            return5m = 1.5, return60s = 1.0, cvdSlope = 20.0, takerBuyRatio30s = 0.7,
            spreadBps = 20.0, slippagePercent = 0.05, volumeZ30s = 3.0, obi10 = 0.2
        )
        // Ни одно условие истощения не срабатывает — этим и объясняется exhaustion=0.
        assertFalse(f.getValue("EXH_late5m"))
        assertFalse(f.getValue("EXH_cvdDivergence"))
        assertFalse(f.getValue("EXH_takerLosing"))
        assertFalse(f.getValue("EXH_spreadWiden"))
        // Данные готовы.
        assertTrue(f.getValue("CONF_volumeReady"))
        assertTrue(f.getValue("CONF_depthReady"))
    }

    @Test fun lateDivergentEventFiresExhaustion() {
        val f = RiskDiagnostics.firedConditions(
            return5m = 10.0, return60s = 1.0, cvdSlope = -30.0, takerBuyRatio30s = 0.40,
            spreadBps = 80.0, slippagePercent = null, volumeZ30s = null, obi10 = null
        )
        assertTrue(f.getValue("EXH_late5m"))
        assertTrue(f.getValue("EXH_cvdDivergence"))
        assertTrue(f.getValue("EXH_takerLosing"))
        assertTrue(f.getValue("EXH_spreadWiden"))
        assertTrue(f.getValue("ENTRY_slippage"))
        assertTrue(f.getValue("ENTRY_dataRisk"))
        assertTrue(f.getValue("SLIP_insufficient"))
    }

    @Test fun nearZeroSlippageFlagged() {
        val f = RiskDiagnostics.firedConditions(
            return5m = 1.0, return60s = 1.0, cvdSlope = 5.0, takerBuyRatio30s = 0.7,
            spreadBps = 12.0, slippagePercent = 0.0, volumeZ30s = 2.0, obi10 = 0.1
        )
        assertTrue(f.getValue("SLIP_nearZero"))
        assertFalse(f.getValue("SLIP_insufficient"))
        assertFalse(f.getValue("ENTRY_spread"))   // 12 < 15
    }

    @Test fun allIdsPresent() {
        val f = RiskDiagnostics.firedConditions(null, null, null, null, null, null, null, null)
        assertEquals(RiskDiagnostics.CONDITION_IDS.toSet(), f.keys)
    }
}
