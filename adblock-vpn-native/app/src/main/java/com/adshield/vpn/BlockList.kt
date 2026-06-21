package com.adshield.vpn

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream

/**
 * The effective set of domains to answer with 0.0.0.0 (blocked).
 *
 * Built from:
 *  - the bundled offline list in assets/hosts.txt (only when "ads" is on), and
 *  - every enabled category whose list has been downloaded.
 *
 * A whitelist of always-allowed domains overrides the blocklist. Matching is
 * suffix based: blocking `doubleclick.net` also blocks `ad.g.doubleclick.net`.
 */
class BlockList private constructor(
    private val domains: HashSet<String>,
    private val whitelist: Set<String>,
) {

    val size: Int get() = domains.size

    fun isBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        val name = host.lowercase().trimEnd('.')
        if (matches(name, whitelist)) return false
        return matches(name, domains)
    }

    private fun matches(host: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var name = host
        while (true) {
            if (set.contains(name)) return true
            val dot = name.indexOf('.')
            if (dot < 0) return false
            name = name.substring(dot + 1)
        }
    }

    companion object {
        fun load(ctx: Context): BlockList {
            val set = HashSet<String>(1 shl 16)
            val enabled = Prefs.enabledSources(ctx)

            // Bundled offline ads list is the baseline for the "ads" category.
            if (enabled.contains("ads")) {
                runCatching { ctx.assets.open("hosts.txt").use { parse(it, set) } }
            }
            // Each enabled category that has a downloaded file.
            for (id in enabled) {
                val source = BlocklistCatalog.byId(id) ?: continue
                val file = BlocklistCatalog.fileFor(ctx, source)
                if (file.exists()) {
                    runCatching { file.inputStream().use { parse(it, set) } }
                }
            }

            val whitelist = Prefs.whitelist(ctx).map { it.lowercase() }.toSet()
            return BlockList(set, whitelist)
        }

        /**
         * Parse a hosts-format or plain-domain-list stream into [out].
         * Accepts `0.0.0.0 ads.example.com`, `127.0.0.1 x.com` or a bare
         * `ads.example.com`. Comments (`#`, `!`) and non-domains are skipped.
         */
        fun parse(stream: InputStream, out: HashSet<String>) {
            stream.bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { raw ->
                    var line = raw
                    val hash = line.indexOf('#')
                    if (hash >= 0) line = line.substring(0, hash)
                    line = line.trim()
                    if (line.isEmpty() || line.startsWith("!")) return@forEachLine
                    val parts = line.split(Regex("\\s+"))
                    val domain = (if (parts.size >= 2) parts[1] else parts[0])
                        .lowercase().trimEnd('.')
                    if (domain.isEmpty() || domain == "localhost" ||
                        domain == "localhost.localdomain" || domain == "broadcasthost" ||
                        domain == "0.0.0.0" || !domain.contains('.') || domain.contains('/')
                    ) return@forEachLine
                    out.add(domain)
                }
            }
        }
    }
}
