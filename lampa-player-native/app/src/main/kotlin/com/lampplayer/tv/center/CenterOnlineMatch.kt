package com.lampplayer.tv.center

import java.text.Normalizer
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Matching never treats a provider's native ID as a TMDB ID. */
object CenterOnlineMatch {
    val providers=listOf("kinovod","kinogo","kinokong","kinotac","zona")
    private fun normalized(value:String):String = Normalizer.normalize(value,Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё','е')
        .replace(Regex("(?iu)\\s*(?:\\(|\\[)\\s*(?:19|20)[0-9]{2}(?:\\s*[-–]\\s*(?:19|20)[0-9]{2})?\\s*(?:\\)|\\])\\s*$"),"")
        .replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    private fun names(item:CenterTitle)=listOf(item.title,item.original).map(::normalized).filter { it.isNotBlank() }.toSet()
    fun queries(item:CenterTitle)=listOf(item.title,item.original).map { it.trim() }.filter { it.isNotBlank() }.distinctBy(::normalized).take(2)
    fun valid(item:CenterTitle,source:CenterTitle):Boolean = item.provider==null && item.id>0 && item.type in listOf("movie","tv") &&
        source.provider in providers&&source.type==item.type&&source.id>0&&runCatching { CenterOnline.source(source) }.isSuccess
    fun exact(item:CenterTitle,source:CenterTitle):Boolean = valid(item,source)&&names(item).intersect(names(source)).isNotEmpty()&&
        Regex("(?:19|20)[0-9]{2}").matches(item.year)&&item.year==source.year
    fun candidates(item:CenterTitle,results:List<CenterTitle>)=results.filter { valid(item,it) }.distinctBy { it.key }
        .sortedByDescending { exact(item,it) }.take(40)
    fun automatic(item:CenterTitle,results:List<CenterTitle>):CenterTitle? = candidates(item,results).filter { exact(item,it) }.singleOrNull()
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
