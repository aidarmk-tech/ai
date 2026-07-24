package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.ExecutablePathEval
import com.aidar.pumpradar.domain.analyzer.ExecutablePathEval.PathPoint
import com.aidar.pumpradar.domain.analyzer.TradeSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Исполнимая оценка по секундной траектории bid/ask (порядок уровней, сторона,
 *  спред, проскальзывание, задержка реакции). */
class ExecutablePathEvalTest {

    /** Ровный спред 10 bps вокруг mid; параметр — mid-цена. */
    private fun pt(tMs: Long, mid: Double): PathPoint {
        val half = mid * 0.0005   // спред 10 bps
        return PathPoint(tMs, bid = mid - half, ask = mid + half)
    }

    /** LONG: рост цели достигнут раньше стопа. */
    @Test fun longTargetBeforeStop() {
        val path = listOf(
            pt(0, 100.0), pt(1000, 100.5), pt(2000, 101.2), pt(3000, 102.5)
        )
        val r = ExecutablePathEval.evaluate(
            path, TradeSide.LONG, targetPercent = 1.0, stopPercent = 1.0,
            reactionMs = 500, horizonMs = 60_000
        )
        assertEquals(ExecutablePathEval.Hit.TARGET, r.hit)
        assertTrue(r.target)
    }

    /** LONG: спред делает вход по ask дороже — маленькая цель не берётся. */
    @Test fun longSpreadCostBlocksTinyTarget() {
        // Вход на pt(500)=100.0 (ask ~100.05), выход на pt(1000)=100.2 (bid ~100.15).
        val path = listOf(pt(0, 100.0), pt(500, 100.0), pt(1000, 100.2))
        val r = ExecutablePathEval.evaluate(
            path, TradeSide.LONG, targetPercent = 0.2, stopPercent = 5.0,
            reactionMs = 500, horizonMs = 60_000
        )
        // (100.15/100.05−1) ≈ +0.10% < 0.2% → цель не взята из-за спреда.
        assertEquals(ExecutablePathEval.Hit.NONE, r.hit)
    }

    /** SHORT: падение цели достигнуто раньше стопа (зеркально). */
    @Test fun shortTargetBeforeStop() {
        val path = listOf(
            pt(0, 100.0), pt(1000, 99.5), pt(2000, 98.8), pt(3000, 97.5)
        )
        val r = ExecutablePathEval.evaluate(
            path, TradeSide.SHORT, targetPercent = 1.0, stopPercent = 1.0,
            reactionMs = 500, horizonMs = 60_000
        )
        assertEquals(ExecutablePathEval.Hit.TARGET, r.hit)
    }

    /** SHORT: рост раньше падения → стоп. */
    @Test fun shortStopFirst() {
        val path = listOf(pt(0, 100.0), pt(1000, 101.5), pt(2000, 98.0))
        val r = ExecutablePathEval.evaluate(
            path, TradeSide.SHORT, targetPercent = 1.0, stopPercent = 1.0,
            reactionMs = 500, horizonMs = 60_000
        )
        assertEquals(ExecutablePathEval.Hit.STOP, r.hit)
    }

    /** Задержка реакции сдвигает точку входа: при 5с вход уже после всплеска. */
    @Test fun reactionLatencyChangesEntry() {
        // Всплеск +2% в первую секунду, затем откат к 0.
        val path = listOf(
            pt(0, 100.0), pt(1000, 102.0), pt(2000, 100.1), pt(5000, 100.1), pt(6000, 100.1)
        )
        // Быстрая реакция (0.5с): вход ~100.05, ловит +2%.
        val fast = ExecutablePathEval.evaluate(
            path, TradeSide.LONG, 1.0, 1.0, reactionMs = 500, horizonMs = 60_000
        )
        assertEquals(ExecutablePathEval.Hit.TARGET, fast.hit)
        // Медленная реакция (5с): вход уже после отката, цель не берётся.
        val slow = ExecutablePathEval.evaluate(
            path, TradeSide.LONG, 1.0, 1.0, reactionMs = 5000, horizonMs = 60_000
        )
        assertEquals(ExecutablePathEval.Hit.NONE, slow.hit)
    }

    /** Пустая/слишком короткая траектория → нет входа. */
    @Test fun noEntryWhenNoPointAfterReaction() {
        val path = listOf(pt(0, 100.0))  // нет точки после 500мс
        val r = ExecutablePathEval.evaluate(
            path, TradeSide.LONG, 1.0, 1.0, reactionMs = 500, horizonMs = 60_000
        )
        assertEquals(ExecutablePathEval.Hit.NO_ENTRY, r.hit)
        assertNull(r.timeToHitMs)
    }
}
