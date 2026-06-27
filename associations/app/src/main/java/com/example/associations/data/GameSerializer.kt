package com.example.associations.data

import com.example.associations.model.Card
import com.example.associations.model.Category
import com.example.associations.model.GameState
import com.example.associations.model.Pile
import com.example.associations.model.PileType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ручная (без рефлексии) сериализация [GameState] в JSON и обратно.
 * Используем встроенный в Android org.json, чтобы не тянуть плагины.
 */
object GameSerializer {

    fun toJson(state: GameState): String {
        val root = JSONObject()
        root.put("moves", state.moves)
        root.put("elapsedSec", state.elapsedSec)
        root.put("isWon", state.isWon)

        val pilesArr = JSONArray()
        for (pile in state.piles) {
            val pileObj = JSONObject()
            pileObj.put("type", pile.type.name)
            pileObj.put("category", pile.category?.name ?: JSONObject.NULL)
            val cardsArr = JSONArray()
            for (card in pile.cards) {
                val c = JSONObject()
                c.put("id", card.id)
                c.put("category", card.category.name)
                c.put("rank", card.rank)
                c.put("title", card.title)
                c.put("faceUp", card.faceUp)
                cardsArr.put(c)
            }
            pileObj.put("cards", cardsArr)
            pilesArr.put(pileObj)
        }
        root.put("piles", pilesArr)
        return root.toString()
    }

    fun fromJson(json: String): GameState? = try {
        val root = JSONObject(json)
        val pilesArr = root.getJSONArray("piles")
        val piles = ArrayList<Pile>(pilesArr.length())
        for (i in 0 until pilesArr.length()) {
            val pileObj = pilesArr.getJSONObject(i)
            val type = PileType.valueOf(pileObj.getString("type"))
            val category = pileObj.opt("category")?.let {
                if (it == JSONObject.NULL || it !is String) null else Category.valueOf(it)
            }
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
            moves = root.optInt("moves", 0),
            elapsedSec = root.optInt("elapsedSec", 0),
            isWon = root.optBoolean("isWon", false)
        )
    } catch (e: Exception) {
        null
    }
}
