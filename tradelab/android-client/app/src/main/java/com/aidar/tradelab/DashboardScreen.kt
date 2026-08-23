package com.aidar.tradelab

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardScreen(private val activity: MainActivity) {
    private val api = ApiClient(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var inflight = false

    private val root = ScrollView(activity)
    private val content = LinearLayout(activity)
    private lateinit var updatedView: TextView

    init {
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 24))
        content.orientation = LinearLayout.VERTICAL
        root.addView(content)
        renderLoading()
    }

    fun view(): View = root

    fun refresh(force: Boolean = true) {
        if (inflight && !force) return
        inflight = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Triple(api.health(), api.tournament(), api.marketStatus())
                } catch (e: Exception) {
                    e
                }
            }
            inflight = false
            when (result) {
                is Exception -> renderError(result)
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    render(result as Triple<HealthInfo, Tournament, MarketStatus>)
                }
            }
        }
    }

    private fun renderLoading() {
        content.removeAllViews()
        content.addView(headerCard("Загрузка…"))
        for (i in 0 until 3) {
            content.addView(Ui.spacer(activity, 10))
            content.addView(skeletonCard())
        }
    }

    private fun skeletonCard(): View {
        val c = Ui.card(activity)
        c.addView(Ui.text(activity, "···", 18, Ui.MUTED, mono = true))
        return c
    }

    private fun headerCard(text: String): View {
        val c = Ui.card(activity)
        c.addView(Ui.text(activity, text, 16, Ui.TEXT, bold = true))
        return c
    }

    private fun renderError(e: Exception) {
        content.removeAllViews()
        val c = Ui.card(activity)
        c.addView(Ui.text(activity, "Не удалось получить данные", 16, Ui.RED, bold = true))
        c.addView(Ui.spacer(activity, 6))
        c.addView(Ui.text(activity, friendly(e), 14, Ui.MUTED))
        c.addView(Ui.spacer(activity, 10))
        val retry = Button(activity).apply { text = "Повторить" }
        retry.setOnClickListener { refresh() }
        c.addView(retry)
        content.addView(c)
    }

    private fun friendly(e: Exception): String = when {
        e is ApiException && e.httpCode == 401 -> "Неверный read token. Проверь его в Настройках."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun render(data: Triple<HealthInfo, Tournament, MarketStatus>) {
        val (health, tournament, market) = data
        content.removeAllViews()
        val now = System.currentTimeMillis()

        // --- header ---
        val head = Ui.card(activity)
        head.addView(Ui.text(activity, "TradeLab · теневой турнир", 17, Ui.TEXT, bold = true))
        head.addView(Ui.spacer(activity, 4))
        head.addView(Ui.text(activity, health.epochId.ifEmpty { "эпоха неизвестна" }, 13, Ui.ACCENT, mono = true))
        head.addView(Ui.spacer(activity, 8))
        val chips = Ui.row(activity)
        chips.gravity = Gravity.TOP
        chips.addView(
            Ui.pill(
                activity,
                if (health.ok) "API OK" else "API ?",
                if (health.ok) Ui.GREEN else Ui.AMBER,
            )
        )
        chips.addView(Ui.hspace(activity, 6))
        chips.addView(
            Ui.pill(
                activity,
                if (market.recorderEnabled == true) "REC ON" else "REC OFF",
                if (market.recorderEnabled == true) Ui.GREEN else Ui.RED,
            )
        )
        chips.addView(Ui.hspace(activity, 6))
        chips.addView(Ui.pill(activity, "v" + health.version, Ui.MUTED))
        head.addView(chips)
        head.addView(Ui.spacer(activity, 6))
        head.addView(
            Ui.text(
                activity,
                "Последний сэмпл: ${Ui.ageText(health.lastSampleMs, now)}",
                13, Ui.MUTED,
            )
        )
        val badComps = market.components.filter { it.status !in setOf("OK", "CONNECTED") }
        if (badComps.isNotEmpty()) {
            head.addView(Ui.spacer(activity, 4))
            head.addView(
                Ui.text(
                    activity,
                    "⚠ Компоненты не в норме: " + badComps.joinToString(", ") { it.component },
                    13, Ui.AMBER,
                )
            )
        }
        head.addView(Ui.spacer(activity, 4))
        updatedView = Ui.text(activity, "", 12, Ui.MUTED)
        head.addView(updatedView)
        content.addView(head)

        // --- participants by equity desc ---
        val rows = tournament.participants.sortedByDescending { it.equity }
        var place = 1
        for (p in rows) {
            content.addView(Ui.spacer(activity, 10))
            content.addView(participantCard(p, place))
            place++
        }
        if (rows.isEmpty()) {
            content.addView(Ui.spacer(activity, 10))
            content.addView(headerCard("Участники: нет данных"))
        }
        updatedView.text = "Обновлено: ${Ui.ageText(now, now)}"
    }

    private fun participantCard(p: StatRow, place: Int): View {
        val c = Ui.card(activity)
        val pnl = p.netPnlUsdt
        val equityDelta = p.equity - 20.0
        val name = p.displayName ?: p.participantId

        val top = Ui.row(activity)
        top.addView(Ui.text(activity, "#$place", 14, Ui.MUTED, bold = true, mono = true))
        top.addView(Ui.hspace(activity, 8))
        top.addView(Ui.text(activity, name, 16, Ui.TEXT, bold = true))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        c.addView(top, lp)

        c.addView(Ui.spacer(activity, 6))

        val eqRow = Ui.row(activity)
        eqRow.addView(kv("Equity", "%.2f$".format(p.equity), Ui.TEXT), weight1())
        eqRow.addView(kv("PnL", Ui.signed(pnl, 3, "$"), Ui.pnlColor(pnl)), weight1())
        eqRow.addView(kv("Δ к старту", Ui.signed(equityDelta, 2, "$"), Ui.pnlColor(equityDelta)), weight1())
        c.addView(eqRow)

        c.addView(Ui.spacer(activity, 6))

        val wr = if (p.closedTrades > 0) 100.0 * p.winners / p.closedTrades else Double.NaN
        val statRow = Ui.row(activity)
        statRow.addView(kv("Сделки", "${p.closedTrades}з/${p.openTrades}о", Ui.TEXT), weight1())
        statRow.addView(kv("Winrate", if (wr.isNaN()) "—" else "%.0f%%".format(wr), Ui.TEXT), weight1())
        statRow.addView(
            kv(
                "Avg net",
                if (p.meanNetReturnPct == null) "—" else Ui.signed(p.meanNetReturnPct!!, 4, "%"),
                p.meanNetReturnPct?.let { Ui.pnlColor(it) } ?: Ui.MUTED,
            ),
            weight1(),
        )
        c.addView(statRow)

        val pfText = if (p.profitFactor == null || p.profitFactor <= 0) "—" else "%.2f".format(p.profitFactor)
        c.addView(Ui.spacer(activity, 6))
        val bottom = Ui.row(activity)
        bottom.addView(Ui.text(activity, "${p.role ?: ""} · PF $pfText", 12, Ui.MUTED))
        bottom.addView(
            Ui.pill(
                activity,
                if ((p.role ?: "") == "ELIMINATED") "OUT" else "CANDIDATE",
                if ((p.role ?: "") == "ELIMINATED") Ui.MUTED else Ui.GREEN,
            )
        )
        c.addView(bottom)
        return c
    }

    private fun kv(label: String, value: String, color: Int): View {
        val box = Ui.column(activity)
        box.addView(Ui.text(activity, label, 11, Ui.MUTED))
        box.addView(Ui.text(activity, value, 15, color, bold = true, mono = true))
        return box
    }

    private fun weight1(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
