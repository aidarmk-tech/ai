package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.PumpScoreCalculator
import com.aidar.pumpradar.domain.model.Candidate
import com.aidar.pumpradar.domain.model.CandidateMetrics
import com.aidar.pumpradar.domain.model.OrderBookMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Строгий EARLY_CLEAN gate (патч §4/§7). */
class PumpScoreEarlyCleanTest {

    private val calc = PumpScoreCalculator()

    // Чистый ранний сценарий: Tier A, поток покупок, узкий спред, малый риск.
    private fun candidate() =
        Candidate("XUSDT", 1.0, 0.4, 1.5, 2.0, 0.2, 60_000_000.0, 0.5, 50.0)

    private fun metrics() =
        CandidateMetrics(
            ready = true, quoteVolume30s = 30_000.0, tradeCount30s = 50,
            tradesPerSecond = 1.6, takerBuyRatio30s = 0.70, cvd30s = 1_000.0,
            cvdSlope = 100.0, volumeZ30s = 2.5, spreadBps = 20.0, tradeGap = false
        )

    private fun ob(spread: Double = 20.0, slip: Double = 0.2) =
        OrderBookMetrics(spread, 0.30, 0.30, slip, 50_000.0, 40_000.0)

    @Test fun accepted() {
        assertEquals("EARLY_CLEAN", calc.score(candidate(), metrics(), ob()).opportunityLabel)
    }

    @Test fun rejectedBySpread() {
        assertNotEquals("EARLY_CLEAN",
            calc.score(candidate(), metrics(), ob(spread = 50.0)).opportunityLabel)
    }

    @Test fun rejectedBySlippage() {
        assertNotEquals("EARLY_CLEAN",
            calc.score(candidate(), metrics(), ob(slip = 0.9)).opportunityLabel)
    }

    @Test fun rejectedByLateStage() {
        val late = candidate().copy(return5m = 15.0)  // поздняя стадия → gate падает
        assertNotEquals("EARLY_CLEAN", calc.score(late, metrics(), ob()).opportunityLabel)
    }

    @Test fun rejectedByWeakFlow() {
        val weak = metrics().copy(takerBuyRatio30s = 0.55)  // покупки < 0.62
        assertNotEquals("EARLY_CLEAN", calc.score(candidate(), weak, ob()).opportunityLabel)
    }
}
