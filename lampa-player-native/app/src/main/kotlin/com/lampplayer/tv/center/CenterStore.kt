package com.lampplayer.tv.center

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lampplayer.tv.domain.model.CardMeta
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CenterHistory(val url: String, val card: CardMeta, val at: Long)
class CenterStore(context: Context) {
    private val prefs = context.getSharedPreferences("media_center", Context.MODE_PRIVATE)
    private val gson = Gson()
    var autoUpdates: Boolean
        get() = prefs.getBoolean("auto_updates", true)
        set(value) { prefs.edit().putBoolean("auto_updates", value).apply() }
    var indexer: String
        get() = prefs.getString("indexer", "").orEmpty()
        set(value) { prefs.edit().putString("indexer", value.trim()).apply() }
    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(value) { prefs.edit().putString("api_key", value.trim()).apply() }
    var indexerType: String
        get() = prefs.getString("indexer_type", "jacred").orEmpty()
        set(value) { prefs.edit().putString("indexer_type", value).apply() }
    var catalog: String
        get() = prefs.getString("catalog", "tmdb").orEmpty().takeIf { it in CenterCatalog.providers } ?: "tmdb"
        set(value) { prefs.edit().putString("catalog", value).apply() }
    var searchCatalog:String
        get() = prefs.getString("search_catalog",null)?.takeIf { it in CenterCatalog.searchProviders } ?: catalog.takeIf { it in CenterCatalog.searchProviders } ?: "tmdb"
        set(value) { require(value in CenterCatalog.searchProviders);prefs.edit().putString("search_catalog",value).apply() }
    /** «Поиск везде»: искать сразу во всех каталогах вместо [searchCatalog]. */
    var searchEverywhere: Boolean
        get() = prefs.getBoolean("search_everywhere", false)
        set(value) { prefs.edit().putBoolean("search_everywhere", value).apply() }
    /** Browsing preferences belong to each TMDB media type; search text is never persisted here. */
    fun libraryBrowse(type: String): CenterBrowse {
        require(type in listOf("movie","tv"))
        val key="browse_$type"
        return CenterBrowse(type=type,
            mode=prefs.getString("${key}_mode","popular").takeIf { it=="new" } ?: "popular",
            country=prefs.getString("${key}_country","").orEmpty().takeIf { it in listOf("","RU","KZ","US") }.orEmpty(),
            year=prefs.getInt("${key}_year",0).takeIf { it in 1888..2200 },
            genre=prefs.getInt("${key}_genre",0).takeIf { it>0 },
            genreName=prefs.getString("${key}_genre_name","").orEmpty().take(100),
            translatedOnly=prefs.getBoolean("${key}_translated_only",true))
    }
    fun rememberLibraryBrowse(value: CenterBrowse) {
        require(value.type in listOf("movie","tv") && value.query.isBlank())
        require(value.mode in listOf("popular","new") && value.country in listOf("","RU","KZ","US"))
        require(value.year==null||value.year in 1888..2200)
        require(value.genre==null||value.genre>0)
        val key="browse_${value.type}"
        prefs.edit().putString("${key}_mode",value.mode).putString("${key}_country",value.country)
            .putInt("${key}_year",value.year ?: 0).putInt("${key}_genre",value.genre ?: 0)
            .putString("${key}_genre_name",value.genreName.take(100))
            .putBoolean("${key}_translated_only",value.translatedOnly).apply()
    }
    fun catalogHost(provider: String) = prefs.getString("host_$provider", CenterCatalog.defaultHost(provider)).orEmpty()
    fun setCatalogHost(provider: String, host: String) { prefs.edit().putString("host_$provider", host.trim().trimEnd('/')).apply() }
    fun preferredQuality(provider: String?): Int? =
        prefs.getInt("quality_${provider.orEmpty()}", -1).takeIf { it >= 0 }
    fun setPreferredQuality(provider: String?, quality: Int?) {
        val key = "quality_${provider.orEmpty()}"
        val editor = prefs.edit()
        if (quality == null) editor.remove(key) else {
            require(quality >= 0)
            editor.putInt(key, quality)
        }
        editor.apply()
    }
    var exactReleases: Boolean
        get() = prefs.getBoolean("exact_releases", false)
        set(value) { prefs.edit().putBoolean("exact_releases", value).apply() }
    var releaseSort: String
        get() = prefs.getString("release_sort", "seeds").orEmpty().takeIf { it in listOf("seeds", "small", "large") } ?: "seeds"
        set(value) { require(value in listOf("seeds", "small", "large")); prefs.edit().putString("release_sort", value).apply() }
    var seededOnly: Boolean
        get() = prefs.getBoolean("seeded_only", false)
        set(value) { prefs.edit().putBoolean("seeded_only", value).apply() }
    var cardSize: String
        get() = prefs.getString("card_size", "normal").orEmpty().takeIf { it in listOf("compact", "normal", "large") } ?: "normal"
        set(value) { require(value in listOf("compact", "normal", "large")); prefs.edit().putString("card_size", value).apply() }
    var textScale: String
        get() = prefs.getString("text_scale", "normal").orEmpty().takeIf { it in CenterTheme.scales } ?: "normal"
        set(value) { require(value in CenterTheme.scales); prefs.edit().putString("text_scale", value).apply() }
    var palette: String
        get() = prefs.getString("palette", "default").orEmpty().takeIf { it in CenterTheme.paletteLabels } ?: "default"
        set(value) { require(value in CenterTheme.paletteLabels); prefs.edit().putString("palette", value).apply() }
    fun voice(key: String): String? = prefs.getString("voice_$key", null)
    fun setVoice(key: String, voice: String?) {
        prefs.edit().apply { if (voice == null) remove("voice_$key") else putString("voice_$key", voice) }.apply()
    }
    fun onlineSource(item:CenterTitle,provider:String?=null):CenterTitle? {
        fun saved(p:String)=runCatching { gson.fromJson(prefs.getString("online_"+p+"_"+item.key,null),CenterTitle::class.java) }
            .getOrNull()?.takeIf { it.provider==p&&CenterOnlineMatch.valid(item,it) }
        if(provider!=null)return provider.takeIf { it in CenterOnlineMatch.providers }?.let(::saved)
        val bindings=CenterOnlineMatch.providers.mapNotNull(::saved)
        item.sourceUrl?.let { page -> bindings.firstOrNull { CenterOnline.sameTitleNavigation(it,page) }?.let { return it.copy(sourceUrl=page) } }
        val last=prefs.getString("online_last_"+item.key,null)
        return bindings.firstOrNull { it.provider==last } ?: bindings.firstOrNull { it.provider=="kinovod" }
    }
    fun setOnlineSource(item:CenterTitle,source:CenterTitle) {
        require(CenterOnlineMatch.valid(item,source))
        prefs.edit().putString("online_"+source.provider+"_"+item.key,gson.toJson(source))
            .putString("online_last_"+item.key,source.provider).apply()
    }
    /**
     * Привязка, найденная автоматически (поиск везде, предзагрузка): записывается, только если для этого сайта
     * ещё нет выбора, и не меняет сайт, который пользователь выбрал последним.
     */
    fun suggestOnlineSource(item:CenterTitle,source:CenterTitle):Boolean {
        if(!CenterOnlineMatch.valid(item,source)||source.type!=item.type||onlineSource(item,source.provider)!=null)return false
        val edit=prefs.edit().putString("online_"+source.provider+"_"+item.key,gson.toJson(source))
        if(!prefs.contains("online_last_"+item.key))edit.putString("online_last_"+item.key,source.provider)
        edit.apply();return true
    }
    fun searches(): List<String> = runCatching {
        gson.fromJson<List<String>>(prefs.getString("searches", "[]"), object : TypeToken<List<String>>() {}.type)
    }.getOrNull().orEmpty().filter { it.length in 2..120 }.take(20)
    fun rememberSearch(value: String) {
        val term=value.trim().replace(Regex("\\s+"), " ")
        if(term.length !in 2..120) return
        prefs.edit().putString("searches",gson.toJson((listOf(term)+searches().filterNot { it.equals(term,true) }).take(20))).apply()
    }
    fun removeSearch(value: String) { prefs.edit().putString("searches",gson.toJson(searches().filterNot { it.equals(value,true) })).apply() }
    fun clearSearches() { prefs.edit().remove("searches").apply() }
    fun followed(): List<CenterTitle> = runCatching {
        gson.fromJson<List<CenterTitle>>(prefs.getString("followed", "[]"), object : TypeToken<List<CenterTitle>>() {}.type)
    }.getOrNull().orEmpty()
    fun isFollowed(item: CenterTitle) = followed().any { it.key==item.key }
    fun toggleFollow(item: CenterTitle) {
        require(item.type=="tv")
        val next=followed().filterNot { it.key==item.key }.toMutableList()
        if(!isFollowed(item)) next.add(0,item)
        prefs.edit().putString("followed",gson.toJson(next.take(200))).apply()
    }
    fun mySeries(): List<CenterTitle> {
        val recent=history().filter { it.card.contentKey?.contains(":tv:")==true || it.card.seasonNumber!=null || it.card.episodes.isNotEmpty() }.mapNotNull {
            val card=it.card;val parts=card.contentKey?.split(':').orEmpty()
            val provider=parts.firstOrNull()?.takeIf { p -> CenterCatalog.isSiteIdentity(p) }
            val id=if(provider!=null) parts.getOrNull(2)?.toIntOrNull() else card.tmdbId
            id?.let { value -> CenterTitle(value,"tv",card.title,poster=card.posterUrl,provider=provider,sourceUrl=card.sourcePage) }
        }
        return (followed()+recent).distinctBy { it.key }.map { it.copy(updateLabel=seriesStatuses[seriesStatus(it.key)]) }
    }
    fun pinned(): List<CenterTitle> = runCatching {
        gson.fromJson<List<CenterTitle>>(prefs.getString("pinned", "[]"), object : TypeToken<List<CenterTitle>>() {}.type)
    }.getOrNull().orEmpty()
    fun isPinned(item: CenterTitle) = pinned().any { it.key == item.key }
    fun pin(item: CenterTitle) {
        val next = pinned().filterNot { it.key == item.key }.toMutableList()
        if (!isPinned(item)) next.add(0, item)
        prefs.edit().putString("pinned", gson.toJson(next.take(30))).apply()
    }
    fun favorites(): List<CenterTitle> = runCatching {
        gson.fromJson<List<CenterTitle>>(prefs.getString("favorites", "[]"), object : TypeToken<List<CenterTitle>>() {}.type)
    }.getOrNull().orEmpty()
    fun isFavorite(item: CenterTitle) = favorites().any { it.key == item.key }
    fun toggle(item: CenterTitle) {
        val items = favorites().filterNot { it.key == item.key }.toMutableList()
        if (!isFavorite(item)) items.add(0, item)
        prefs.edit().putString("favorites", gson.toJson(items.take(200))).apply()
    }
    fun history(): List<CenterHistory> = runCatching {
        gson.fromJson<List<CenterHistory>>(prefs.getString("history", "[]"), object : TypeToken<List<CenterHistory>>() {}.type)
    }.getOrNull().orEmpty()
    private fun historyKey(card: CardMeta) = card.contentKey?.removePrefix("tmdb:") ?: "${if (card.seasonNumber != null || card.episodes.isNotEmpty()) "tv" else "movie"}:${card.tmdbId ?: card.title}"
    fun removeHistory(card: CardMeta) {
        prefs.edit().putString("history", gson.toJson(history().filterNot { historyKey(it.card) == historyKey(card) })).apply()
    }
    /** Возвращает запись, убранную из истории, на прежнее место по времени просмотра. */
    fun restoreHistory(entry: CenterHistory) {
        val list = (history().filterNot { historyKey(it.card) == historyKey(entry.card) } + entry).sortedByDescending { it.at }
        prefs.edit().putString("history", gson.toJson(list.take(HISTORY_LIMIT))).apply()
    }
    fun clearHistory() { prefs.edit().remove("history").apply() }
    fun remember(url: String, card: CardMeta) {
        val list = history().filterNot { historyKey(it.card) == historyKey(card) }.toMutableList()
        list.add(0, CenterHistory(url, card, System.currentTimeMillis()))
        prefs.edit().putString("history", gson.toJson(list.take(HISTORY_LIMIT))).apply()
    }
    fun seriesStatus(key:String):String = prefs.getString("series_status_$key","watching").orEmpty()
        .takeIf { it in seriesStatuses } ?: "watching"
    fun setSeriesStatus(key:String,status:String){require(status in seriesStatuses);prefs.edit().putString("series_status_$key",status).apply()}
    fun rememberEpisodes(item:CenterTitle,episodes:List<OnlineEpisode>){
        if(item.type!="tv")return
        val known=episodes.filter { it.season>0&&it.number>0&&it.sources.isNotEmpty() }.map { it.season to it.number }.distinct()
        prefs.edit().putString("known_episodes_${item.key}",gson.toJson(known.take(1000).map { "${it.first}:${it.second}" })).apply()
    }
    fun nextKnownEpisode(item:CenterTitle):Pair<Int,Int>? {
        val last=history().firstOrNull { it.card.contentKey==item.key || (item.provider==null&&it.card.tmdbId==item.id&&it.card.seasonNumber!=null) }?.card ?: return null
        val season=last.seasonNumber ?: return null;val number=last.episodeNumber ?: return null
        val known=stringList("known_episodes_${item.key}")
        return (season to number+1).takeIf { "${it.first}:${it.second}" in known }
    }
    private fun stringList(key:String):List<String> = runCatching {
        gson.fromJson<List<String>>(prefs.getString(key,"[]"),object:TypeToken<List<String>>(){}.type)
    }.getOrNull().orEmpty()
    fun homeOrder(provider:String):List<String> {
        val defaults=listOf("continue","pinned_movies","pinned_series")+CenterCatalog.homeSections(provider).map { it.id }
        return (stringList("home_order_$provider").filter { it in defaults }+defaults).distinct()
    }
    fun homeHidden(provider:String)=stringList("home_hidden_$provider").toSet()
    fun setHomeOrder(provider:String,order:List<String>){
        require(order.toSet()==homeOrder(provider).toSet()&&order.size==order.distinct().size)
        prefs.edit().putString("home_order_$provider",gson.toJson(order)).apply()
    }
    fun toggleHomeShelf(provider:String,id:String){
        require(id in homeOrder(provider));val hidden=homeHidden(provider).toMutableSet()
        if(!hidden.add(id))hidden.remove(id)
        prefs.edit().putString("home_hidden_$provider",gson.toJson(hidden)).apply()
    }
    fun resetHome(provider:String){prefs.edit().remove("home_order_$provider").remove("home_hidden_$provider").apply()}

