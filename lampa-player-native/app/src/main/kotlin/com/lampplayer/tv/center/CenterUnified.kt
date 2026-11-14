package com.lampplayer.tv.center

/**
 * Одна карточка на одно произведение.
 *
 * Карточки сайтов (KinoGo, KinoKong, Zona…) привязываются к карточке TMDB, если совпадают название, тип и год.
 * Однозначная привязка сохраняется как источник этой карточки TMDB — тогда в ней сразу есть «Смотреть · сайт»,
 * а не отдельная карточка на каждый сайт. Произведения, которых нет в TMDB, объединяются между сайтами по тем же правилам.
 * Идентификатор сайта никогда не подставляется как TMDB ID.
 */
data class MergedTitle(
    val main: CenterTitle,
    /** Карточки сайтов, найденные для этого произведения (по одной на сайт). */
    val sources: List<CenterTitle>,
    /** Источники, совпавшие однозначно (название + тип + год): их можно сохранить как выбор для карточки TMDB. */
    val confirmed: List<CenterTitle>,
) {
    val providers get() = (listOfNotNull(main.provider ?: "tmdb") + sources.mapNotNull { it.provider }).distinct()
}

object CenterUnified {
    private fun closeYear(a: String, b: String) =
        a.isBlank() || b.isBlank() || (a.toIntOrNull() != null && b.toIntOrNull() != null && kotlin.math.abs(a.toInt() - b.toInt()) <= 1)

    /**
     * Сводит выдачу TMDB и сайтов. Порядок: карточки TMDB в порядке TMDB, затем произведения, найденные только на сайтах.
     * Сайт привязывается к TMDB, только если подходит ровно одна карточка TMDB — иначе остаётся отдельной.
     */
    fun merge(tmdb: List<CenterTitle>, sites: Map<String, List<CenterTitle>>): List<MergedTitle> {
        val anchors = tmdb.filter { it.provider == null }.distinctBy { it.key }
        val attached = anchors.associate { it.key to mutableListOf<CenterTitle>() }
        val confirmed = anchors.associate { it.key to mutableListOf<CenterTitle>() }
        val leftovers = mutableListOf<CenterTitle>()
        for ((_, items) in sites) for (site in items) {
            if (site.provider == null) continue
            val exact = anchors.filter { CenterOnlineMatch.exact(it, site) }
            if (exact.size == 1) {
                val anchor = exact.single()
                attached.getValue(anchor.key) += site
                confirmed.getValue(anchor.key) += if (site.type != anchor.type) site.copy(type = anchor.type) else site
                continue
            }
            // Нестрогое совпадение (год ±1 или не указан) только объединяет карточки на экране, но не сохраняется.
            val loose = if (exact.isEmpty()) anchors.filter {
                CenterOnlineMatch.sameName(it, site) && closeYear(it.year, site.year) &&
                    (it.type == site.type || site.provider in CenterOnlineMatch.guessedTypeProviders)
            } else emptyList()
            if (loose.size == 1) attached.getValue(loose.single().key) += site else leftovers += site
        }
        val merged = anchors.map { anchor ->
            MergedTitle(anchor, attached.getValue(anchor.key).distinctBy { it.provider },
                confirmed.getValue(anchor.key).distinctBy { it.provider })
        }.toMutableList()
        // Только на сайтах: группируем одинаковые произведения разных сайтов в одну карточку.
        val groups = mutableListOf<MutableList<CenterTitle>>()
        for (site in leftovers) {
            val group = groups.firstOrNull { g -> g.none { it.provider == site.provider } && g.all { other ->
                CenterOnlineMatch.sameName(other, site) && closeYear(other.year, site.year) &&
                    (other.type == site.type || other.provider in CenterOnlineMatch.guessedTypeProviders || site.provider in CenterOnlineMatch.guessedTypeProviders)
            } }
            if (group != null) group += site else groups += mutableListOf(site)
        }
        groups.forEach { group -> merged += MergedTitle(group.first(), group.drop(1), emptyList()) }
        return merged
    }

    /** Подпись карточки: тип, год и где найдено — «Сериал · 2022 · TMDB, KinoGo, Zona». */
    fun label(title: MergedTitle): String = listOf(
        if (title.main.type == "tv") "Сериал" else "Фильм", title.main.year,
        title.providers.joinToString(", ") { CenterCatalog.name(it) },
    ).filter { it.isNotBlank() }.joinToString(" · ")
}
