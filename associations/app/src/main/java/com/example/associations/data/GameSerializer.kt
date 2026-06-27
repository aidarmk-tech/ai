package com.example.associations.data

import com.example.associations.model.Card
import com.example.associations.model.Category
import com.example.associations.model.GameState
import com.example.associations.model.Pile
import com.example.associations.model.PileType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ручная (без рефлексии) сериализация [GameState] в JSON и обратно через
 * встроенный org.json — чтобы не тянуть плагины сериализации.
 */
object GameSerializer {

    fun toJson(state: GameState): String {
        val root = JSONObject()
        root.put("level", state.level)
        root.put("rankLength", state.rankLength)
        root.put("moves", state.moves)
        root.put("elapsedSec", state.elapsedSec)
        root.put("isWon", state.isWon)

        root.put("categories", JSONArray().apply {
            state.categories.forEach { put(it.name) }
        })
        root.put("collected", JSONArray().apply {
            state.collected.forEach { put(it.name) }
        })

        val pilesArr = JSONArray()
        for (pile in state.piles) {
            val pileObj = JSONObject()
            pileObj.put("type", pile.type.name)
            pileObj.put("category", pile.category?.name ?: JSONObject.NULL)
            val cardsArr = JSONArray()
            for (card in pile.cards) {
                cardsArr.put(JSONObject().apply {
                    put("id", card.id)
                    put("category", card.category.name)
                    put("rank", card.rank)
                    put("title", card.title)
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

        val categories = root.getJSONArray("categories").let { arr ->
            (0 until arr.length()).map { Category.valueOf(arr.getString(it)) }
        }
        val collected = root.optJSONArray("collected").let { arr ->
            if (arr == null) emptySet()
            else (0 until arr.length()).map { Category.valueOf(arr.getString(it)) }.toSet()
        }

        val pilesArr = root.getJSONArray("piles")
        val piles = ArrayList<Pile>(pilesArr.length())
        for (i in 0 until pilesArr.length()) {
            val pileObj = pilesArr.getJSONObject(i)
            val type = PileType.valueOf(pileObj.getString("type"))
            val categoryRaw = pileObj.opt("category")
            val category = if (categoryRaw is String) Category.valueOf(categoryRaw) else null
            val cardsArr = pileObj.getJSONArray("cards")
            val cards = ArrayList<Card>(cardsArr.length())
            for (j in 0 until cardsArr.length()) {
                val c = cardsArr.getJSONObject(j)
                cards += Card(
                    id = c.getInt("id"),
                    category = Category.valueOf(c.getString("category")),
                    rank = c.getInt("rank"),
                    title = c.getString("title"),
                    faceUp = c.getBoolean("faceUp")
                )
            }
            piles += Pile(type = type, category = category, cards = cards)
        }

        GameState(
            piles = piles,
            categories = categories,
            rankLength = root.optInt("rankLength", 6),
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
