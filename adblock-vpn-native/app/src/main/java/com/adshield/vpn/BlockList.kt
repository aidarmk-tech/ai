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
         * Parse a blocklist stream into [out]. Understands three formats:
         *  - hosts files: `0.0.0.0 ads.example.com` / `127.0.0.1 x.com`
         *  - plain domain lists: `ads.example.com`
         *  - AdGuard/uBlock filter lists: `||ads.example.com^`
         *
         * For AdGuard syntax we only take rules that block a WHOLE domain
         * (`||domain^` or `||domain$modifiers`). Cosmetic rules (`##`), path
         * rules (`||site.com/ads.js`), wildcards and exceptions (`@@`) are
         * skipped — a DNS blocker can't honour them, and blindly taking the
         * domain from a path rule would break legitimate sites (e.g. vk.com).
         */
        fun parse(stream: InputStream, out: HashSet<String>) {
            stream.bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { raw ->
                    val domain = domainFromLine(raw) ?: return@forEachLine
                    out.add(domain)
                }
            }
        }

        private val DOMAIN_RE = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")
        private val IPV4_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        private fun isIpAddress(s: String): Boolean =
            IPV4_RE.matches(s) || s == "::1" || s == "::"

        /** Extract a blockable domain from a single list line, or null. */
        fun domainFromLine(raw: String): String? {
            val line = raw.trim()
            if (line.isEmpty()) return null
            // Comments / headers / exceptions / cosmetic rules.
            if (line[0] == '!' || line[0] == '#' || line[0] == '[') return null
            if (line.startsWith("@@")) return null
            if (line.contains("##") || line.contains("#@#") ||
                line.contains("#?#") || line.contains("#\$#")
            ) return null

            val candidate: String = when {
                // AdGuard/uBlock network rule.
                line.startsWith("||") -> {
                    val s = line.substring(2)
                    val end = s.indexOfFirst { it == '^' || it == '/' || it == '$' || it == '*' || it == '?' }
                    when {
                        end < 0 -> s                         // ||domain
                        s[end] == '^' || s[end] == '$' -> {
                            // Modifiers after the domain. Context-specific ones
                            // (`$domain=...`, negations `~...`) can't be honoured
                            // by DNS and would over-block legitimate sites, so we
                            // only take the domain when none are present.
                            val mods = if (s[end] == '^') s.substring(end + 1) else s.substring(end)
                            if (mods.contains("domain=") || mods.contains("~")) return null
                            s.substring(0, end)
                        }
                        else -> return null                  // path rule / wildcard → skip
                    }
                }
                // hosts format "<ip> <domain>" or a bare domain.
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2 && isIpAddress(parts[0])) parts[1] else parts[0]
                }
            }

            val domain = candidate.lowercase().trim('.', ' ')
            if (domain == "localhost" || domain == "localhost.localdomain" ||
                domain == "broadcasthost" || domain == "0.0.0.0"
            ) return null
            return if (DOMAIN_RE.matches(domain)) domain else null
        }
    }
}
