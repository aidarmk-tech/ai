package com.example.associations.game

import com.example.associations.model.Card
import com.example.associations.model.CardContent
import com.example.associations.model.Category
import com.example.associations.model.GameState
import com.example.associations.model.MAX_RANK_CAP
import com.example.associations.model.Pile
import com.example.associations.model.PileType
import kotlin.random.Random

/** Конфигурация одного уровня сложности. */
data class LevelConfig(
    val level: Int,
    val categories: List<Category>,
    val rankLength: Int,
    val columns: Int
) {
    val totalCards: Int get() = categories.size * rankLength
}

/**
 * Чистая игровая логика. Функции принимают [GameState] и возвращают новый
 * (или null, если ход нелегален) — без побочных эффектов.
 *
 * Сложность растёт с уровнем: больше категорий, длиннее ассоциативные цепочки,
 * больше колонок. Размер колоды ограничен [MAX_TOTAL_CARDS] (≈100 карт).
 * Когда основание собрано до конца (1..M) — оно «уходит с поля»: карты убираются,
 * категория попадает в [GameState.collected], слот освобождается.
 */
object GameLogic {

    private const val MAX_TOTAL_CARDS = 100
    private val ALL_CATEGORIES = Category.values().toList()

    /** Параметры уровня. С ростом уровня плавно увеличиваем масштаб. */
    fun levelConfig(level: Int): LevelConfig {
        val l = level.coerceAtLeast(1)
        var categoriesCount = (3 + l).coerceIn(4, ALL_CATEGORIES.size)   // 4,5,6,...,12
        var rankLength = (5 + (l - 1) / 2).coerceIn(6, MAX_RANK_CAP)     // 6,6,7,7,8,...
        // Ограничиваем общий размер колоды.
        while (categoriesCount * rankLength > MAX_TOTAL_CARDS) {
            if (rankLength > 6) rankLength-- else categoriesCount--
        }
        val columns = (6 + l / 2).coerceIn(7, 9)
        val categories = ALL_CATEGORIES.take(categoriesCount)
        return LevelConfig(l, categories, rankLength, columns)
    }

    fun newGame(level: Int = 1, seed: Long = System.currentTimeMillis()): GameState {
        val config = levelConfig(level)
        val deck = CardContent.buildDeck(config.categories, config.rankLength).toMutableList()
        deck.shuffle(Random(seed))

        // Раздаём ~2/3 карт по колонкам (почти равномерно), остальное — в Stock.
        val total = deck.size
        val tableauTotal = (total * 2) / 3
        val columns = config.columns
        val sizes = IntArray(columns) { tableauTotal / columns }
        repeat(tableauTotal % columns) { sizes[it]++ }

        val tableau = MutableList(columns) { mutableListOf<Card>() }
        var index = 0
        for (col in 0 until columns) {
            val size = sizes[col]
            for (row in 0 until size) {
                val faceUp = row == size - 1 // открыта только верхняя карта
                tableau[col].add(deck[index].copy(faceUp = faceUp))
                index++
            }
        }
        val stockCards = deck.subList(index, deck.size).map { it.copy(faceUp = false) }

        val piles = buildList {
            add(Pile(PileType.STOCK, cards = stockCards))
            add(Pile(PileType.WASTE, cards = emptyList()))
            for (category in config.categories) {
                add(Pile(PileType.FOUNDATION, category = category, cards = emptyList()))
            }
            for (col in tableau) {
                add(Pile(PileType.TABLEAU, cards = col.toList()))
            }
        }

        return GameState(
            piles = piles,
            categories = config.categories,
            rankLength = config.rankLength,
            level = config.level,
            collected = emptySet(),
            moves = 0,
            elapsedSec = 0,
            isWon = false
        )
    }

    // ----- Проверки легальности -------------------------------------------

    fun canPlaceOnFoundation(card: Card, foundation: Pile): Boolean {
        if (foundation.type != PileType.FOUNDATION) return false
        if (foundation.category != card.category) return false
        val topRank = foundation.cards.lastOrNull()?.rank ?: 0
        return card.rank == topRank + 1
    }

    fun canPlaceOnTableau(card: Card, column: Pile): Boolean {
        if (column.type != PileType.TABLEAU) return false
        val top = column.cards.lastOrNull() ?: return true // на пустую — любую
        return card.rank == top.rank - 1 && card.category != top.category
    }

