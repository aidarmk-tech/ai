package com.adshield.vpn

import android.content.Context
import java.io.File

/** A toggleable category of blocked domains, backed by a downloadable list. */
data class BlocklistSource(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val defaultEnabled: Boolean,
)

/** The catalog of filter categories shown as checkboxes in the UI. */
object BlocklistCatalog {

    val sources: List<BlocklistSource> = listOf(
        BlocklistSource(
            id = "ads",
            title = "Реклама",
            description = "Баннеры и реклама в приложениях и сайтах (AdAway)",
            url = "https://adaway.org/hosts.txt",
            defaultEnabled = true,
        ),
        BlocklistSource(
            id = "tracking",
            title = "Трекеры и аналитика",
            description = "Слежка и сбор статистики (EasyPrivacy)",
            url = "https://v.firebog.net/hosts/Easyprivacy.txt",
            defaultEnabled = true,
        ),
        BlocklistSource(
            id = "ru_ads",
            title = "Российская реклама",
            description = "Реклама и трекеры рунета (NoADS_RU Extended)",
            url = "https://raw.githubusercontent.com/Zalexanninev15/NoADS_RU/main/ads_list_extended.txt",
            defaultEnabled = true,
        ),
        BlocklistSource(
            id = "malware",
            title = "Малварь и фишинг",
            description = "Вредоносные и мошеннические домены (Spam404)",
            url = "https://raw.githubusercontent.com/Spam404/lists/master/main-blacklist.txt",
            defaultEnabled = false,
        ),
        BlocklistSource(
            id = "annoyance",
            title = "Назойливое и соц-виджеты",
            description = "Всплывающие окна, кнопки соцсетей (Prigent)",
            url = "https://v.firebog.net/hosts/Prigent-Ads.txt",
            defaultEnabled = false,
        ),
    )

    fun byId(id: String): BlocklistSource? = sources.firstOrNull { it.id == id }

    val defaultEnabledIds: Set<String>
        get() = sources.filter { it.defaultEnabled }.map { it.id }.toSet()

    /** On-disk location of a downloaded source list. */
    fun fileFor(ctx: Context, source: BlocklistSource): File {
        val dir = File(ctx.filesDir, "lists").apply { mkdirs() }
        return File(dir, "${source.id}.txt")
    }

    /** On-disk location of a user-added custom list URL. */
    fun fileForCustom(ctx: Context, url: String): File {
        val dir = File(ctx.filesDir, "lists").apply { mkdirs() }
        return File(dir, "custom_${url.hashCode().toUInt()}.txt")
    }
}

/**
 * A selectable upstream resolver. [key] is either a plain IPv4 address (UDP
 * DNS) or an `https://<ip>/dns-query` URL (DNS-over-HTTPS). DoH endpoints use
 * IP literals on purpose so resolving them never loops back into our own VPN.
 */
data class DnsServer(val title: String, val key: String) {
    val isDoh: Boolean get() = key.startsWith("https://")
}

object DnsServers {
    val all: List<DnsServer> = listOf(
        DnsServer("Cloudflare (1.1.1.1)", "1.1.1.1"),
        DnsServer("Google (8.8.8.8)", "8.8.8.8"),
        DnsServer("Quad9 (9.9.9.9)", "9.9.9.9"),
        DnsServer("AdGuard DNS (94.140.14.14)", "94.140.14.14"),
        DnsServer("Яндекс (77.88.8.8)", "77.88.8.8"),
        DnsServer("Cloudflare DoH (шифрование)", "https://1.1.1.1/dns-query"),
        DnsServer("Google DoH (шифрование)", "https://8.8.8.8/dns-query"),
    )

    fun indexOfKey(key: String): Int = all.indexOfFirst { it.key == key }.coerceAtLeast(0)
}
