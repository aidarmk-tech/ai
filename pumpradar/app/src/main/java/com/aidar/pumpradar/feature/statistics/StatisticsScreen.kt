package com.aidar.pumpradar.feature.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aidar.pumpradar.data.local.OutcomeDao
import com.aidar.pumpradar.data.local.ShadowSignalDao
import com.aidar.pumpradar.data.local.ShadowSignalEntity
import com.aidar.pumpradar.data.local.SignalOutcome
import com.aidar.pumpradar.data.local.SnapshotOutcomeDao
import com.aidar.pumpradar.data.local.TrainingSnapshotDao
import com.aidar.pumpradar.data.preferences.SettingsRepository
import com.aidar.pumpradar.domain.analyzer.CalibrationEval
import com.aidar.pumpradar.domain.analyzer.ExecutableOutcome
import com.aidar.pumpradar.domain.analyzer.ExecutablePathEval
import com.aidar.pumpradar.domain.analyzer.FeatureVector
import com.aidar.pumpradar.domain.analyzer.OutcomeClassifier
import com.aidar.pumpradar.domain.analyzer.RiskDiagnostics
import com.aidar.pumpradar.domain.analyzer.StrategyLab
import com.aidar.pumpradar.domain.analyzer.TradeSide
import com.aidar.pumpradar.domain.model.TrajectoryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Разметка исходов снимков по типам (для гейта готовности к ML, item 10). */
data class SnapCounts(
    val total: Int = 0, val triggered: Int = 0, val nearMiss: Int = 0, val randomNormal: Int = 0
) {
    val allTypesLabeled: Boolean get() = triggered > 0 && nearMiss > 0 && randomNormal > 0
}

