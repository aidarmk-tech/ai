package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.CalibrationEval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Настраиваемый критерий «цель раньше стопа» (ТЗ 0A.24). */
class CalibrationEvalTest {

    /** Цель достигнута до стопа → успех. */
    @Test fun targetFirst() {
        val rets = listOf(30 to 0.5, 60 to 2.5, 180 to -1.5)  // +2.5% на 60с, −1.5% на 180с
        assertTrue(CalibrationEval.targetBeforeStop(rets, 2.0, 1.0, 900))
    }

    /** Стоп раньше цели → неуспех. */
    @Test fun stopFirst() {
        val rets = listOf(30 to -1.2, 60 to 2.5)  // −1.2% на 30с раньше +2.5%
        assertFalse(CalibrationEval.targetBeforeStop(rets, 2.0, 1.0, 900))
    }

    /** Более широкий стоп спасает тот же путь. */
    @Test fun widerStopHelps() {
        val rets = listOf(30 to -1.2, 60 to 2.5)
        assertTrue(CalibrationEval.targetBeforeStop(rets, 2.0, 2.0, 900))  // стоп −2% не задет
    }

    /** Короткий горизонт отсекает позднюю цель. */
    @Test fun horizonCutsLateTarget() {
        val rets = listOf(30 to 0.5, 300 to 2.5)  // цель только на 5й минуте
        assertFalse(CalibrationEval.targetBeforeStop(rets, 2.0, 1.0, 60))  // горизонт 1м
        assertTrue(CalibrationEval.targetBeforeStop(rets, 2.0, 1.0, 300))  // горизонт 5м
    }

    /** Доходности точек считаются от опорной цены. */
    @Test fun checkpointReturns_fromRef() {
        val r = CalibrationEval.checkpointReturns(100.0, 101.0, 102.0, null, null, 99.0)
        assertEquals(3, r.size)
        assertEquals(1.0, r[0].second, 1e-9)   // 101/100-1 = +1%
        assertEquals(-1.0, r[2].second, 1e-9)  // 99/100-1 = −1%
    }
}
