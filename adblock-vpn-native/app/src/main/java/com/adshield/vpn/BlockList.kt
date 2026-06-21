package com.adshield.vpn

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStream

/**
 * The set of domains that should be answered with 0.0.0.0 (i.e. blocked).
 *
 * The list is the union of:
 *  - the bundled offline list in assets/hosts.txt, and
 *  - an optional downloaded list in filesDir/hosts_downloaded.txt.
 *
 * Matching is suffix based: blocking `doubleclick.net` also blocks
 * `ad.g.doubleclick.net`, which is how ad domains actually appear in the wild.
 */
class BlockList private constructor(private val domains: HashSet<String>) {

    val size: Int get() = domains.size

    /** True if [host] or any of its parent domains is on the blocklist. */
    fun isBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        var name = host.lowercase().trimEnd('.')
        while (true) {
            if (domains.contains(name)) return true
            val dot = name.indexOf('.')
            if (dot < 0) return false
            name = name.substring(dot + 1)
        }
    }

    companion object {
        const val DOWNLOADED_FILE = "hosts_downloaded.txt"

        fun load(ctx: Context): BlockList {
            val set = HashSet<String>(1 shl 16)
            // Bundled offline list.
            runCatching {
                ctx.assets.open("hosts.txt").use { parse(it, set) }
            }
            // Optional downloaded list.
            val downloaded = File(ctx.filesDir, DOWNLOADED_FILE)
            if (downloaded.exists()) {
                runCatching { downloaded.inputStream().use { parse(it, set) } }
            }
            return BlockList(set)
        }

        /**
         * Parse a hosts-format or plain-domain-list stream into [out].
         * Accepts lines like `0.0.0.0 ads.example.com`, `127.0.0.1 x.com`
         * or a bare `ads.example.com`. Comments (`#`) and `localhost` skipped.
         */
        private fun parse(stream: InputStream, out: HashSet<String>) {
            stream.bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { raw ->
                    var line = raw
                    val hash = line.indexOf('#')
                    if (hash >= 0) line = line.substring(0, hash)
                    line = line.trim()
                    if (line.isEmpty()) return@forEachLine
                    val parts = line.split(Regex("\\s+"))
                    val domain = when {
                        parts.size >= 2 -> parts[1]
                        else -> parts[0]
                    }.lowercase().trimEnd('.')
                    if (domain.isEmpty() || domain == "localhost" ||
                        domain == "localhost.localdomain" || domain == "broadcasthost" ||
                        !domain.contains('.')
                    ) return@forEachLine
                    out.add(domain)
                }
            }
        }
    }
}
