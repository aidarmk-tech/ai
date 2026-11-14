package com.lampplayer.tv.center

import java.text.Normalizer
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Matching never treats a provider's native ID as a TMDB ID. */
object CenterOnlineMatch {
    val providers=listOf("kinovod","kinogo","kinokong","kinotac","zona")
    /** Сайты с типом «фильм/сериал», угаданным по вёрстке: для них тип берётся из TMDB, а не сравнивается. */
    val guessedTypeProviders=setOf("kinogo","kinokong")
    // Хвосты и приписки, которые сайты добавляют к названию, но которых нет в TMDB.
    private val noise=listOf(
        "(?:смотреть\\s+)?онлайн(?:\\s+бесплатно)?", "бесплатно", "в\\s+хорошем\\s+качестве", "hd(?:rip)?", "full\\s*hd", "4k",
        "все\\s+(?:серии|сезоны)", "(?:[0-9]+\\s*[-–]\\s*)?[0-9]+\\s*сезон(?:а|ов)?", "сезон\\s*[0-9]+(?:\\s*[-–]\\s*[0-9]+)?",
        "(?:[0-9]+\\s*[-–]\\s*)?[0-9]+\\s*серия", "сериал", "фильм", "мультфильм", "мультсериал", "аниме", "дорама", "тв\\s*шоу")
        .joinToString("|").let { Regex("(?iu)[\\s,.:;!\\-–—]*[(\\[]?\\s*(?:$it)\\s*[)\\]]?[\\s,.:;!\\-–—]*$") }
    private val trailingYear=Regex("(?iu)\\s*[(\\[]?\\s*(?:19|20)[0-9]{2}(?:\\s*[-–]\\s*(?:(?:19|20)[0-9]{2})?)?\\s*[)\\]]?\\s*$")
    internal fun normalized(value:String):String {
        var text=Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('ё','е').trim()
        // Приписки могут идти цепочкой: «Название (2022) 1-2 сезон смотреть онлайн».
        // Название целиком из приписки или года («2012», «1917», «Сериал») не обрезается.
        repeat(4) {
            for(pattern in listOf(noise,trailingYear)) text.replace(pattern,"").trim().takeIf { it.any(Char::isLetterOrDigit) }?.let { text=it }
        }
        return text.replace("&"," и ").replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    }
    /** «Дюна: Часть вторая / Dune: Part Two» — две отдельные версии названия. */
    private fun variants(value:String)=value.split(Regex("\\s+[/|]\\s+")).map(::normalized).filter { it.isNotBlank() }
    private fun names(item:CenterTitle)=listOf(item.title,item.original).flatMap(::variants).toSet()
    fun sameName(item:CenterTitle,other:CenterTitle)=names(item).intersect(names(other)).isNotEmpty()
    /** Запросы к сайту: русское и оригинальное название, плюс русское без подзаголовка после двоеточия. */
    fun queries(item:CenterTitle):List<String> {
        val base=listOf(item.title,item.original).map { it.trim() }.filter { it.isNotBlank() }
        val short=item.title.substringBefore(':').trim().takeIf { it.length>=3 && it!=item.title.trim() }
        return (base+listOfNotNull(short)).distinctBy(::normalized).take(3)
    }
    fun valid(item:CenterTitle,source:CenterTitle):Boolean = item.provider==null && item.id>0 && item.type in listOf("movie","tv") &&
        source.provider in providers&&(source.type==item.type||source.provider in guessedTypeProviders)&&source.id>0&&runCatching { CenterOnline.source(source) }.isSuccess
    fun exact(item:CenterTitle,source:CenterTitle):Boolean = valid(item,source)&&sameName(item,source)&&
        Regex("(?:19|20)[0-9]{2}").matches(item.year)&&item.year==source.year
    fun candidates(item:CenterTitle,results:List<CenterTitle>)=results.filter { valid(item,it) }.distinctBy { it.key }
        .sortedByDescending { exact(item,it) }.take(40)
    fun automatic(item:CenterTitle,results:List<CenterTitle>):CenterTitle? = candidates(item,results).filter { exact(item,it) }.singleOrNull()
        ?.let { if(it.type!=item.type&&it.provider in guessedTypeProviders) it.copy(type=item.type) else it }
    fun fromPage(item:CenterTitle):CenterTitle? {
        if(item.provider!=null)return null
        val url=item.sourceUrl?.toHttpUrlOrNull() ?: return null
        val provider=providers.firstOrNull { p ->
            val host=CenterCatalog.defaultHost(p).toHttpUrlOrNull()!!.host
            url.host==host||url.host.endsWith("."+host)||(p=="kinogo"&&(url.host=="kinogo.moi"||url.host.endsWith(".kinogo.moi")))
        } ?: "kinovod".takeIf { Regex("^/(film|serial)/[0-9]+-[^/]+$").matches(url.encodedPath) } ?: return null
        val match=when(provider) {
            "kinovod" -> Regex("^/(film|serial)/([0-9]+)-[^/]+$").find(url.encodedPath)
            "zona" -> Regex("^/(film|series)/[^/]+-([0-9]+)/?$").find(url.encodedPath)
            else -> Regex("^/([0-9]+)-[^/]+\\.html$").find(url.encodedPath)
        } ?: return null
        val id=match.groupValues[if(provider in listOf("kinovod","zona"))2 else 1].toIntOrNull() ?: return null
        val type=if(provider in listOf("kinovod","zona"))if(match.groupValues[1]=="film")"movie" else "tv" else item.type
        return item.copy(id=id,type=type,provider=provider).takeIf { valid(item,it) }
    }
}
