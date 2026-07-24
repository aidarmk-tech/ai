package com.aidar.pumpradar.domain.analyzer

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Дедупликация сигналов в одно рыночное событие (патч §12). Несколько сигналов
 * одной монеты во время одного движения относятся к общему кластеру; новый
 * кластер начинается, если разрыв между соседними сигналами больше [gapMs].
 */
@Singleton
class MarketEventClusterer @Inject constructor() {

    data class Cluster(
        val id: String,
        val symbol: String,
        val startedAt: Long,
        var lastTime: Long,
        var count: Int,
        var peakImpulse: Int,
        val firstSignalId: String
    )

    private val active = HashMap<String, Cluster>()
    private val gapMs: Long = DEFAULT_GAP_MS

    /** Присвоить сигналу кластер; вернуть его текущее состояние. */
    fun assign(
        symbol: String,
        now: Long,
        impulse: Int,
        signalId: String,
        newId: () -> String = { UUID.randomUUID().toString() }
    ): Cluster {
        val c = active[symbol]
        return if (c != null && now - c.lastTime <= gapMs) {
            c.lastTime = now
            c.count += 1
            if (impulse > c.peakImpulse) c.peakImpulse = impulse
            c
        } else {
            val nc = Cluster(newId(), symbol, now, now, 1, impulse, signalId)
            active[symbol] = nc
            nc
        }
    }

    fun retain(symbols: Set<String>) { active.keys.retainAll(symbols) }
    fun clear() = active.clear()

    companion object {
        const val DEFAULT_GAP_MS = 20 * 60_000L   // ≤20 минут между соседними сигналами
    }
}
