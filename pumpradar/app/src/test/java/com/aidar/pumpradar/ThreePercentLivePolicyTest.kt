package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.ThreePercentLivePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreePercentLivePolicyTest {

    @Test fun strongFlowDoesNotRequestExit() {
        val a = ThreePercentLivePolicy.assess(
            ThreePercentLivePolicy.Input(
                currentReturnPercent = 1.5,
                maxReturnPercent = 1.8,
                takerBuyRatio5s = 0.65,
                takerBuyRatio15s = 0.70,
                takerBuyRatio30s = 0.72,
                cvdSlope = 10.0
            )
        )
        assertFalse(a.flowWeak)
    }

    @Test fun weakFlowNeedsConfirmedDrawdown() {
        val noDrawdown = ThreePercentLivePolicy.assess(
            ThreePercentLivePolicy.Input(
                currentReturnPercent = 1.75,
                maxReturnPercent = 1.8,
                takerBuyRatio5s = 0.40,
                takerBuyRatio15s = 0.50,
                takerBuyRatio30s = 0.55,
                cvdSlope = -1.0
            )
        )
        assertFalse(noDrawdown.flowWeak)

        val confirmed = ThreePercentLivePolicy.assess(
            ThreePercentLivePolicy.Input(
                currentReturnPercent = 1.2,
                maxReturnPercent = 1.8,
                takerBuyRatio5s = 0.40,
                takerBuyRatio15s = 0.50,
                takerBuyRatio30s = 0.55,
                cvdSlope = -1.0
            )
        )
        assertTrue(confirmed.flowWeak)
        assertTrue(confirmed.reasons.isNotEmpty())
    }
}
