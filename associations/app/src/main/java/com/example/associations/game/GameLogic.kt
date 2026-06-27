package com.example.associations.game

import com.example.associations.model.Card
import com.example.associations.model.CardContent
import com.example.associations.model.Category
import com.example.associations.model.GameState
import com.example.associations.model.MAX_RANK
import com.example.associations.model.Pile
import com.example.associations.model.PileType
import kotlin.random.Random

/**
 * Чистая игровая логика. Все функции не имеют побочных эффектов: принимают
 * [GameState] и возвращают новый [GameState] (или null, если ход нелегален).
 *
 * Колода — 24 карты (4 категории × 6 рангов), поэтому классическая «косынка»
 * на 28 карт не помещается. Раздаём симметричной «лесенкой» из 16 карт по
 * 7 колонкам, остаток (8 карт) уходит в Stock — это гарантирует играбельный
 * добор и переворот колоды.
 */
object GameLogic {

    /** Размеры колонок tableau при раздаче (сумма = 16, в Stock остаётся 8). */
    private val TABLEAU_DEAL = intArrayOf(1, 2, 3, 4, 3, 2, 1)

    fun newGame(seed: Long = System.currentTimeMillis()): GameState {
        val deck = CardContent.buildDeck().toMutableList()
        deck.shuffle(Random(seed))

        val tableau = MutableList(TABLEAU_DEAL.size) { mutableListOf<Card>() }
        var index = 0
        for (col in TABLEAU_DEAL.indices) {
            val size = TABLEAU_DEAL[col]
            for (row in 0 until size) {
                val faceUp = row == size - 1 // открыта только верхняя карта колонки
                tableau[col].add(deck[index].copy(faceUp = faceUp))
                index++
            }
        }

        val stockCards = deck.subList(index, deck.size).map { it.copy(faceUp = false) }

        val piles = buildList {
            add(Pile(PileType.STOCK, cards = stockCards))
            add(Pile(PileType.WASTE, cards = emptyList()))
            for (category in Category.values()) {
                add(Pile(PileType.FOUNDATION, category = category, cards = emptyList()))
            }
            for (col in tableau) {
                add(Pile(PileType.TABLEAU, cards = col.toList()))
            }
        }

        return GameState(piles = piles, moves = 0, elapsedSec = 0, isWon = false)
    }

    // ----- Проверки легальности -------------------------------------------

    /** Можно ли положить [card] на основание [foundation]. */
    fun canPlaceOnFoundation(card: Card, foundation: Pile): Boolean {
        if (foundation.type != PileType.FOUNDATION) return false
        if (foundation.category != card.category) return false
        val topRank = foundation.cards.lastOrNull()?.rank ?: 0
        return card.rank == topRank + 1
    }

    /** Можно ли положить [card] на колонку tableau [column]. */
    fun canPlaceOnTableau(card: Card, column: Pile): Boolean {
        if (column.type != PileType.TABLEAU) return false
        val top = column.cards.lastOrNull() ?: return true // на пустую — любую
        return card.rank == top.rank - 1 && card.category != top.category
    }

    /**
     * Является ли «хвост» колонки начиная с [startIndex] корректной серией,
     * которую можно перемещать целиком: все карты открыты и идут по убыванию
     * ранга с чередованием категорий.
     */
    fun isMovableRun(column: Pile, startIndex: Int): Boolean {
        val cards = column.cards
        if (startIndex < 0 || startIndex >= cards.size) return false
        for (i in startIndex until cards.size) {
            if (!cards[i].faceUp) return false
        }
        for (i in startIndex until cards.size - 1) {
            val upper = cards[i]
            val lower = cards[i + 1]
            if (lower.rank != upper.rank - 1 || lower.category == upper.category) return false
        }
        return true
    }

    // ----- Ходы ------------------------------------------------------------

    /** Тап по Stock: добор в Waste, либо переворот Waste → Stock, если Stock пуст. */
    fun drawFromStock(state: GameState): GameState? {
        val stock = state.stock
        val waste = state.waste
        return when {
            stock.cards.isNotEmpty() -> {
                val card = stock.cards.last().copy(faceUp = true)
                val newStock = stock.copy(cards = stock.cards.dropLast(1))
                val newWaste = waste.copy(cards = waste.cards + card)
                state.replacePiles(
                    GameState.STOCK_INDEX to newStock,
                    GameState.WASTE_INDEX to newWaste
                ).copy(moves = state.moves + 1)
            }
            waste.cards.isNotEmpty() -> {
                // Переворачиваем сброс обратно в колоду (в обратном порядке, рубашкой вверх).
                val recycled = waste.cards.reversed().map { it.copy(faceUp = false) }
                val newStock = stock.copy(cards = recycled)
                val newWaste = waste.copy(cards = emptyList())
                state.replacePiles(
                    GameState.STOCK_INDEX to newStock,
                    GameState.WASTE_INDEX to newWaste
                ).copy(moves = state.moves + 1)
            }
            else -> null
        }
    }

