package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.StrategyLab
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Стратегии Strategy Lab (патч §13). */
class StrategyLabTest {

    private fun f(
        label: String? = null, tier: String? = null,
        risk: Int? = null, conf: Int? = null, imp: Int = 50
    ) = StrategyLab.Features(label, tier, risk, conf, imp)

    private fun strat(id: String) = StrategyLab.STRATEGIES.first { it.id == id }

    @Test fun championTriggersOnNotifyLabels() {
        val s = strat("S1_CURRENT")
        assertTrue(s.triggers(f(label = "EARLY_CLEAN")))
        assertTrue(s.triggers(f(label = "RETEST_CONFIRMED")))
        assertFalse(s.triggers(f(label = "STRONG_BUT_LATE")))
    }

    @Test fun earlyCleanOnly() {
        val s = strat("S2_EARLY_CLEAN")
        assertTrue(s.triggers(f(label = "EARLY_CLEAN")))
        assertFalse(s.triggers(f(label = "RETEST_CONFIRMED")))
    }

    @Test fun highLiquidityRequiresTier() {
        val s = strat("S4_HIGH_LIQUIDITY")
        assertTrue(s.triggers(f(label = "EARLY_CLEAN", tier = "A")))
        assertFalse(s.triggers(f(label = "EARLY_CLEAN", tier = "C")))
    }

    @Test fun flowFirst() {
        val s = strat("S5_FLOW_FIRST")
        assertTrue(s.triggers(f(conf = 80, risk = 30)))
        assertFalse(s.triggers(f(conf = 70, risk = 30)))  // confidence < 75
        assertFalse(s.triggers(f(conf = 80, risk = 50)))  // entryRisk > 40
    }

    @Test fun lowEntryRisk() {
        val s = strat("S6_LOW_ENTRY_RISK")
        assertTrue(s.triggers(f(risk = 20, conf = 80)))
        assertFalse(s.triggers(f(risk = 40, conf = 80)))  // risk > 25
        assertFalse(s.triggers(f(risk = 20, conf = 60)))  // conf < 70
    }
}
