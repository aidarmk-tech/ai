package com.aidar.pumpradar.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единый weight-aware ограничитель REST (ТЗ v2, раздел 0A.5).
 *
 * Держит локальный token-bucket по весу за скользящую минуту с запасом
 * [RESERVED_PERCENT]%, ограничивает число одновременных и минутных backfill,
 * синхронизируется по заголовку X-MBX-USED-WEIGHT-1M и приостанавливает REST
 * при HTTP 429 (Retry-After) / 418 (circuit breaker). Бесконечных retry нет.
 */
@Singleton
class BinanceRestRateLimiter @Inject constructor() {

    private val concurrency = Semaphore(MAX_CONCURRENT_BACKFILLS)
    private val mutex = Mutex()

    private var windowStart = 0L
    private var weightUsed = 0
    private var backfillsThisMinute = 0
    private var pausedUntil = 0L

    /** Ограничить одновременные backfill (ТЗ: maximumConcurrentBackfills). */
    suspend fun <T> withBackfillSlot(block: suspend () -> T): T =
        concurrency.withPermit { block() }

    /**
     * Разрешает запрос веса [weight]. false → пропустить (пауза/исчерпан бюджет),
     * тогда вызывающий продолжает в WARMING/DEGRADED без REST.
     */
    suspend fun acquire(weight: Int): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now < pausedUntil) return false
        if (now - windowStart >= 60_000L) {
            windowStart = now
            weightUsed = 0
            backfillsThisMinute = 0
        }
        if (backfillsThisMinute >= MAX_BACKFILLS_PER_MINUTE) return false
        if (weightUsed + weight > usableWeight()) return false
        weightUsed += weight
        backfillsThisMinute += 1
        true
    }

    /** Синхронизировать локальный счётчик с фактическим весом сервера. */
    suspend fun onUsedWeight(headerValue: String?) {
        val used = headerValue?.toIntOrNull() ?: return
        mutex.withLock { if (used > weightUsed) weightUsed = used }
    }

    /** HTTP 429: приостановить REST до Retry-After (сек). */
    suspend fun onRateLimited(retryAfterSeconds: Long?) = mutex.withLock {
        val delay = (retryAfterSeconds ?: 60L).coerceIn(1L, 600L) * 1000L
        pausedUntil = System.currentTimeMillis() + delay
        Timber.w("REST 429: пауза на %d с", delay / 1000)
    }

    /** HTTP 418: circuit breaker (бан IP) до Retry-After или дефолта. */
    suspend fun onBanned(retryAfterSeconds: Long?) = mutex.withLock {
        val delay = (retryAfterSeconds ?: 300L).coerceIn(1L, 3600L) * 1000L
        pausedUntil = System.currentTimeMillis() + delay
        Timber.e("REST 418: circuit breaker на %d с", delay / 1000)
    }

    fun isPaused(): Boolean = System.currentTimeMillis() < pausedUntil

    private fun usableWeight(): Int =
        (MAX_WEIGHT_PER_MINUTE * (100 - RESERVED_PERCENT) / 100)

    private companion object {
        const val MAX_CONCURRENT_BACKFILLS = 2
        const val MAX_BACKFILLS_PER_MINUTE = 20
        const val RESERVED_PERCENT = 20
        // Консервативно ниже фактического IP-лимита Binance (6000/мин).
        const val MAX_WEIGHT_PER_MINUTE = 1000
    }
}