    /**
     * Перемещение карты (или серии) из колонки [fromPile] начиная с [cardIndex]
     * в колонку [toPile]. Возвращает новое состояние или null, если ход нелегален.
     */
    fun move(state: GameState, fromPile: Int, cardIndex: Int, toPile: Int): GameState? {
        if (fromPile == toPile) return null
        val from = state.piles[fromPile]
        val to = state.piles[toPile]
        if (cardIndex < 0 || cardIndex >= from.cards.size) return null

        val moving = from.cards.subList(cardIndex, from.cards.size).toList()
        if (moving.isEmpty()) return null
        val head = moving.first()
        if (!head.faceUp) return null

        when (to.type) {
            PileType.FOUNDATION -> {
                if (moving.size != 1) return null // на основание — только одиночную карту
                if (!canPlaceOnFoundation(head, to)) return null
            }
            PileType.TABLEAU -> {
                if (from.type == PileType.TABLEAU && !isMovableRun(from, cardIndex)) return null
                if (!canPlaceOnTableau(head, to)) return null
            }
            else -> return null // в Stock/Waste класть нельзя
        }

        var newFromCards = from.cards.dropLast(moving.size)
        // Если открылась закрытая карта — переворачиваем её.
        if (from.type == PileType.TABLEAU && newFromCards.isNotEmpty() && !newFromCards.last().faceUp) {
            newFromCards = newFromCards.dropLast(1) + newFromCards.last().copy(faceUp = true)
        }
        val newToCards = to.cards + moving

        val result = state.replacePiles(
            fromPile to from.copy(cards = newFromCards),
            toPile to to.copy(cards = newToCards)
        ).copy(moves = state.moves + 1)

        return result.copy(isWon = checkWin(result))
    }

    /**
     * Авто-ход (двойной тап): отправить доступную карту [cardIndex] из [fromPile]
     * на подходящее основание. Возвращает null, если нет подходящего основания.
     */
    fun autoToFoundation(state: GameState, fromPile: Int, cardIndex: Int): GameState? {
        val from = state.piles[fromPile]
        if (cardIndex != from.cards.lastIndex) return null // только верхнюю карту
        val card = from.cards.lastOrNull() ?: return null
        if (!card.faceUp) return null

        state.foundations.forEachIndexed { i, foundation ->
            val foundationIndex = GameState.FOUNDATION_START + i
            if (canPlaceOnFoundation(card, foundation)) {
                return move(state, fromPile, cardIndex, foundationIndex)
            }
        }
        return null
    }

    /** Победа — когда все основания заполнены до MAX_RANK. */
    fun checkWin(state: GameState): Boolean =
        state.foundations.all { it.cards.size == MAX_RANK }

    /** Существует ли хоть один доступный ход (для подсветки/подсказки). */
    fun hasAnyMove(state: GameState): Boolean {
        if (state.stock.cards.isNotEmpty() || state.waste.cards.isNotEmpty()) return true
        // Проверяем перемещения доступных карт.
        val sources = buildList {
            state.waste.cards.lastOrNull()?.let { add(Triple(GameState.WASTE_INDEX, state.waste.cards.lastIndex, it)) }
            state.tableau.forEachIndexed { ti, pile ->
                val pileIndex = state.tableauStart + ti
                pile.cards.forEachIndexed { ci, c -> if (c.faceUp) add(Triple(pileIndex, ci, c)) }
            }
        }
        for ((pileIndex, ci, card) in sources) {
            state.foundations.forEachIndexed { i, f ->
                if (ci == state.piles[pileIndex].cards.lastIndex && canPlaceOnFoundation(card, f)) return true
            }
            state.tableau.forEachIndexed { ti, t ->
                val toIndex = state.tableauStart + ti
                if (toIndex != pileIndex &&
                    isMovableRun(state.piles[pileIndex], ci) &&
                    canPlaceOnTableau(card, t)
                ) return true
            }
        }
        return false
    }

    // ----- Утилита ---------------------------------------------------------

    private fun GameState.replacePiles(vararg replacements: Pair<Int, Pile>): GameState {
        val newPiles = piles.toMutableList()
        for ((index, pile) in replacements) newPiles[index] = pile
        return copy(piles = newPiles)
    }
}
