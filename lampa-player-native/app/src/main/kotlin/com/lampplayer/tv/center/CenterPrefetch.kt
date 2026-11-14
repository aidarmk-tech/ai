package com.lampplayer.tv.center

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/*
 * Предзагрузка совпадений: пока открыта карточка TMDB, фильм ищется на всех онлайн-сайтах в фоне.
 * К нажатию «Смотреть онлайн» результаты уже готовы, а в списке источников видно, где карточка нашлась.
 * Это только поиск по названию (несколько лёгких запросов); видео и плееры сайтов не загружаются.
 */

/** Результат поиска на одном сайте: null — сайт не ответил или вернул ошибку. */
internal class MatchPrefetch(val at: Long, val results: Deferred<Map<String, List<CenterTitle>?>>)

internal const val MATCH_PREFETCH_TTL_MS = 10 * 60_000L

internal fun MediaCenterActivity.prefetchMatches(item: CenterTitle) {
    if (!prefetchEnabled || item.provider != null || item.id <= 0 || item.type !in listOf("movie", "tv")) return
    val now = System.currentTimeMillis()
    matchPrefetch[item.key]?.takeIf { now - it.at < MATCH_PREFETCH_TTL_MS && !it.results.isCancelled }?.let { return }
    val queries = CenterOnlineMatch.queries(item)
    val deferred = lifecycleScope.async {
        supervisorScope {
            CenterOnlineMatch.providers.map { provider ->
                async {
                    provider to try {
                        queries.map { term -> async { searchSite(provider, term) } }.awaitAll().flatten()
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                }
            }.awaitAll().toMap()
        }
    }
    if (matchPrefetch.size >= 6) matchPrefetch.remove(matchPrefetch.keys.first())
    matchPrefetch[item.key] = MatchPrefetch(now, deferred)
}

/** Готовые (или ещё идущие) результаты предзагрузки для сайта; null — предзагрузки нет или сайт не ответил. */
internal suspend fun MediaCenterActivity.prefetchedMatches(item: CenterTitle, provider: String): List<CenterTitle>? {
    val entry = matchPrefetch[item.key]?.takeIf { System.currentTimeMillis() - it.at < MATCH_PREFETCH_TTL_MS } ?: return null
    return try { entry.results.await()[provider] } catch (e: CancellationException) {
        if (entry.results.isCancelled) null else throw e
    }
}

/** Короткая отметка для кнопки сайта в списке источников. */
internal fun prefetchLabel(item: CenterTitle, results: List<CenterTitle>?): String = when {
    results == null -> ""
    CenterOnlineMatch.automatic(item, results) != null -> " · карточка найдена"
    CenterOnlineMatch.candidates(item, results).isNotEmpty() -> " · есть похожие"
    else -> " · не найдено"
}