/** Агрегат сработавших риск-условий по последним снимкам (item 5). */
data class RiskDiag(val total: Int = 0, val counts: Map<String, Int> = emptyMap())

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    dao: OutcomeDao,
    shadowDao: ShadowSignalDao,
    snapshotDao: TrainingSnapshotDao,
    snapshotOutcomeDao: SnapshotOutcomeDao,
    settings: SettingsRepository,
    private val json: Json
) : ViewModel() {
    val outcomes = dao.completedWithSignal(200).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val shadows = shadowDao.completed(300).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val noTradeCount = flow {
        emit(snapshotDao.countByType("NEAR_MISS") + snapshotDao.countByType("RANDOM_NORMAL"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val snapCounts = flow {
        emit(
            SnapCounts(
                total = snapshotOutcomeDao.completedCount(),
                triggered = snapshotOutcomeDao.completedCountByType("TRIGGERED"),
                nearMiss = snapshotOutcomeDao.completedCountByType("NEAR_MISS"),
                randomNormal = snapshotOutcomeDao.completedCountByType("RANDOM_NORMAL")
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SnapCounts())
    val riskDiag = flow {
        val snaps = snapshotDao.recent(500)
        val counts = LinkedHashMap<String, Int>()
        RiskDiagnostics.CONDITION_IDS.forEach { counts[it] = 0 }
        var n = 0
        for (s in snaps) {
            val fv = runCatching {
                json.decodeFromString(FeatureVector.serializer(), s.featureVectorJson)
            }.getOrNull() ?: continue
            n++
            RiskDiagnostics.firedConditions(
                fv.return5m, fv.return60s, fv.cvdSlope, fv.takerBuyRatio30s,
                fv.spreadBps, fv.slippagePercent, fv.volumeZ30s, fv.obi10
            ).forEach { (k, v) -> if (v) counts[k] = (counts[k] ?: 0) + 1 }
        }
        emit(RiskDiag(n, counts))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RiskDiag())
    val calibrating = settings.settings.map { it.calibrationMode }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
}

@Composable
fun StatisticsScreen(vm: StatisticsViewModel = hiltViewModel()) {
    val outcomes by vm.outcomes.collectAsStateWithLifecycle()
    val shadows by vm.shadows.collectAsStateWithLifecycle()
    val noTradeCount by vm.noTradeCount.collectAsStateWithLifecycle()
    val snapCounts by vm.snapCounts.collectAsStateWithLifecycle()
    val riskDiag by vm.riskDiag.collectAsStateWithLifecycle()
    val calibrating by vm.calibrating.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Статистика", style = MaterialTheme.typography.titleLarge)

        // Калибровка (ТЗ 0A.24 + патч §24): прогресс сбора, без термина «ложные».
        if (calibrating || outcomes.isNotEmpty()) {
            val collected = outcomes.size
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (calibrating) "Калибровка активна" else "Калибровка",
                        fontWeight = FontWeight.Bold)
                    StatRow("Собрано (оценок)", "$collected / 200")
                    Text("Выборка пока мала. Пороги автоматически не меняются. Исход оценивается " +
                        "по нескольким целям и категориям, а не одной цифрой «ложных».",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (outcomes.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Пока нет завершённых оценок. Радар следит за ценой 15 минут после " +
                        "каждого сигнала — первые цифры появятся примерно через 15 минут " +
                        "работы мониторинга.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Column
        }

        // Обзор (патч §24).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Обзор", fontWeight = FontWeight.Bold)
                val coverage = outcomes.count { hasCheckpoints(it) } * 100.0 / outcomes.size
                val uniqueEvents = outcomes
                    .map { it.eventId ?: "${it.symbol}-${it.createdAt}" }.distinct().size
                StatRow("Сигналов (оценок)", outcomes.size.toString())
                StatRow("Уникальных событий", uniqueEvents.toString())
                StatRow("Outcome coverage", "%.0f%%".format(coverage))
                StatRow("Медиана MFE (макс. рост)", median(outcomes.mapNotNull { it.mfePercent }))
                StatRow("Медиана MAE (макс. просадка)", median(outcomes.mapNotNull { it.maePercent }))
            }
        }

        // Цели раньше стопа (патч §10.1) — вместо одной цифры «ложных».
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Цели (достигнута раньше стопа)", fontWeight = FontWeight.Bold)
                TARGETS.forEach { t ->
                    val hits = outcomes.count { targetHit(it, t) }
                    val rate = hits * 100.0 / outcomes.size
                    StatRow(t.label, "%d/%d (%.0f%%)".format(hits, outcomes.size, rate))
                }
                Text("«Цель раньше стопа» по 5 контрольным точкам за 15 мин (грубая нижняя граница). " +
                    "Разные пороги — разный смысл, а не одна метка «ложный».",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // Категории исходов (патч §10.2).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Категории исходов", fontWeight = FontWeight.Bold)
                val counts = outcomes.groupingBy { outcomeCategory(it) }.eachCount()
                CATEGORY_ORDER.forEach { c ->
                    val n = counts[c] ?: 0
                    if (n > 0) StatRow(categoryRu(c), n.toString())
                }
                Text("«85% ложных» — слишком грубо: часть из них это пилы и поздние входы, " +
                    "а не пустышки. Точные пороги — в разделе «Настраиваемый критерий».",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // Исполнимый исход (executable) — честнее last price (ТЗ 0A.13).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Исполнимый исход (executable)", fontWeight = FontWeight.Bold)
                val total = outcomes.size
                val success = outcomes.count { successfulExecutable(it) }
                val rate = if (total > 0) success * 100.0 / total else 0.0
                StatRow("+2% раньше −1% (с издержками)", "%d (%.0f%%)".format(success, rate))
                StatRow("Медиана MFE executable", median(outcomes.mapNotNull { execMfe(it) }))
                StatRow("Медиана MAE executable", median(outcomes.mapNotNull { execMae(it) }))
                Text("Вход по ask + проскальзывание, выход по bid, минус издержки " +
                    "(комиссия 10 bps/сторона, доп. проскальзывание 5 bps). Спред/проскальзывание " +
                    "взяты на момент сигнала. Консервативная оценка, а не факт сделки.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // Настраиваемый критерий: цель/стоп/горизонт под свою стратегию.
        CalibrationCard(outcomes)

        // Лаборатория стратегий (патч §13): champion/challenger в теневом режиме.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Лаборатория стратегий (тень)", fontWeight = FontWeight.Bold)
                Text("Уведомлениями управляет champion (S1). Остальные сравниваются в тени. " +
                    "Точность = доля первичной цели +1%/−0.75% (item 9). " +
                    "Победитель автоматически не выбирается — нужна большая выборка.",
                    style = MaterialTheme.typography.bodySmall)
                StrategyLab.STRATEGIES.forEach { s ->
                    val trig = outcomes.filter { s.triggers(features(it)) }
                    val events = trig.map { eventKey(it) }.distinct()
                    if (events.size < 10) {
                        StatRow(s.title, "мало данных (${events.size})")
                    } else {
                        // Первичная цель +1%/−0.75% (item 9); 2%/3% — дополнительные.
                        val wins = trig.filter { targetHit(it, TARGETS[1]) }.map { eventKey(it) }.distinct()
                        val prec = wins.size * 100.0 / events.size
                        StatRow(s.title, "%d соб · %.0f%%".format(events.size, prec))
                    }
                }
            }
        }

        // Двусторонний анализ (LONG + SHORT) — теневые стратегии.
        TwoSidedCard(shadows, noTradeCount)

        // Диагностика риск-условий (item 5): какие условия срабатывают и как часто.
        RiskDiagnosticsCard(riskDiag)

        // Готовность к ML (item 10): гейт из нескольких обязательных условий.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Готовность к обучению", fontWeight = FontWeight.Bold)
                val events = outcomes.map { eventKey(it) }.distinct().size
                // Первичная цель (item 9): +1% раньше −0.75% (TARGETS[1]).
                val positives = outcomes.filter { targetHit(it, TARGETS[1]) }
                    .map { eventKey(it) }.distinct().size
                val minAt = outcomes.minOf { it.createdAt }
                val maxAt = outcomes.maxOf { it.createdAt }
                val days = ((maxAt - minAt) / 86_400_000L + 1).toInt()

                val enoughEvents = events >= 300
                val allTypes = snapCounts.allTypesLabeled
                val enoughDays = days >= 30
                val enoughPos = positives >= 50

                StatRow("Уникальных событий", gate("$events / 300", enoughEvents))
                StatRow("Позитивов (перв. +1%/−0.75%)", gate("$positives / 50", enoughPos))
                StatRow("Размечено снимков (все типы)",
                    gate("T${snapCounts.triggered}/N${snapCounts.nearMiss}/R${snapCounts.randomNormal}", allTypes))
                StatRow("Дней покрыто", gate("$days / 30", enoughDays))
                StatRow("Порядок first-barrier", "✓ по секундной траектории")
                StatRow("Несколько рыночных режимов", "⚠ проверить вручную")

                val autoReady = enoughEvents && enoughPos && allTypes && enoughDays
                Text(if (autoReady)
                    "Автопроверки пройдены. Перед обучением ещё убедись, что данные покрывают " +
                        "разные рыночные режимы (тренд/флет/распродажа) и что first-barrier " +
                        "разметка корректна. Модель обучается вне телефона."
                else
                    "Рано обучать production-модель. Нужны: ≥300 событий, исходы для triggered/" +
                        "near-miss/random-normal, ≥30 дней, несколько режимов и корректный порядок " +
                        "first-barrier. Пороги автоматически не меняются.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // Разбивка по уровням.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Точность по уровням", fontWeight = FontWeight.Bold)
                Text(
                    "Доля «+2% раньше −1%». При выборке <30 процент не показываем (патч §24).",
                    style = MaterialTheme.typography.bodySmall
                )
                val order = listOf("EXTREME", "STRONG", "EARLY", "WATCH")
                val byLevel = outcomes.groupBy { it.level }
                val present = order.filter { byLevel[it]?.isNotEmpty() == true }
                if (present.isEmpty()) {
                    Text("—", style = MaterialTheme.typography.bodyMedium)
                } else {
                    present.forEach { lvl ->
                        val list = byLevel.getValue(lvl)
                        if (list.size < 30) {
                            StatRow("$lvl (${list.size})", "недостаточно данных")
                        } else {
                            val s = summarize(list)
                            StatRow("$lvl (${s.total})", s.successLabel)
                        }
                    }
                }
            }
        }

        // Последние сигналы с исходом.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Последние сигналы", fontWeight = FontWeight.Bold)
                outcomes.take(20).forEach { o ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${o.symbol} · ${o.level}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "MFE %s · MAE %s".format(pct(o.mfePercent), pct(o.maePercent)),
                            color = if (successful(o)) Color(0xFF39D98A) else Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Text(
            "Это не доходность: сделки не исполнялись. Показатели отражают только " +
                "поведение цены после сигнала.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private class Summary(
    val total: Int,
    val successLabel: String,
    val medianMfe: String,
    val medianMae: String
)

private fun summarize(items: List<SignalOutcome>): Summary {
    val total = items.size
    val success = items.count { successful(it) }
    val rate = if (total > 0) success * 100.0 / total else 0.0
    return Summary(
        total = total,
        successLabel = "%d (%.0f%%)".format(success, rate),
        medianMfe = median(items.mapNotNull { it.mfePercent }),
        medianMae = median(items.mapNotNull { it.maePercent })
    )
}

private fun successful(o: SignalOutcome): Boolean {
    val mfe = o.mfePercent ?: return false
    val mae = o.maePercent ?: return false
    return mfe >= 2.0 && mae > -1.0
}

// Executable-оценка (ТЗ 0A.13) — логика в ExecutableOutcome (тестируется отдельно).
private fun execMfe(o: SignalOutcome): Double? =
    ExecutableOutcome.mfe(o.mfePercent, o.spreadBps, o.slippagePercent)

private fun execMae(o: SignalOutcome): Double? =
    ExecutableOutcome.mae(o.maePercent, o.spreadBps, o.slippagePercent)

private fun successfulExecutable(o: SignalOutcome): Boolean =
    ExecutableOutcome.successful(o.mfePercent, o.maePercent, o.spreadBps, o.slippagePercent)

private fun pct(v: Double?): String = v?.let { "%+.2f%%".format(it) } ?: "—"

private fun median(v: List<Double>): String {
    if (v.isEmpty()) return "—"
    val s = v.sorted()
    val m = if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2 else s[s.size / 2]
    return "%+.2f%%".format(m)
}

/** Пометка выполнения условия гейта готовности (item 10). */
private fun gate(value: String, ok: Boolean): String = if (ok) "✓ $value" else "✗ $value"

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

private fun outcomeReturns(o: SignalOutcome): List<Pair<Int, Double>> =
    CalibrationEval.checkpointReturns(
        o.referencePrice, o.price30s, o.price1m, o.price3m, o.price5m, o.price15m
    )

private fun hasCheckpoints(o: SignalOutcome): Boolean = outcomeReturns(o).isNotEmpty()

private fun eventKey(o: SignalOutcome): String = o.eventId ?: "${o.symbol}-${o.createdAt}"

private fun features(o: SignalOutcome): StrategyLab.Features = StrategyLab.Features(
    opportunityLabel = o.opportunityLabel,
    liquidityTier = o.liquidityTier,
    entryRisk = o.entryRiskScore,
    confidence = o.confidenceScore,
    impulse = o.score
)

// Набор целей (патч §10.1): цель% / стоп%.
private class TargetDef(val target: Double, val stop: Double, val label: String)

private val TARGETS = listOf(
    TargetDef(0.75, 0.50, "+0.75% / −0.50%"),
    TargetDef(1.00, 0.75, "+1.00% / −0.75%"),
    TargetDef(1.50, 1.00, "+1.50% / −1.00%"),
    TargetDef(2.00, 1.00, "+2.00% / −1.00%"),
    TargetDef(3.00, 1.50, "+3.00% / −1.50%")
)

private fun targetHit(o: SignalOutcome, t: TargetDef): Boolean {
    val rets = outcomeReturns(o)
    if (rets.isEmpty()) return false
    return CalibrationEval.targetBeforeStop(rets, t.target, t.stop, 900)
}

private val CATEGORY_ORDER = listOf(
    "CLEAN_WIN", "SMALL_WIN", "WHIPSAW", "LOW_LIQUIDITY_MOVE", "NO_CONTINUATION", "DATA_INCOMPLETE"
)

/** Категория исхода (патч §10.2) — логика в OutcomeClassifier (тестируется). */
private fun outcomeCategory(o: SignalOutcome): String =
    OutcomeClassifier.category(outcomeReturns(o), o.mfePercent, o.maePercent, o.spreadBps)

private fun categoryRu(c: String): String = when (c) {
    "CLEAN_WIN" -> "Чистый вход"
    "SMALL_WIN" -> "Малый плюс"
    "WHIPSAW" -> "Пила (туда-сюда)"
    "LOW_LIQUIDITY_MOVE" -> "Плохая ликвидность"
    "NO_CONTINUATION" -> "Без продолжения"
    "DATA_INCOMPLETE" -> "Мало данных"
    else -> c
}

private fun horizonLabel(sec: Int): String = when (sec) {
    60 -> "1м"; 180 -> "3м"; 300 -> "5м"; else -> "15м"
}

// ── Двусторонний анализ (LONG + SHORT), теневые стратегии ──

private class ShadowStrategyDef(val id: String, val ru: String, val side: TradeSide)

private val SHADOW_STRATEGIES = listOf(
    ShadowStrategyDef("LONG_CONTINUATION", "LONG продолжение (ретест)", TradeSide.LONG),
    ShadowStrategyDef("LONG_STRICT", "LONG строгий", TradeSide.LONG),
    ShadowStrategyDef("PUMP_REVERSAL_SHORT", "SHORT разворот пампа", TradeSide.SHORT),
    ShadowStrategyDef("DUMP_CONTINUATION_SHORT", "SHORT продолжение дампа", TradeSide.SHORT),
    ShadowStrategyDef("DUMP_REBOUND_LONG", "LONG отскок от дна", TradeSide.LONG)
)

// Зеркальные цели: LONG «+T% раньше −S%», SHORT «−T% раньше +S%» (те же числа).
private val MIRROR_TARGETS = listOf(0.75 to 0.50, 1.00 to 0.75, 2.00 to 1.00)

private val TRAJECTORY_JSON = Json { ignoreUnknownKeys = true }

private fun shadowReturns(s: ShadowSignalEntity): List<Pair<Int, Double>> =
    CalibrationEval.checkpointReturns(
        s.referencePrice, s.price30s, s.price1m, s.price3m, s.price5m, s.price15m
    )

private fun shadowTargetHit(s: ShadowSignalEntity, target: Double, stop: Double, side: TradeSide): Boolean {
    val rets = shadowReturns(s)
    if (rets.isEmpty()) return false
    return CalibrationEval.targetBeforeStop(rets, target, stop, 900, side)
}

private fun shadowPath(s: ShadowSignalEntity): List<ExecutablePathEval.PathPoint>? {
    val pj = s.pointsJson ?: return null
    val pts = runCatching {
        TRAJECTORY_JSON.decodeFromString(ListSerializer(TrajectoryPoint.serializer()), pj)
    }.getOrNull() ?: return null
    if (pts.isEmpty()) return null
    return pts.map { ExecutablePathEval.PathPoint(it.offsetMs, it.bid, it.ask) }
}

/** Executable «цель раньше стопа» по секундной траектории при задержке реакции. */
private fun shadowExecTarget(
    s: ShadowSignalEntity, target: Double, stop: Double, side: TradeSide, reactionMs: Long
): ExecutablePathEval.Hit? {
    val path = shadowPath(s) ?: return null
    return ExecutablePathEval.evaluate(
        path, side, target, stop, reactionMs, horizonMs = 300_000, slippagePercent = s.slippagePercent
    ).hit
}

@Composable
private fun TwoSidedCard(shadows: List<ShadowSignalEntity>, noTradeCount: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Двусторонний анализ (LONG + SHORT)", fontWeight = FontWeight.Bold)
            Text("Теневые стратегии (SHADOW/PAPER): ордера не отправляются, API-ключ не нужен. " +
                "Текущий LONG-сигнал не переворачивается — SHORT считается отдельно.",
                style = MaterialTheme.typography.bodySmall)

            SHADOW_STRATEGIES.forEach { def ->
                val list = shadows.filter { it.strategy == def.id }
                Text(def.ru, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                if (list.size < 5) {
                    StatRow("Событий", "мало данных (${list.size})")
                } else {
                    StatRow("Событий", list.size.toString())
                    // Зеркальные цели по 5 контрольным точкам (грубая нижняя граница).
                    MIRROR_TARGETS.forEach { (t, stop) ->
                        val hits = list.count { shadowTargetHit(it, t, stop, def.side) }
                        val rate = hits * 100.0 / list.size
                        val lbl = if (def.side == TradeSide.LONG)
                            "+%.2f%% / −%.2f%%".format(t, stop)
                        else "−%.2f%% / +%.2f%%".format(t, stop)
                        StatRow("  $lbl", "%d/%d (%.0f%%)".format(hits, list.size, rate))
                    }
                    // Executable по секундной траектории (порядок уровней, bid/ask, спред,
                    // проскальзывание) при разных задержках реакции — только где траектория есть.
                    val withPath = list.filter { it.pointsJson != null }
                    if (withPath.size >= 5) {
                        val (t, stop) = MIRROR_TARGETS[1]  // средняя цель 1.00/0.75
                        ExecutablePathEval.REACTION_MS.forEach { rms ->
                            val hits = withPath.count {
                                shadowExecTarget(it, t, stop, def.side, rms) == ExecutablePathEval.Hit.TARGET
                            }
                            val rate = hits * 100.0 / withPath.size
                            StatRow("  exec %.1fс".format(rms / 1000.0),
                                "%d/%d (%.0f%%)".format(hits, withPath.size, rate))
                        }
                    } else {
                        Text("  секундная траектория копится (${withPath.size}/5) — " +
                            "executable по времени появится позже",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            StatRow("NO_TRADE (окна без сигнала)", noTradeCount.toString())
            Text("Вывод не по одним MFE/MAE: где есть секундная траектория — считается порядок " +
                "достижения уровней (executable) с учётом best bid/ask, спреда, проскальзывания и " +
                "задержки реакции 0.5/2/5 c; иначе — грубая оценка по 5 точкам. Это не доходность: " +
                "сделки не исполнялись.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RiskDiagnosticsCard(diag: RiskDiag) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Диагностика риск-условий", fontWeight = FontWeight.Bold)
            if (diag.total == 0) {
                Text("Пока нет снимков для диагностики.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text("По ${diag.total} последним снимкам: как часто срабатывает каждое под-условие " +
                "риск-скоров. Если условия истощения (EXH_*) почти не срабатывают — понятно, " +
                "почему exhaustionRisk ≈ 0 на чистых событиях (жёсткая фильтрация EARLY_CLEAN).",
                style = MaterialTheme.typography.bodySmall)
            RiskDiagnostics.CONDITION_IDS.forEach { id ->
                val c = diag.counts[id] ?: 0
                val rate = c * 100.0 / diag.total
                StatRow(RiskDiagnostics.ru(id), "%d/%d (%.0f%%)".format(c, diag.total, rate))
            }
        }
    }
}

@Composable
private fun CalibrationCard(outcomes: List<SignalOutcome>) {
    var target by rememberSaveable { mutableStateOf(2.0) }
    var stop by rememberSaveable { mutableStateOf(1.0) }
    var horizon by rememberSaveable { mutableStateOf(900) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Настраиваемый критерий", fontWeight = FontWeight.Bold)
            ChoiceRow("Цель", listOf(1.0, 2.0, 3.0, 5.0), target, { "+%.0f%%".format(it) }) { target = it }
            ChoiceRow("Стоп", listOf(1.0, 2.0, 3.0), stop, { "−%.0f%%".format(it) }) { stop = it }
            HorizonRow(horizon) { horizon = it }

            val evaluable = outcomes.filter { outcomeReturns(it).isNotEmpty() }
            val hits = evaluable.count {
                CalibrationEval.targetBeforeStop(outcomeReturns(it), target, stop, horizon)
            }
            val rate = if (evaluable.isNotEmpty()) hits * 100.0 / evaluable.size else 0.0
            StatRow(
                "Цель +%.0f%% раньше −%.0f%% за %s".format(target, stop, horizonLabel(horizon)),
                "%d/%d (%.0f%%)".format(hits, evaluable.size, rate)
            )
            Text("Грубая оценка по 5 контрольным точкам (нижняя граница — межточечные всплески " +
                "не видны). Подбирай цель/стоп/горизонт под свою стратегию и смотри, где процент " +
                "попаданий становится приемлемым.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Double>,
    selected: Double,
    fmt: (Double) -> String,
    onSelect: (Double) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
        options.forEach { v ->
            FilterChip(selected = selected == v, onClick = { onSelect(v) }, label = { Text(fmt(v)) })
        }
    }
}

@Composable
private fun HorizonRow(selected: Int, onSelect: (Int) -> Unit) {
    val opts = listOf(60 to "1м", 180 to "3м", 300 to "5м", 900 to "15м")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Гориз.", Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
        opts.forEach { (sec, lbl) ->
            FilterChip(selected = selected == sec, onClick = { onSelect(sec) }, label = { Text(lbl) })
        }
    }
}