    private fun backupKey(key:String)=key in setOf("favorites","pinned","followed","history","searches","card_size","search_catalog","catalog") ||
        listOf("voice_","quality_","online_","series_status_","known_episodes_","home_order_","home_hidden_","browse_").any { key.startsWith(it) }
    fun backupEntries():Map<String,String> = prefs.all.filterKeys(::backupKey).mapValues { (key,value) ->
        if(key=="history")gson.toJson(history().map { it.copy(card=it.card.copy(headers=emptyMap())) }) else value.toString()
    }
    fun validateBackup(entries:Map<String,String>) {
        require(entries.size<=10000){"Слишком много записей библиотеки"}
        entries.forEach { (key,value) ->
            require(backupKey(key)&&key.length<=600&&value.length<=4*1024*1024){"Неверное поле резервной копии"}
            when {
                key in listOf("favorites","pinned","followed") -> {
                    val items=gson.fromJson<List<CenterTitle>>(value,object:TypeToken<List<CenterTitle>>(){}.type)
                    require(items.size<=1000&&items.all { it.id>0&&it.type in listOf("movie","tv")&&it.title.isNotBlank() }){"Неверные карточки библиотеки"}
                }
                key=="history" -> {
                    val items=gson.fromJson<List<CenterHistory>>(value,object:TypeToken<List<CenterHistory>>(){}.type)
                    require(items.size<=1000&&items.all { it.at>=0&&it.card.title.isNotBlank()&&it.url.toHttpUrlOrNull()!=null }){"Неверная история просмотра"}
                }
                key=="searches"||key.startsWith("home_")||key.startsWith("known_episodes_") -> {
                    val items=gson.fromJson<List<String>>(value,object:TypeToken<List<String>>(){}.type)
                    require(items.size<=1000&&items.all { it.length<=600 }){"Неверный список в резервной копии"}
                }
                key.startsWith("quality_") -> require(value.toIntOrNull() in 0..10000){"Неверное качество"}
                key.startsWith("series_status_") -> require(value in seriesStatuses){"Неверный статус сериала"}
                key.startsWith("online_")&&!key.startsWith("online_last_") -> {
                    val item=gson.fromJson(value,CenterTitle::class.java)
                    require(item.id>0&&item.provider in CenterOnlineMatch.providers&&item.title.isNotBlank()&&runCatching { CenterOnline.source(item) }.isSuccess){"Неверная привязка источника"}
                }
                key.startsWith("browse_")&&(key.endsWith("_year")||key.endsWith("_genre")) -> require(value.toIntOrNull()!=null)
                key.startsWith("browse_")&&key.endsWith("_translated_only") -> require(value in listOf("true","false"))
            }
        }
    }
    fun mergeBackup(entries:Map<String,String>):Int {
        validateBackup(entries)
        val edit=prefs.edit();var imported=0
        entries.forEach { (key,value) ->
            when {
                key in listOf("favorites","pinned","followed") -> {
                    val incoming=gson.fromJson<List<CenterTitle>>(value,object:TypeToken<List<CenterTitle>>(){}.type)
                    val existing=when(key){"favorites"->favorites();"pinned"->pinned();else->followed()}
                    val merged=(existing+incoming).distinctBy { it.key }.take(if(key=="pinned")30 else 200)
                    edit.putString(key,gson.toJson(merged));imported+=(merged.size-existing.size).coerceAtLeast(0)
                }
                key=="history" -> {
                    val incoming=gson.fromJson<List<CenterHistory>>(value,object:TypeToken<List<CenterHistory>>(){}.type).map { it.copy(card=it.card.copy(headers=emptyMap())) }
                    val existing=history();val merged=(existing+incoming).sortedByDescending { it.at }.distinctBy { historyKey(it.card) }.take(HISTORY_LIMIT)
                    edit.putString(key,gson.toJson(merged));imported+=(merged.size-existing.size).coerceAtLeast(0)
                }
                !prefs.contains(key) -> {
                    if(key.startsWith("quality_")||key.startsWith("browse_")&&(key.endsWith("_year")||key.endsWith("_genre")))edit.putInt(key,value.toInt())
                    else if(key.startsWith("browse_")&&key.endsWith("_translated_only"))edit.putBoolean(key,value.toBoolean())
                    else edit.putString(key,value)
                    imported++
                }
            }
        }
        check(edit.commit()){ "Не удалось сохранить библиотеку" };return imported
    }

    companion object {
        /** Сколько произведений хранит «Продолжить просмотр»; таймкоды хранятся отдельно и не ограничены этой лентой. */
        const val HISTORY_LIMIT = 200
        val seriesStatuses=linkedMapOf("watching" to "Смотрю","unwatched" to "Есть непросмотренные","waiting" to "Жду продолжения","done" to "Досмотрено")
        fun rebase(url: String, base: String): String {
            val original = url.toHttpUrlOrNull() ?: return url
            val target = base.toHttpUrlOrNull() ?: return url
            return original.newBuilder().scheme(target.scheme).host(target.host).port(target.port).build().toString()
        }
    }
}
