package com.aidar.pumpradar

import com.aidar.pumpradar.data.export.CsvFormat
import com.aidar.pumpradar.data.export.DatasetRows
import com.aidar.pumpradar.data.local.TrainingSnapshotEntity
import com.aidar.pumpradar.domain.analyzer.FeatureVector
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Экспорт строки датасета и её повторный импорт (roundtrip, ровно 30 колонок). */
class DatasetRowsTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun snapshot(fv: FeatureVector, type: String = "NEAR_MISS") = TrainingSnapshotEntity(
        id = "id1", signalId = null, eventId = null, symbol = "BTCUSDT",
        snapshotTime = 1700000000000L, snapshotType = type,
        algorithmVersion = "3.0.0", liquidityTier = "A", opportunityLabel = "WATCH",
        featureVectorJson = json.encodeToString(FeatureVector.serializer(), fv)
    )

    @Test fun rowHasExactlyThirtyColumns() {
        val row = DatasetRows.buildRow(json, snapshot(FeatureVector()), null)
        assertEquals(30, row.size)
        assertEquals(30, DatasetRows.COLUMN_COUNT)
    }

    @Test fun exportReimportRoundTrip() {
        val fv = FeatureVector(
            return15s = 0.35, return60s = 1.2, slippagePercent = 0.0000012,
            impulse = 55, entryRisk = 30, confidence = 80
        )
        val outcome = DatasetRows.OutcomeView(mfePercent = 1.53, maePercent = -2.11, completed = true)
        val row = DatasetRows.buildRow(json, snapshot(fv, "RANDOM_NORMAL"), outcome)

        val line = CsvFormat.encodeRow(row, DatasetRows.COLUMN_COUNT)
        val parsed = CsvFormat.parseLine(line)

        assertEquals(30, parsed.size)
        assertEquals(row, parsed)                       // полный roundtrip полей
        assertEquals("RANDOM_NORMAL", parsed[0])
        assertEquals("BTCUSDT", parsed[1])
        assertEquals("1.53", parsed[27])                // mfePercent
        assertEquals("-2.11", parsed[28])               // maePercent
        assertEquals("true", parsed[29])                // outcomeCompleted
        // Малое проскальзывание не схлопнулось в ноль.
        assertTrue(parsed.contains("0.0000012"))
    }

    @Test fun labelsPresentForNonTriggered() {
        // NEAR_MISS/RANDOM_NORMAL теперь имеют исход → колонки меток заполнены.
        val outcome = DatasetRows.OutcomeView(0.5, -0.5, true)
        val row = DatasetRows.buildRow(json, snapshot(FeatureVector(), "NEAR_MISS"), outcome)
        assertEquals("0.5", row[27])
        assertEquals("-0.5", row[28])
        assertEquals("true", row[29])
    }
}