    /** Корректная ли серия с [startIndex]: открыта, убывает по рангу, чередует категории. */
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

    fun drawFromStock(state: GameState): GameState? {
        val stock = state.stock
        val waste = state.waste
        return when {
            stock.cards.isNotEmpty() -> {
                val card = stock.cards.last().copy(faceUp = true)
                state.replacePiles(
                    GameState.STOCK_INDEX to stock.copy(cards = stock.cards.dropLast(1)),
                    GameState.WASTE_INDEX to waste.copy(cards = waste.cards + card)
                ).copy(moves = state.moves + 1)
            }
            waste.cards.isNotEmpty() -> {
                val recycled = waste.cards.reversed().map { it.copy(faceUp = false) }
                state.replacePiles(
                    GameState.STOCK_INDEX to stock.copy(cards = recycled),
                    GameState.WASTE_INDEX to waste.copy(cards = emptyList())
                ).copy(moves = state.moves + 1)
            }
            else -> null
        }
    }

    /**
     * Перемещение карты/серии из [fromPile] (с [cardIndex]) в [toPile].
     * Если основание собирается полностью — оно уходит с поля (collected).
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
                if (moving.size != 1) return null
                if (to.category != null && to.category in state.collected) return null
                if (!canPlaceOnFoundation(head, to)) return null
            }
            PileType.TABLEAU -> {
                if (from.type == PileType.TABLEAU && !isMovableRun(from, cardIndex)) return null
                if (!canPlaceOnTableau(head, to)) return null
            }
            else -> return null
        }

        // Убираем карты из источника, при необходимости переворачиваем открывшуюся.
        var newFromCards = from.cards.dropLast(moving.size)
        if (from.type == PileType.TABLEAU && newFromCards.isNotEmpty() && !newFromCards.last().faceUp) {
            newFromCards = newFromCards.dropLast(1) + newFromCards.last().copy(faceUp = true)
        }

        // Кладём на назначение; если основание собрано — оно уходит с поля.
        var newCollected = state.collected
        val newToCards = to.cards + moving
        val finalTo = if (to.type == PileType.FOUNDATION && newToCards.size == state.rankLength) {
            newCollected = state.collected + to.category!!
            to.copy(cards = emptyList())
        } else {
            to.copy(cards = newToCards)
        }

        val result = state.replacePiles(
            fromPile to from.copy(cards = newFromCards),
            toPile to finalTo
        ).copy(moves = state.moves + 1, collected = newCollected)

        return result.copy(isWon = checkWin(result))
    }

    /** Авто-ход (двойной тап): верхнюю карту [fromPile] на подходящее основание. */
    fun autoToFoundation(state: GameState, fromPile: Int, cardIndex: Int): GameState? {
        val from = state.piles[fromPile]
        if (cardIndex != from.cards.lastIndex) return null
        val card = from.cards.lastOrNull() ?: return null
        if (!card.faceUp) return null

        state.foundations.forEachIndexed { i, foundation ->
            val foundationIndex = GameState.FOUNDATION_START + i
            val notCollected = foundation.category == null || foundation.category !in state.collected
            if (notCollected && canPlaceOnFoundation(card, foundation)) {
                return move(state, fromPile, cardIndex, foundationIndex)
            }
        }
        return null
    }

    /** Победа — когда все категории уровня собраны (ушли с поля). */
    fun checkWin(state: GameState): Boolean =
        state.collected.size == state.categories.size

    fun hasAnyMove(state: GameState): Boolean {
        if (state.stock.cards.isNotEmpty() || state.waste.cards.isNotEmpty()) return true
        val sources = buildList {
            state.waste.cards.lastOrNull()?.let { add(Triple(GameState.WASTE_INDEX, state.waste.cards.lastIndex, it)) }
            state.tableau.forEachIndexed { ti, pile ->
                val pileIndex = state.tableauStart + ti
                pile.cards.forEachIndexed { ci, c -> if (c.faceUp) add(Triple(pileIndex, ci, c)) }
            }
        }
        for ((pileIndex, ci, card) in sources) {
            state.foundations.forEachIndexed { i, f ->
                val notCollected = f.category == null || f.category !in state.collected
                if (ci == state.piles[pileIndex].cards.lastIndex &&
                    notCollected &&
                    canPlaceOnFoundation(card, f)
                ) return true
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
