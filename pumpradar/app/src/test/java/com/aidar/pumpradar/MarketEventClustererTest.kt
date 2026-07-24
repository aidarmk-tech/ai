package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.MarketEventClusterer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Кластеризация сигналов в одно событие (патч §12). */
class MarketEventClustererTest {

    private val min = 60_000L

    @Test fun adjacentSignalsSameCluster() {
        val c = MarketEventClusterer()
        var n = 0
        val ids = { "c${n++}" }
        val a = c.assign("X", 0, 50, "s1", ids)
        val b = c.assign("X", 5 * min, 60, "s2", ids)   // 5 мин < 20 → тот же кластер
        assertEquals(a.id, b.id)
        assertEquals(2, b.count)
        assertEquals(60, b.peakImpulse)           // пик обновился
        assertEquals("s1", b.firstSignalId)       // первый сигнал сохранён
    }

    @Test fun gapStartsNewCluster() {
        val c = MarketEventClusterer()
        var n = 0
        val ids = { "c${n++}" }
        val a = c.assign("X", 0, 50, "s1", ids)
        val b = c.assign("X", 25 * min, 40, "s2", ids) // 25 мин > 20 → новый кластер
        assertNotEquals(a.id, b.id)
        assertEquals(1, b.count)
    }

    @Test fun differentSymbolsDifferentClusters() {
        val c = MarketEventClusterer()
        var n = 0
        val ids = { "c${n++}" }
        val a = c.assign("X", 0, 50, "s1", ids)
        val b = c.assign("Y", min, 50, "s2", ids)
        assertNotEquals(a.id, b.id)
    }
}
