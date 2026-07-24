package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.OutcomeClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

/** Категории исхода (патч §10.2). */
class OutcomeClassifierTest {

    @Test fun cleanWin() {
        val rets = listOf(30 to 0.4, 60 to 2.2)  // +2.2% раньше стопа
        assertEquals(OutcomeClassifier.CLEAN_WIN,
            OutcomeClassifier.category(rets, 2.5, -0.4, 10.0))
    }

    @Test fun smallWin() {
        val rets = listOf(30 to 0.3, 60 to 1.2)  // +1.2% раньше −0.75, но не +2
        assertEquals(OutcomeClassifier.SMALL_WIN,
            OutcomeClassifier.category(rets, 1.4, -0.5, 10.0))
    }

    @Test fun whipsaw() {
        // Стоп задет раньше цели, но размах большой в обе стороны → пила.
        val rets = listOf(30 to -1.6, 60 to 2.5)
        assertEquals(OutcomeClassifier.WHIPSAW,
            OutcomeClassifier.category(rets, 2.5, -1.8, 20.0))
    }

    @Test fun lowLiquidity() {
        val rets = listOf(30 to 0.2, 60 to 0.3)  // без продолжения, но широкий спред
        assertEquals(OutcomeClassifier.LOW_LIQUIDITY_MOVE,
            OutcomeClassifier.category(rets, 0.4, -0.6, 80.0))
    }

    @Test fun noContinuation() {
        val rets = listOf(30 to 0.2, 60 to 0.1)
        assertEquals(OutcomeClassifier.NO_CONTINUATION,
            OutcomeClassifier.category(rets, 0.3, -0.4, 15.0))
    }

    @Test fun dataIncomplete() {
        assertEquals(OutcomeClassifier.DATA_INCOMPLETE,
            OutcomeClassifier.category(emptyList(), 2.0, -0.2, 10.0))
    }
}
