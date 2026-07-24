package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.OrderBookAnalyzer
import com.aidar.pumpradar.domain.model.DepthLevel
import com.aidar.pumpradar.domain.model.PartialDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Кривая проскальзывания 10/50/100 USDT + нехватка глубины + стоимость спреда. */
class OrderBookSlippageTest {

    private val a = OrderBookAnalyzer()

    private fun depth() = PartialDepth(
        symbol = "X",
        bids = listOf(DepthLevel(99.0, 1.0), DepthLevel(98.5, 2.0)),
        // Совокупная глубина ask ≈ 40.1 USDT: на 100 USDT не хватит.
        asks = listOf(DepthLevel(100.0, 0.2), DepthLevel(100.5, 0.2)),
        lastUpdateId = 1
    )

    @Test fun probesAndShortfall() {
        a.onDepth(depth())
        val m = a.metrics("X", 10.0) ?: error("metrics null")

        // 10 USDT — исполняется на первом уровне, проскальзывание 0, без нехватки.
        val p10 = m.slippage10!!
        assertEquals(0.0, p10.slippagePercent!!, 1e-9)
        assertEquals(0.0, p10.shortfallUsdt, 1e-9)

        // 100 USDT — глубины не хватает: shortfall ≈ 59.9, исполнено ≈ 40.1.
        val p100 = m.slippage100!!
        assertEquals(40.1, p100.filledUsdt, 0.2)
        assertEquals(59.9, p100.shortfallUsdt, 0.2)
        // Проскальзывание по исполненной части ≈ 0.25% — не схлопнуто в ноль.
        assertNotNull(p100.slippagePercent)
        assertEquals(0.25, p100.slippagePercent!!, 0.05)
        assertTrue(p100.slippagePercent!! > 0.0)

        // Стоимость спреда отдельно: (100−99)/99.5·100 ≈ 1.005%.
        assertEquals(1.005, m.spreadCostPercent, 0.01)
    }
}
