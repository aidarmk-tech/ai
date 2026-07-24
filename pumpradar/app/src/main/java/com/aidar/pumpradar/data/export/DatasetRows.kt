package com.aidar.pumpradar.data.export

import com.aidar.pumpradar.data.local.TrainingSnapshotEntity
import com.aidar.pumpradar.domain.analyzer.FeatureVector
import kotlinx.serialization.json.Json

/**
 * Чистая (без Android/Room) сборка строк ML-датасета — тестируется напрямую.
 * Ровно [COLUMN_COUNT] полей в каждой строке (RFC 4180 экранирование — в
 * [CsvFormat]).
 */
object DatasetRows {

    val HEADER = listOf(
        "snapshotType", "symbol", "snapshotTime", "eventId", "opportunityLabel",
        "liquidityTier", "algorithmVersion",
        "return15s", "return60s", "return5m", "acceleration", "volumeZ30s",
        "takerBuyRatio30s", "cvd30s", "spreadBps", "obi10", "slippagePercent",
        "relativeStrengthVsBtc", "largestTradeShare", "top3TradeShare", "tinyTradeShare",
        "impulse", "entryRisk", "confidence", "exhaustionRisk", "artificialRisk", "marketWideRisk",
        "mfePercent", "maePercent", "outcomeCompleted"
    )

    val COLUMN_COUNT: Int get() = HEADER.size

    /** Минимальная проекция исхода (источник — snapshot outcomes). */
    data class OutcomeView(val mfePercent: Double?, val maePercent: Double?, val completed: Boolean)

    /** Строка датасета: ровно [COLUMN_COUNT] полей. */
    fun buildRow(json: Json, s: TrainingSnapshotEntity, outcome: OutcomeView?): List<String> {
        val fv = runCatching {
            json.decodeFromString(FeatureVector.serializer(), s.featureVectorJson)
        }.getOrNull() ?: FeatureVector()
        return listOf(
            s.snapshotType, s.symbol, s.snapshotTime.toString(), s.eventId ?: "",
            s.opportunityLabel, s.liquidityTier, s.algorithmVersion,
            CsvFormat.num(fv.return15s), CsvFormat.num(fv.return60s), CsvFormat.num(fv.return5m),
            CsvFormat.num(fv.acceleration), CsvFormat.num(fv.volumeZ30s),
            CsvFormat.num(fv.takerBuyRatio30s), CsvFormat.num(fv.cvd30s), CsvFormat.num(fv.spreadBps),
            CsvFormat.num(fv.obi10), CsvFormat.num(fv.slippagePercent), CsvFormat.num(fv.relativeStrengthVsBtc),
            CsvFormat.num(fv.largestTradeShare), CsvFormat.num(fv.top3TradeShare), CsvFormat.num(fv.tinyTradeShare),
            fv.impulse.toString(), fv.entryRisk.toString(), fv.confidence.toString(),
            fv.exhaustionRisk.toString(), fv.artificialRisk.toString(), fv.marketWideRisk.toString(),
            CsvFormat.num(outcome?.mfePercent), CsvFormat.num(outcome?.maePercent),
            (outcome?.completed == true).toString()
        )
    }
}
