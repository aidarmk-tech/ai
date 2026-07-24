package com.aidar.pumpradar.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(signal: SignalEntity)

    @Query("SELECT * FROM signals ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SignalEntity>>

    @Query("SELECT * FROM signals WHERE symbol = :symbol ORDER BY createdAt DESC LIMIT :limit")
    fun forSymbol(symbol: String, limit: Int): Flow<List<SignalEntity>>

    @Query("SELECT COUNT(*) FROM signals WHERE createdAt >= :since")
    fun countSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM signals WHERE createdAt >= :since AND score >= :minScore")
    fun countSinceWithScore(since: Long, minScore: Int): Flow<Int>

    @Query("DELETE FROM signals WHERE createdAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("SELECT * FROM signals ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentOnce(limit: Int): List<SignalEntity>
}

@Dao
interface OutcomeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(outcome: OutcomeEntity)

    @Query("SELECT * FROM outcomes WHERE signalId = :id")
    suspend fun get(id: String): OutcomeEntity?

    @Query("SELECT * FROM outcomes WHERE completed = 0")
    suspend fun pending(): List<OutcomeEntity>

    @Query("SELECT * FROM outcomes WHERE completed = 1")
    fun completed(): Flow<List<OutcomeEntity>>

    @Query(
        """
        SELECT s.symbol AS symbol, s.level AS level, s.score AS score,
               s.createdAt AS createdAt, o.mfePercent AS mfePercent,
               o.maePercent AS maePercent, o.timeToMfeSeconds AS timeToMfeSeconds,
               s.spreadBps AS spreadBps, s.slippagePercent AS slippagePercent
        FROM outcomes o JOIN signals s ON s.id = o.signalId
        WHERE o.completed = 1
        ORDER BY s.createdAt DESC
        LIMIT :limit
        """
    )
    fun completedWithSignal(limit: Int): Flow<List<SignalOutcome>>
}

@Dao
interface AppEventDao {
    @Insert
    suspend fun insert(event: AppEventEntity)

    @Query("SELECT * FROM app_events ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<AppEventEntity>>

    @Query("DELETE FROM app_events WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("DELETE FROM app_events WHERE id NOT IN (SELECT id FROM app_events ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
