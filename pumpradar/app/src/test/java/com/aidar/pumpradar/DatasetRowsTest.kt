package com.aidar.pumpradar

import com.aidar.pumpradar.data.export.CsvFormat
import com.aidar.pumpradar.data.export.DatasetRows
import com.aidar.pumpradar.data.local.TrainingSnapshotEntity
import com.aidar.pumpradar.domain.analyzer.FeatureVector
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetRowsTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun snapshot(
        fv: FeatureVector,
        type: String = "NEAR_MISS",
        version: String = "4.2.0"
    ) = TrainingSnapshotEntity(
        id = "id1", signalId = null, eventId = null, symbol = "BTCUSDT",
        snapshotTime = 1700000000000L, snapshotType = type,
        algorithmVersion = version, liquidityTier = "A", opportunityLabel = "WATCH",
        featureVectorJson = json.encodeToString(FeatureVector.serializer(), fv)
    )

    @Test fun rowHasExpectedColumns() {
        val row = DatasetRows.buildRow(json, snapshot(FeatureVector()), null)
        assertEquals(41, row.size)
        assertEquals(41, DatasetRows.COLUMN_COUNT)
    }

    @Test fun exportReimportRoundTrip() {
        val fv = FeatureVector(
            return15s = 0.35,
            return60s = 1.2,
            cvdSlope = 12.5,
            spreadBps = 12.0,
            slippagePercent = 0.0000012,
            impulse = 55,
            entryRisk = 30,
            confidence = 80
        )
        val outcome = DatasetRows.OutcomeView(
            mfePercent = 3.4,
            maePercent = -0.4,
            completed = true,
            long300TargetTime = 120_000,
            long100StopTime = null,
            firstBarrierLong300_100 = "TARGET_FIRST",
            plan3ActivationTime = 20_000,
            plan3TargetTime = 120_000,
            plan3ExitTime = 120_000,
            plan3Result = "TARGET_3",
            plan3GrossReturnPercent = 2.6
        )
        val row = DatasetRows.buildRow(json, snapshot(fv, "RANDOM_NORMAL"), outcome)

        val line = CsvFormat.encodeRow(row, DatasetRows.COLUMN_COUNT)
        val parsed = CsvFormat.parseLine(line)

        assertEquals(41, parsed.size)
        assertEquals(row, parsed)
        assertEquals("RANDOM_NORMAL", parsed[0])
        assertEquals("BTCUSDT", parsed[1])
        assertEquals("4.2.0", parsed[6])
        assertEquals("12.5", parsed[14])
        assertEquals("false", parsed[28])
        assertTrue(parsed[29].contains("IMPULSE_LT_60"))
        assertEquals("3.4", parsed[30])
        assertEquals("-0.4", parsed[31])
        assertEquals("true", parsed[32])
        assertEquals("120000", parsed[33])
        assertEquals("TARGET_FIRST", parsed[35])
        assertEquals("TARGET_3", parsed[39])
        assertEquals("2.6", parsed[40])
        assertTrue(parsed.contains("0.0000012"))
    }

    @Test fun strictQualityPassIsExported() {
        val fv = FeatureVector(
            cvdSlope = 10.0,
            spreadBps = 10.0,
            slippagePercent = 0.05,
            impulse = 60,
            entryRisk = 35,
            exhaustionRisk = 20
        )
        val row = DatasetRows.buildRow(json, snapshot(fv), null)
        assertEquals("true", row[28])
        assertEquals("", row[29])
    }

    @Test fun labelsPresentForNonTriggered() {
        val outcome = DatasetRows.OutcomeView(0.5, -0.5, true)
        val row = DatasetRows.buildRow(json, snapshot(FeatureVector(), "NEAR_MISS"), outcome)
        assertEquals("0.5", row[30])
        assertEquals("-0.5", row[31])
        assertEquals("true", row[32])
    }
}
