package com.aidar.pumpradar.domain.analyzer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ограничение повторных сигналов по одной монете (item 10). После выданного
 * сигнала новый по тому же символу разрешается только когда:
 *   1) рынок вернулся в NORMAL (импульс спал);
 *   2) прошло ≥ repeatNormalMs непрерывно нормального рынка;
 *   3) появился новый независимый импульс (следующий LONG_CONTINUATION уже после
 *      этого периода — это и есть новый импульс).
 */
@Singleton
class SignalGovernor @Inject constructor() {

    private class Row {
        var blocked = false        // после сигнала до возврата в NORMAL на repeatNormalMs
        var normalSince = 0L
        var lastSignalAt = 0L
    }

    private val rows = HashMap<String, Row>()
    private val lock = Any()

    fun retain(symbols: Set<String>) = synchronized(lock) { rows.keys.retainAll(symbols) }
    fun clear() = synchronized(lock) { rows.clear() }

    /** Разрешён ли новый сигнал по символу прямо сейчас. */
    fun allow(symbol: String): Boolean = synchronized(lock) { rows[symbol]?.blocked != true }

    /** Обновление состояния рынка на тике: elevated = импульс/сигнал ещё активен. */
    fun observe(symbol: String, now: Long, elevated: Boolean, repeatNormalMs: Long) = synchronized(lock) {
        val row = rows.getOrPut(symbol) { Row() }
        if (!row.blocked) return
        if (elevated) {
            row.normalSince = 0L                       // всё ещё повышенный — таймер нормального сбрасывается
        } else {
            if (row.normalSince == 0L) row.normalSince = now
            if (now - row.normalSince >= repeatNormalMs) row.blocked = false
        }
    }

    /** Зафиксировать выданный сигнал: блокировать повторы до возврата в NORMAL. */
    fun recordSignal(symbol: String, now: Long) = synchronized(lock) {
        val row = rows.getOrPut(symbol) { Row() }
        row.blocked = true
        row.normalSince = 0L
        row.lastSignalAt = now
    }
}
