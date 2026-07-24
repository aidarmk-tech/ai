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
               s.spreadBps AS spreadBps, s.slippagePercent AS slippagePercent,
               s.referencePrice AS referencePrice, o.price30s AS price30s,
               o.price1m AS price1m, o.price3m AS price3m, o.price5m AS price5m,
               o.price15m AS price15m, s.eventId AS eventId,
               s.opportunityLabel AS opportunityLabel, s.liquidityTier AS liquidityTier,
               s.entryRiskScore AS entryRiskScore, s.confidenceScore AS confidenceScore
        FROM outcomes o JOIN signals s ON s.id = o.signalId
        WHERE o.completed = 1
        ORDER BY s.createdAt DESC
        LIMIT :limit
        """
    )
    fun completedWithSignal(limit: Int): Flow<List<SignalOutcome>>
}

@Dao
interface TrainingSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: TrainingSnapshotEntity)

    @Query("SELECT COUNT(*) FROM training_snapshots")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM training_snapshots WHERE snapshotType = :type")
    suspend fun countByType(type: String): Int

    @Query("SELECT * FROM training_snapshots ORDER BY snapshotTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TrainingSnapshotEntity>

    @Query("SELECT * FROM training_snapshots ORDER BY snapshotTime ASC")
    suspend fun all(): List<TrainingSnapshotEntity>
}

@Dao
interface ClusterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cluster: MarketEventClusterEntity)

    @Query("SELECT * FROM market_event_clusters ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<MarketEventClusterEntity>>

    @Query("SELECT COUNT(*) FROM market_event_clusters")
    suspend fun count(): Int
}

@Dao
interface SignalTrajectoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trajectory: SignalTrajectoryEntity)

    @Query("SELECT * FROM signal_trajectories WHERE signalId = :id")
    suspend fun get(id: String): SignalTrajectoryEntity?

    @Query("SELECT * FROM signal_trajectories WHERE signalId IN (:ids)")
    suspend fun forIds(ids: List<String>): List<SignalTrajectoryEntity>

    @Query("SELECT COUNT(*) FROM signal_trajectories")
    suspend fun count(): Int

    @Query("DELETE FROM signal_trajectories WHERE startedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}

@Dao
interface ShadowSignalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(signal: ShadowSignalEntity)

    @Query("SELECT * FROM shadow_signals WHERE completed = 0")
    suspend fun pending(): List<ShadowSignalEntity>

    @Query("SELECT * FROM shadow_signals WHERE completed = 1 ORDER BY createdAt DESC LIMIT :limit")
    fun completed(limit: Int): Flow<List<ShadowSignalEntity>>

    @Query("SELECT COUNT(*) FROM shadow_signals WHERE strategy = :strategy")
    suspend fun countByStrategy(strategy: String): Int

    @Query("DELETE FROM shadow_signals WHERE createdAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
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
