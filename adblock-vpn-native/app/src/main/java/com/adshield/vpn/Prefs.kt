package com.adshield.vpn

import android.content.Context

/** SharedPreferences wrapper for everything we persist. */
object Prefs {
    private const val FILE = "adshield_prefs"
    private const val KEY_UPSTREAM = "upstream_dns"
    private const val KEY_LAST_UPDATE = "last_list_update"
    private const val KEY_ENABLED_SOURCES = "enabled_sources"
    private const val KEY_WHITELIST = "whitelist"

    const val DEFAULT_UPSTREAM = "1.1.1.1"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Upstream DNS ---
    fun upstreamDns(ctx: Context): String =
        prefs(ctx).getString(KEY_UPSTREAM, DEFAULT_UPSTREAM) ?: DEFAULT_UPSTREAM

    fun setUpstreamDns(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_UPSTREAM, value).apply()

    // --- Last update timestamp ---
    fun lastUpdate(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_UPDATE, 0L)

    fun setLastUpdate(ctx: Context, time: Long) =
        prefs(ctx).edit().putLong(KEY_LAST_UPDATE, time).apply()

    // --- Enabled filter categories ---
    fun enabledSources(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_ENABLED_SOURCES, null)?.toSet()
            ?: BlocklistCatalog.defaultEnabledIds

    fun setSourceEnabled(ctx: Context, id: String, enabled: Boolean) {
        val current = enabledSources(ctx).toMutableSet()
        if (enabled) current.add(id) else current.remove(id)
        prefs(ctx).edit().putStringSet(KEY_ENABLED_SOURCES, current).apply()
    }

    // --- Whitelist (always-allowed domains) ---
    fun whitelist(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_WHITELIST, null)?.toSet() ?: emptySet()

    fun addToWhitelist(ctx: Context, domain: String) {
        val d = normalize(domain) ?: return
        val current = whitelist(ctx).toMutableSet()
        current.add(d)
        prefs(ctx).edit().putStringSet(KEY_WHITELIST, current).apply()
    }

    fun removeFromWhitelist(ctx: Context, domain: String) {
        val current = whitelist(ctx).toMutableSet()
        current.remove(domain)
        prefs(ctx).edit().putStringSet(KEY_WHITELIST, current).apply()
    }

    /** Lowercase, strip scheme/path/leading dots; null if it isn't a domain. */
    fun normalize(raw: String): String? {
        var d = raw.trim().lowercase()
        if (d.isEmpty()) return null
        d = d.substringAfter("://")
        d = d.substringBefore('/')
        d = d.substringBefore(':')
        d = d.trim('.', ' ')
        return if (d.contains('.') && !d.contains(' ')) d else null
    }
}
