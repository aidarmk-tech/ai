package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.ExecutableOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable-оценка исхода (ТЗ 0A.13). */
class ExecutableOutcomeTest {

    /** Издержки уменьшают MFE и делают MAE глубже. */
    @Test fun haircutReducesMfe() {
        // spread 20 bps, slippage 0.1% → entry=10+10+5+10=... exit=10+10 → haircut≈0.55%
        val hc = ExecutableOutcome.haircutPercent(20.0, 0.1)
        assertTrue(hc > 0.0)
        val execMfe = ExecutableOutcome.mfe(3.0, 20.0, 0.1)!!
        assertEquals(3.0 - hc, execMfe, 1e-9)
    }

    /** Пограничный last-price сигнал перестаёт быть успешным после издержек. */
    @Test fun marginalSignalFailsExecutable() {
        // last price: MFE +2.1%, MAE -0.9% → успешен по last price…
        assertTrue(2.1 >= 2.0 && -0.9 > -1.0)
        // …но с издержками MFE падает ниже 2% → неуспешен executable.
        assertFalse(ExecutableOutcome.successful(2.1, -0.9, 30.0, 0.2))
    }

    /** Явно сильный сигнал остаётся успешным и после издержек. */
    @Test fun strongSignalStaysSuccessful() {
        assertTrue(ExecutableOutcome.successful(6.0, -0.2, 10.0, 0.05))
    }

    /** Нет данных MFE/MAE → неуспешен. */
    @Test fun nullOutcomeNotSuccessful() {
        assertFalse(ExecutableOutcome.successful(null, -0.2, 10.0, 0.05))
    }
}
