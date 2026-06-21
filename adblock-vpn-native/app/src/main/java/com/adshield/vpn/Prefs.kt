package com.adshield.vpn

import android.content.Context

/** Tiny SharedPreferences wrapper for the handful of settings we persist. */
object Prefs {
    private const val FILE = "adshield_prefs"
    private const val KEY_UPSTREAM = "upstream_dns"
    private const val KEY_LAST_UPDATE = "last_list_update"
    private const val KEY_DOWNLOADED_COUNT = "downloaded_count"

    const val DEFAULT_UPSTREAM = "1.1.1.1"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun upstreamDns(ctx: Context): String =
        prefs(ctx).getString(KEY_UPSTREAM, DEFAULT_UPSTREAM) ?: DEFAULT_UPSTREAM

    fun setUpstreamDns(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_UPSTREAM, value).apply()

    fun lastUpdate(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_UPDATE, 0L)

    fun setLastUpdate(ctx: Context, time: Long) =
        prefs(ctx).edit().putLong(KEY_LAST_UPDATE, time).apply()

    fun downloadedCount(ctx: Context): Int = prefs(ctx).getInt(KEY_DOWNLOADED_COUNT, 0)

    fun setDownloadedCount(ctx: Context, count: Int) =
        prefs(ctx).edit().putInt(KEY_DOWNLOADED_COUNT, count).apply()
}
