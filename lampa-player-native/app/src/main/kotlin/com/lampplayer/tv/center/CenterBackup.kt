package com.lampplayer.tv.center

import android.content.Context
import com.google.gson.*
import com.lampplayer.tv.data.datastore.PositionDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A portable library snapshot. Indexer credentials, connection addresses and cookies are excluded. */
class CenterBackup(private val context:Context) {
    companion object { const val LIMIT=16*1024*1024 }
    private val gson=Gson()
    private fun strings(obj:JsonElement?):Map<String,String> {
        require(obj?.isJsonObject==true){"В копии отсутствуют данные"}
        return obj!!.asJsonObject.entrySet().associate { (key,value) ->
            require(value.isJsonPrimitive&&value.asJsonPrimitive.isString){"Неверное значение в резервной копии"}
            key to value.asString
        }
    }
    suspend fun export():String = withContext(Dispatchers.IO) {
        val positions=PositionDataStore(context).backupEntries()
        val root=JsonObject().apply {
            addProperty("format","LampMedia");addProperty("version",1);addProperty("createdAt",System.currentTimeMillis())
            add("library",gson.toJsonTree(CenterStore(context).backupEntries()));add("positions",gson.toJsonTree(positions))
        }
        gson.toJson(root).also { require(it.toByteArray(Charsets.UTF_8).size<=LIMIT){"Резервная копия превышает 16 МБ"} }
    }
    suspend fun restore(json:String):String = withContext(Dispatchers.IO) {
        require(json.toByteArray(Charsets.UTF_8).size<=LIMIT){"Файл превышает 16 МБ"}
        val root=JsonParser.parseString(json).asJsonObject
        require(root.get("format")?.asString=="LampMedia"&&root.get("version")?.asInt==1){"Это не поддерживаемая резервная копия LampMedia"}
        val library=strings(root.get("library"));val positions=strings(root.get("positions"))
        val store=CenterStore(context);val progress=PositionDataStore(context)
        // Validate both sections completely before changing either store.
        store.validateBackup(library);progress.validateBackup(positions)
        val updated=progress.mergeBackup(positions);val added=store.mergeBackup(library)
        "Восстановление завершено: добавлено записей библиотеки $added, записей прогресса $updated. Более свежие данные сохранены."
    }
}
