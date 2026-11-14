package com.lampplayer.tv.center

import android.content.Context
import com.google.gson.Gson
import java.security.MessageDigest

/** Keeps the last successful home page for each shelf and catalog address. */
internal class CenterShelfCache(context: Context) {
    private val prefs = context.getSharedPreferences("center_home_pages", Context.MODE_PRIVATE)
    private val gson = Gson()

    data class Page(
        val at: Long,
        val items: List<CenterTitle>,
        val hasMore: Boolean?,
        val sourcePage: Int?
    ) {
        fun titles(): List<CenterTitle> =
            if (hasMore == null) items else CenterTitlePage(items, hasMore, sourcePage)
    }

    private data class Entry(val address: String, val page: Page)

    fun address(host: String): String = MessageDigest.getInstance("SHA-256")
        .digest(host.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun read(provider: String, address: String, section: CenterSection): Page? = runCatching {
        val json = prefs.getString("${provider}_${section.id}", null) ?: return null
        val entry = gson.fromJson(json, Entry::class.java)
        entry.page.takeIf { entry.address == address && it.items.isNotEmpty() }
    }.getOrNull()

    fun save(provider: String, address: String, section: CenterSection, titles: List<CenterTitle>, at: Long) {
        val key = "${provider}_${section.id}"
        if (titles.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val result = titles as? CenterTitlePage
        val cards = titles.take(60).map { it.copy(overview = it.overview.take(1200)) }
        val page = Page(at, cards, result?.hasMore, result?.sourcePage)
        prefs.edit().putString(key, gson.toJson(Entry(address, page))).apply()
    }
}
