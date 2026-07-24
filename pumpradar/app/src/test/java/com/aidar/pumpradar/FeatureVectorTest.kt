package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.FeatureVector
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Вектор признаков для ML (патч §16). */
class FeatureVectorTest {

    private val json = Json

    @Test fun roundTrip() {
        val fv = FeatureVector(return60s = 1.5, impulse = 70, entryRisk = 20, confidence = 85)
        val s = json.encodeToString(FeatureVector.serializer(), fv)
        val back = json.decodeFromString(FeatureVector.serializer(), s)
        assertEquals(fv, back)
        assertTrue(s.contains("return60s"))
    }

    /** В векторе нет будущих исходов (§16 запрещает будущие данные). */
    @Test fun noFutureFields() {
        val s = json.encodeToString(FeatureVector.serializer(), FeatureVector(impulse = 50))
        assertFalse(s.contains("mfe"))
        assertFalse(s.contains("mae"))
        assertFalse(s.contains("outcome"))
        assertFalse(s.contains("target"))
    }
}
