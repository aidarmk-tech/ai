package com.lampplayer.tv.center

import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/*
 * «Поиск везде»: один запрос уходит во все каталоги поиска параллельно.
 * Каждый каталог получает свой ряд и заполняет его, как только ответит, — медленный сайт не задерживает остальные.
 * Ответы держатся в памяти до смены запроса, поэтому возврат из карточки не повторяет поиск.
 */

/** Сколько ждать один каталог, прежде чем показать «не ответил». */
internal const val SEARCH_EVERYWHERE_TIMEOUT_MS = 15_000L

internal sealed class EverywhereResult {
    data class Found(val items: List<CenterTitle>) : EverywhereResult()
    object Timeout : EverywhereResult()
    data class Failed(val message: String) : EverywhereResult()
}

internal fun MediaCenterActivity.searchEverywhere(term: String) {
    job?.cancel(); currentScreen = { searchEverywhere(term) }; page = "search_everywhere"
    clear("Поиск везде · «$term»")
    if (everywhereTerm != term) { everywhereTerm = term; everywhereResults.clear() }
    val providers = CenterCatalog.searchProviders
    val status = text("", 14, muted).also { content.addView(it) }
    space(content, 10)
    val slots = providers.associateWith { provider ->
        column().also { slot ->
            content.addView(slot)
            moveInto(slot) { section(CenterCatalog.name(provider)); content.addView(text("Ищу…", 14, muted)); space(content, 14) }
        }
    }
    content.addView(button("Искать только в одном каталоге") { store.searchEverywhere = false; searchScreen(term) }
        .apply { tag = "search_everywhere_single" })

    fun refreshStatus() {
        val done = providers.count { it in everywhereResults }
        val found = everywhereResults.values.sumOf { (it as? EverywhereResult.Found)?.items?.size ?: 0 }
        status.text = if (done < providers.size) "Ответили $done из ${providers.size} каталогов · найдено $found"
            else if (found == 0) "Ничего не найдено ни в одном каталоге" else "Найдено $found в ${providers.size} каталогах"
    }
    fun show(provider: String, result: EverywhereResult) {
        val slot = slots.getValue(provider)
        val hadFocus = slot.hasFocus()
        slot.removeAllViews()
        moveInto(slot) {
            when (result) {
                is EverywhereResult.Found -> if (result.items.isEmpty()) {
                    section(CenterCatalog.name(provider)); content.addView(text("Ничего не найдено", 14, muted))
                } else {
                    section(CenterCatalog.name(provider), result.items.size)
                    shelf(result.items, "everywhere:$provider:")
                }
                EverywhereResult.Timeout -> {
                    section(CenterCatalog.name(provider))
                    content.addView(text("Не ответил за ${SEARCH_EVERYWHERE_TIMEOUT_MS / 1000} с", 14, muted))
                    content.addView(button("Повторить · ${CenterCatalog.name(provider)}") {
                        everywhereResults.remove(provider); searchEverywhere(term)
                    }.apply { tag = "everywhere_retry:$provider" })
                }
                is EverywhereResult.Failed -> {
                    section(CenterCatalog.name(provider)); content.addView(text("Ошибка: ${result.message}", 14, muted))
                }
            }
            space(content, 14)
        }
        refreshStatus()
        // Первый найденный ряд получает фокус, если пользователь ещё никуда не перешёл.
        val focusFree = content.findFocus() == null || hadFocus
        if (focusFree && result is EverywhereResult.Found && result.items.isNotEmpty())
            slot.post { slot.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus() }
    }

    providers.filter { it in everywhereResults }.forEach { show(it, everywhereResults.getValue(it)) }
    refreshStatus()
    val pending = providers.filterNot { it in everywhereResults }
    if (pending.isEmpty()) return
    job = lifecycleScope.launch {
        supervisorScope {
            pending.forEach { provider ->
                launch {
                    val result = try {
                        withTimeoutOrNull(SEARCH_EVERYWHERE_TIMEOUT_MS) {
                            EverywhereResult.Found(searchCatalogPage(provider, CenterBrowse(type = "all", query = term), 1))
                        } ?: EverywhereResult.Timeout
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { EverywhereResult.Failed(e.message ?: e.javaClass.simpleName) }
                    // Тайм-аут не кэшируется: при возврате на экран каталог опрашивается снова.
                    if (result !is EverywhereResult.Timeout) everywhereResults[provider] = result
                    if (page == "search_everywhere" && everywhereTerm == term) show(provider, result)
                }
            }
        }
    }
}

/** Рисует элементы обычными помощниками экрана и переносит их из `content` в [slot]. */
private fun MediaCenterActivity.moveInto(slot: LinearLayout, draw: () -> Unit) {
    val start = content.childCount
    draw()
    while (content.childCount > start) {
        val view = content.getChildAt(start); content.removeViewAt(start); slot.addView(view)
    }
}
