package com.example.associations.data

import com.example.associations.model.Card
import com.example.associations.model.GameState
import com.example.associations.model.Group
import com.example.associations.model.Pile
import com.example.associations.model.PileType
import org.json.JSONArray
import org.json.JSONObject

/** Ручная сериализация [GameState] ↔ JSON через встроенный org.json. */
object GameSerializer {

    fun toJson(state: GameState): String {
        val root = JSONObject()
        root.put("level", state.level)
        root.put("moves", state.moves)
        root.put("elapsedSec", state.elapsedSec)
        root.put("isWon", state.isWon)
        root.put("groups", JSONArray().apply { state.groups.forEach { put(it.name) } })
        root.put("collected", JSONArray().apply { state.collected.forEach { put(it.name) } })

        val pilesArr = JSONArray()
        for (pile in state.piles) {
            val pileObj = JSONObject()
            pileObj.put("type", pile.type.name)
            pileObj.put("group", pile.group?.name ?: JSONObject.NULL)
            val cardsArr = JSONArray()
            for (card in pile.cards) {
                cardsArr.put(JSONObject().apply {
                    put("id", card.id)
                    put("group", card.group.name)
                    put("isBase", card.isBase)
                    put("title", card.title)
                    put("icon", card.icon)
                    put("faceUp", card.faceUp)
                })
            }
            pileObj.put("cards", cardsArr)
            pilesArr.put(pileObj)
        }
        root.put("piles", pilesArr)
        return root.toString()
    }

    fun fromJson(json: String): GameState? = try {
        val root = JSONObject(json)
        val groups = root.getJSONArray("groups").let { arr ->
            (0 until arr.length()).map { Group.valueOf(arr.getString(it)) }
        }
        val collected = root.optJSONArray("collected").let { arr ->
            if (arr == null) emptySet()
            else (0 until arr.length()).map { Group.valueOf(arr.getString(it)) }.toSet()
        }

        val pilesArr = root.getJSONArray("piles")
        val piles = ArrayList<Pile>(pilesArr.length())
        for (i in 0 until pilesArr.length()) {
            val pileObj = pilesArr.getJSONObject(i)
            val type = PileType.valueOf(pileObj.getString("type"))
            val groupRaw = pileObj.opt("group")
            val group = if (groupRaw is String) Group.valueOf(groupRaw) else null
            val cardsArr = pileObj.getJSONArray("cards")
            val cards = ArrayList<Card>(cardsArr.length())
            for (j in 0 until cardsArr.length()) {
                val c = cardsArr.getJSONObject(j)
                cards += Card(
                    id = c.getInt("id"),
                    group = Group.valueOf(c.getString("group")),
                    isBase = c.getBoolean("isBase"),
                    title = c.getString("title"),
                    icon = c.getString("icon"),
                    faceUp = c.getBoolean("faceUp")
                )
            }
            piles += Pile(type = type, group = group, cards = cards)
        }

        GameState(
            piles = piles,
            groups = groups,
            level = root.optInt("level", 1),
            collected = collected,
            moves = root.optInt("moves", 0),
            elapsedSec = root.optInt("elapsedSec", 0),
            isWon = root.optBoolean("isWon", false)
        )
    } catch (e: Exception) {
        null
    }
}
