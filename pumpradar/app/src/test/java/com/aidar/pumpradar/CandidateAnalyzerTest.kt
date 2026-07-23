package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.CandidateAnalyzer
import com.aidar.pumpradar.domain.model.AggTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Warm start базы объёма (ТЗ 0A.4). */
class CandidateAnalyzerTest {

    // Реальные часы: onAggTrade/seedVolume чистят историю по System-времени,
    // поэтому метки должны быть «свежими» относительно текущего момента.
    private val now = System.currentTimeMillis()

    /** Без прогрева и без истории Объём Z не готов. */
    @Test fun volumeZ_notReady_withoutHistory() {
        val a = CandidateAnalyzer()
        a.onAggTrade(AggTrade("XUSDT", 1.0, 10.0, 10.0, false, now))
        val m = a.metrics("XUSDT", now)
        assertNotNull(m)
        assertNull(m!!.volumeZ30s)
    }

    /** После seed из 1s-клайнов Объём Z доступен сразу. */
    @Test fun volumeZ_ready_afterSeed() {
        val a = CandidateAnalyzer()
        // 12 завершённых 10с-бакетов в прошлом с нормальным объёмом.
        val kl = (1..12).map { i -> (now - i * 10_000L) to 100.0 }
        a.seedVolume("XUSDT", kl)
        val m = a.metrics("XUSDT", now)
        assertNotNull(m)
        assertNotNull(m!!.volumeZ30s)
    }

    /** Повторный seed игнорируется (idempotent). */
    @Test fun seed_isOnce() {
        val a = CandidateAnalyzer()
        val kl = (1..12).map { i -> (now - i * 10_000L) to 100.0 }
        a.seedVolume("XUSDT", kl)
        assertTrue(a.isSeeded("XUSDT"))
        a.seedVolume("XUSDT", kl) // второй раз — без эффекта
        assertTrue(a.isSeeded("XUSDT"))
    }

    /** retain освобождает неактивные символы. */
    @Test fun retain_dropsInactive() {
        val a = CandidateAnalyzer()
        a.seedVolume("XUSDT", listOf((now - 10_000L) to 100.0))
        a.retain(setOf("YUSDT"))
        assertFalse(a.isSeeded("XUSDT"))
    }

    /** Дубликаты и сделки из прошлого по aggId отбрасываются (ТЗ 0A.7). */
    @Test fun aggTrade_dedupAndOrder() {
        val a = CandidateAnalyzer()
        a.onAggTrade(AggTrade("XUSDT", 1.0, 10.0, 10.0, false, now, 5))
        a.onAggTrade(AggTrade("XUSDT", 1.0, 10.0, 10.0, false, now, 5)) // дубликат
        a.onAggTrade(AggTrade("XUSDT", 1.0, 10.0, 10.0, false, now, 4)) // из прошлого
        val m = a.metrics("XUSDT", now)
        assertEquals(1, m!!.tradeCount30s)
    }

    /** Пропуск в aggId помечается как разрыв потока (ТЗ 0A.7). */
    @Test fun aggTrade_gapFlagged() {
        val a = CandidateAnalyzer()
        a.onAggTrade(AggTrade("XUSDT", 1.0, 10.0, 10.0, false, now, 5))
        a.onAggTrade(AggTrade("XUSDT", 1.0, 10.0, 10.0, false, now, 10)) // пропущены 6..9
        val m = a.metrics("XUSDT", now)
        assertTrue(m!!.tradeGap)
    }

    /** Всплеск объёма над ровным baseline даёт высокий Z (ТЗ 0A.8). */
    @Test fun volumeZ_spikeDetected() {
        val a = CandidateAnalyzer()
        // 39 бакетов ~100 (с небольшим разбросом, чтобы MAD>0) + свежий бакет 1000.
        val base = (2..40).map { i -> (now - i * 10_000L) to (100.0 + (i % 5)) }
        val spike = listOf((now - 10_000L) to 1000.0)
        a.seedVolume("XUSDT", base + spike)
        val m = a.metrics("XUSDT", now)
        assertNotNull(m!!.volumeZ30s)
        assertTrue(m.volumeZ30s!! > 3.0)
    }
}
