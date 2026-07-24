package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.SignalGovernor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ограничение повторных сигналов (item 10). */
class SignalGovernorTest {

    private val g = SignalGovernor()
    private val fiveMin = 5 * 60_000L

    @Test fun allowsFirstSignal() {
        assertTrue(g.allow("X"))
    }

    @Test fun blocksRepeatUntilNormalPeriodElapses() {
        g.recordSignal("X", 0)
        assertFalse(g.allow("X"))                       // сразу после сигнала — блок
        // Рынок ещё повышен — таймер нормального не идёт.
        g.observe("X", 60_000, elevated = true, repeatNormalMs = fiveMin)
        assertFalse(g.allow("X"))
        // Рынок стал нормальным, но 5 минут ещё не прошло.
        g.observe("X", 120_000, elevated = false, repeatNormalMs = fiveMin)
        assertFalse(g.allow("X"))
        // Прошло ≥5 минут непрерывно нормального рынка → разрешено снова.
        g.observe("X", 120_000 + fiveMin, elevated = false, repeatNormalMs = fiveMin)
        assertTrue(g.allow("X"))
    }

    @Test fun elevatedResetsNormalTimer() {
        g.recordSignal("Y", 0)
        g.observe("Y", 100_000, elevated = false, repeatNormalMs = fiveMin)  // таймер пошёл
        g.observe("Y", 200_000, elevated = true, repeatNormalMs = fiveMin)   // снова импульс — сброс
        g.observe("Y", 200_000 + fiveMin - 1, elevated = false, repeatNormalMs = fiveMin)
        assertFalse(g.allow("Y"))                       // с последнего сброса ещё не 5 минут
    }
}
