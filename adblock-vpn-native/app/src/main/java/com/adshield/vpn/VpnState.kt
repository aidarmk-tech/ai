package com.adshield.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide, observable state shared between the [AdVpnService], the
 * [MainActivity] UI and the Quick Settings [AdBlockTileService].
 */
object VpnState {
    /** Whether the filtering VPN is currently established. */
    val running = MutableStateFlow(false)

    /** Number of domains loaded into the active blocklist. */
    val blockListSize = MutableStateFlow(0)

    /** DNS queries answered with a block since the service started. */
    val blockedCount = AtomicLong(0)

    /** Total DNS queries seen since the service started. */
    val totalCount = AtomicLong(0)

    /** Ring buffer of the most recently blocked domains (newest last). */
    private const val LOG_CAPACITY = 200
    private val recent = ArrayDeque<String>(LOG_CAPACITY)

    @Synchronized
    fun addBlocked(domain: String) {
        if (recent.size >= LOG_CAPACITY) recent.removeFirst()
        recent.addLast(domain)
    }

    /** Snapshot of the recent blocked domains, newest first. */
    @Synchronized
    fun recentBlocked(): List<String> = recent.toList().asReversed()

    @Synchronized
    fun clearLog() = recent.clear()

    fun resetCounters() {
        blockedCount.set(0)
        totalCount.set(0)
    }
}
