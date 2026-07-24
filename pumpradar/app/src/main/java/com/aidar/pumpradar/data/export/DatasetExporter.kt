package com.aidar.pumpradar.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aidar.pumpradar.data.local.OutcomeDao
import com.aidar.pumpradar.data.local.SnapshotOutcomeDao
import com.aidar.pumpradar.data.local.TrainingSnapshotDao
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
    private val snapshotOutcomeDao: SnapshotOutcomeDao,
    private val outcomeDao: OutcomeDao,
    private val json: Json
) {
    suspend fun exportCsv(): File = withContext(Dispatchers.IO) {
        val snaps = snapshotDao.all()
        val sb = StringBuilder()
        sb.append(CsvFormat.encodeRow(DatasetRows.HEADER, DatasetRows.COLUMN_COUNT)).append("\r\n")
        for (s in snaps) {
            // Исход берём по snapshotId (работает и для NEAR_MISS/RANDOM_NORMAL),
            // с откатом на legacy-outcome сигнала для старых записей.
            val so = runCatching { snapshotOutcomeDao.get(s.id) }.getOrNull()
            val legacy = if (so == null) {
                s.signalId?.let { runCatching { outcomeDao.get(it) }.getOrNull() }
            } else null
            val view = when {
                so != null -> DatasetRows.OutcomeView(so.mfePercent, so.maePercent, so.completed)
                legacy != null -> DatasetRows.OutcomeView(legacy.mfePercent, legacy.maePercent, legacy.completed)
                else -> null
            }
            sb.append(CsvFormat.encodeRow(DatasetRows.buildRow(json, s, view), DatasetRows.COLUMN_COUNT))
                .append("\r\n")
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
}
