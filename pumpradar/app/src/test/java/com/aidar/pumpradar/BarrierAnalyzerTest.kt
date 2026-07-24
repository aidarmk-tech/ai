package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.BarrierAnalyzer
import com.aidar.pumpradar.domain.analyzer.BarrierAnalyzer.FirstBarrier
import com.aidar.pumpradar.domain.analyzer.TradeSide
import org.junit.Assert.assertEquals
import org.junit.Test

/** Порядок достижения барьеров цель/стоп. */
class BarrierAnalyzerTest {

    @Test fun longTargetFirst() {
        val s = listOf(1000L to 0.5, 2000L to 1.2)
        val b = BarrierAnalyzer.analyze(s, TradeSide.LONG, 1.0, 0.75)
        assertEquals(FirstBarrier.TARGET_FIRST, b.first)
        assertEquals(2000L, b.targetTimeMs)
        assertEquals(null, b.stopTimeMs)
    }

    @Test fun longStopFirst() {
        val s = listOf(1000L to -0.8, 2000L to 1.2)
        val b = BarrierAnalyzer.analyze(s, TradeSide.LONG, 1.0, 0.75)
        assertEquals(FirstBarrier.STOP_FIRST, b.first)
        assertEquals(1000L, b.stopTimeMs)
        assertEquals(2000L, b.targetTimeMs)
    }

    @Test fun neitherWhenNoCross() {
        val s = listOf(1000L to 0.2, 2000L to -0.3)
        val b = BarrierAnalyzer.analyze(s, TradeSide.LONG, 1.0, 0.75)
        assertEquals(FirstBarrier.NEITHER, b.first)
    }

    @Test fun emptyIsDataIncomplete() {
        val b = BarrierAnalyzer.analyze(emptyList(), TradeSide.LONG, 1.0, 0.75)
        assertEquals(FirstBarrier.DATA_INCOMPLETE, b.first)
    }

    /** Оба барьера пересечены в одном временном интервале → неоднозначно. */
    @Test fun bothSameInterval() {
        val s = listOf(1000L to 1.5, 1000L to -1.0)
        val b = BarrierAnalyzer.analyze(s, TradeSide.LONG, 1.0, 0.75)
        assertEquals(FirstBarrier.BOTH_SAME_INTERVAL, b.first)
    }

    @Test fun shortMirrorTargetFirst() {
        // SHORT: цель = падение −1%, стоп = рост +0.75%.
        val s = listOf(1000L to -0.5, 2000L to -1.2)
        val b = BarrierAnalyzer.analyze(s, TradeSide.SHORT, 1.0, 0.75)
        assertEquals(FirstBarrier.TARGET_FIRST, b.first)
        assertEquals(2000L, b.targetTimeMs)
    }

    @Test fun shortMirrorStopFirst() {
        val s = listOf(1000L to 0.9, 2000L to -1.2)
        val b = BarrierAnalyzer.analyze(s, TradeSide.SHORT, 1.0, 0.75)
        assertEquals(FirstBarrier.STOP_FIRST, b.first)
        assertEquals(1000L, b.stopTimeMs)
    }
}
