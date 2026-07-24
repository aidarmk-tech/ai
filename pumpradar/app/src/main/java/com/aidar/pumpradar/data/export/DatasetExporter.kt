package com.aidar.pumpradar.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aidar.pumpradar.data.local.OutcomeDao
import com.aidar.pumpradar.data.local.TrainingSnapshotDao
import com.aidar.pumpradar.domain.analyzer.FeatureVector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Экспорт ML-датасета в CSV (патч §19). Обучение — вне APK; приложение только
 * отдаёт снимки признаков + фактические исходы (mfe/mae) из outcomes. Целевые
 * метки offline-скрипт считает сам.
 */
@Singleton
class DatasetExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotDao: TrainingSnapshotDao,
    private val outcomeDao: OutcomeDao,
    private val json: Json
) {
    private val header = listOf(
        "snapshotType", "symbol", "snapshotTime", "eventId", "opportunityLabel",
        "liquidityTier", "algorithmVersion",
        "return15s", "return60s", "return5m", "acceleration", "volumeZ30s",
        "takerBuyRatio30s", "cvd30s", "spreadBps", "obi10", "slippagePercent",
        "relativeStrengthVsBtc", "largestTradeShare", "top3TradeShare", "tinyTradeShare",
        "impulse", "entryRisk", "confidence", "exhaustionRisk", "artificialRisk", "marketWideRisk",
        "mfePercent", "maePercent", "outcomeCompleted"
    )

    suspend fun exportCsv(): File = withContext(Dispatchers.IO) {
        val snaps = snapshotDao.all()
        val sb = StringBuilder()
        sb.append(header.joinToString(",")).append('\n')
        for (s in snaps) {
            val fv = runCatching {
                json.decodeFromString(FeatureVector.serializer(), s.featureVectorJson)
            }.getOrNull() ?: FeatureVector()
            val outcome = s.signalId?.let { runCatching { outcomeDao.get(it) }.getOrNull() }
            val row = listOf(
                s.snapshotType, s.symbol, s.snapshotTime.toString(), s.eventId ?: "",
                s.opportunityLabel, s.liquidityTier, s.algorithmVersion,
                n(fv.return15s), n(fv.return60s), n(fv.return5m), n(fv.acceleration),
                n(fv.volumeZ30s), n(fv.takerBuyRatio30s), n(fv.cvd30s), n(fv.spreadBps),
                n(fv.obi10), n(fv.slippagePercent), n(fv.relativeStrengthVsBtc),
                n(fv.largestTradeShare), n(fv.top3TradeShare), n(fv.tinyTradeShare),
                fv.impulse.toString(), fv.entryRisk.toString(), fv.confidence.toString(),
                fv.exhaustionRisk.toString(), fv.artificialRisk.toString(), fv.marketWideRisk.toString(),
                n(outcome?.mfePercent), n(outcome?.maePercent),
                (outcome?.completed == true).toString()
            )
            sb.append(row.joinToString(",")).append('\n')
        }
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "pumpradar_dataset.csv")
        file.writeText(sb.toString())
        file
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun n(v: Double?): String = v?.let { "%.6f".format(it) } ?: ""
}
