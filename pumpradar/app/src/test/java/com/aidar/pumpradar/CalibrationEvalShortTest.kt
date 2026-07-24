package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.CalibrationEval
import com.aidar.pumpradar.domain.analyzer.TradeSide
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Зеркальный (SHORT) критерий «цель раньше стопа». */
class CalibrationEvalShortTest {

    /** SHORT: падение −target% раньше роста +stop% → успех. */
    @Test fun shortTargetFirst() {
        val rets = listOf(30 to -0.5, 60 to -2.5, 180 to 1.5)  // −2.5% на 60с раньше +1.5%
        assertTrue(CalibrationEval.targetBeforeStop(rets, 2.0, 1.0, 900, TradeSide.SHORT))
    }

    /** SHORT: рост +stop% раньше падения −target% → неуспех. */
    @Test fun shortStopFirst() {
        val rets = listOf(30 to 1.2, 60 to -2.5)  // +1.2% (стоп) на 30с раньше −2.5%
        assertFalse(CalibrationEval.targetBeforeStop(rets, 2.0, 1.0, 900, TradeSide.SHORT))
    }

    /** Тот же ряд: LONG и SHORT дают противоположные вердикты. */
    @Test fun sidesAreMirror() {
        val rets = listOf(30 to 0.5, 60 to 2.5)  // чистый рост
        assertTrue(CalibrationEval.targetBeforeStop(rets, 2.0, 1.0, 900, TradeSide.LONG))
        assertFalse(CalibrationEval.targetBeforeStop(rets, 2.0, 1.0, 900, TradeSide.SHORT))
    }

    /** LONG по умолчанию (без указания стороны) — обратная совместимость. */
    @Test fun defaultIsLong() {
        val rets = listOf(30 to 2.5)
        assertTrue(CalibrationEval.targetBeforeStop(rets, 2.0, 1.0, 900))
    }
}
