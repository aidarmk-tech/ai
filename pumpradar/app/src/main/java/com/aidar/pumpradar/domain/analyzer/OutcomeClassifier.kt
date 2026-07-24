package com.aidar.pumpradar.domain.analyzer

/**
 * Диагностические категории исхода вместо одной метки «ложный» (патч §10.2).
 * Работает по доступным данным (контрольные точки + MFE/MAE + спред). Категории,
 * требующие детекторов истощения/общерыночного движения/ложного пробоя, добавятся
 * на следующих этапах патча.
 */
object OutcomeClassifier {

    const val CLEAN_WIN = "CLEAN_WIN"
    const val SMALL_WIN = "SMALL_WIN"
    const val WHIPSAW = "WHIPSAW"
    const val LOW_LIQUIDITY_MOVE = "LOW_LIQUIDITY_MOVE"
    const val NO_CONTINUATION = "NO_CONTINUATION"
    const val DATA_INCOMPLETE = "DATA_INCOMPLETE"

    fun category(
        returnsByTime: List<Pair<Int, Double>>,
        mfePercent: Double?,
        maePercent: Double?,
        spreadBps: Double?
    ): String {
        if (returnsByTime.isEmpty()) return DATA_INCOMPLETE
        val mfe = mfePercent ?: return DATA_INCOMPLETE
        val mae = maePercent ?: return DATA_INCOMPLETE
        return when {
            CalibrationEval.targetBeforeStop(returnsByTime, 2.0, 1.0, 900) -> CLEAN_WIN
            CalibrationEval.targetBeforeStop(returnsByTime, 1.0, 0.75, 900) -> SMALL_WIN
            mfe >= 1.5 && mae <= -1.5 -> WHIPSAW
            (spreadBps ?: 0.0) > 45.0 -> LOW_LIQUIDITY_MOVE
            else -> NO_CONTINUATION
        }
    }
}
