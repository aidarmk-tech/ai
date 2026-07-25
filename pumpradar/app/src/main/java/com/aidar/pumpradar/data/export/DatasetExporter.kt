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

/** Экспорт снимков признаков и фактических исходов в CSV. */
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
            val so = runCatching { snapshotOutcomeDao.get(s.id) }.getOrNull()
            val legacy = if (so == null) {
                s.signalId?.let { runCatching { outcomeDao.get(it) }.getOrNull() }
            } else null
            val view = when {
                so != null -> DatasetRows.OutcomeView(
                    mfePercent = so.mfePercent,
                    maePercent = so.maePercent,
                    completed = so.completed,
                    long300TargetTime = so.long300TargetTime,
                    long100StopTime = so.long100StopTime,
                    firstBarrierLong300_100 = so.firstBarrierLong300_100,
                    plan3ActivationTime = so.plan3ActivationTime,
                    plan3TargetTime = so.plan3TargetTime,
                    plan3ExitTime = so.plan3ExitTime,
                    plan3Result = so.plan3Result,
                    plan3GrossReturnPercent = so.plan3GrossReturnPercent
                )
                legacy != null -> DatasetRows.OutcomeView(
                    legacy.mfePercent, legacy.maePercent, legacy.completed
                )
                else -> null
            }

            // MonitoringEngine старых установок сохранял внутреннюю версию 3.0.0.
            // Для нового эксперимента используем фиксированную границу сборки, чтобы
            // строки после установки однозначно отделялись как 4.1.0 без переписывания
            // старой истории в Room.
            val exportSnapshot = if (s.snapshotTime >= EXPERIMENT_41_CUTOFF_MS) {
                s.copy(algorithmVersion = EXPERIMENT_41_VERSION)
            } else s

            sb.append(
                CsvFormat.encodeRow(
                    DatasetRows.buildRow(json, exportSnapshot, view),
                    DatasetRows.COLUMN_COUNT
                )
            ).append("\r\n")
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

    private companion object {
        const val EXPERIMENT_41_VERSION = "4.1.0"
        // 25.07.2026 18:00 Asia/Almaty — граница новой экспериментальной сборки.
        const val EXPERIMENT_41_CUTOFF_MS = 1_784_984_400_000L
    }
}