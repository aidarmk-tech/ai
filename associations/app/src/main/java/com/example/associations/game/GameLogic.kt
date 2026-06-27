package com.example.associations.game

import com.example.associations.model.Card
import com.example.associations.model.CardContent
import com.example.associations.model.GROUP_SIZE
import com.example.associations.model.GameState
import com.example.associations.model.Group
import com.example.associations.model.Pile
import com.example.associations.model.PileType
import kotlin.random.Random

/** Конфигурация уровня. */
data class LevelConfig(
    val level: Int,
    val groups: List<Group>,
    val columns: Int
) {
    val totalCards: Int get() = groups.size * GROUP_SIZE
}

/**
 * Чистая игровая логика (без побочных эффектов).
 *
 * Правила:
 *  • ОСНОВУ можно положить только в пустой фундамент или на пустую колонку.
 *  • ПРЕДМЕТ можно положить на фундамент своей группы (после основы, в любом
 *    порядке), на колонку с верхней картой той же группы или на пустую колонку.
 *  • Когда фундамент собран полностью (основа + все предметы) — он уходит с поля.
 *  • Победа — когда собраны все группы уровня.
 *
 * Сложность растёт: уровень 1 — 4 группы, далее +1 группа за победу (до 12).
 */
object GameLogic {

    private val ALL_GROUPS = Group.values().toList()

    fun levelConfig(level: Int): LevelConfig {
        val l = level.coerceAtLeast(1)
        val groupCount = (3 + l).coerceIn(4, ALL_GROUPS.size) // 4,5,6,...,12
        val columns = (groupCount + 3).coerceIn(6, 9)
        return LevelConfig(l, ALL_GROUPS.take(groupCount), columns)
    }

    fun newGame(level: Int = 1, seed: Long = System.currentTimeMillis()): GameState {
        val config = levelConfig(level)
        val deck = CardContent.buildDeck(config.groups).toMutableList()
        deck.shuffle(Random(seed))

        val total = deck.size
        val columns = config.columns
        // В поле — примерно половина карт (колонки неглубокие), остальное в колоду.
        val tableauTotal = (total + 1) / 2
        val sizes = IntArray(columns) { tableauTotal / columns }
        repeat(tableauTotal % columns) { sizes[it]++ }

        val tableau = MutableList(columns) { mutableListOf<Card>() }
        var index = 0
        for (col in 0 until columns) {
            for (row in 0 until sizes[col]) {
                val faceUp = row == sizes[col] - 1 // открыта только верхняя карта
                tableau[col].add(deck[index].copy(faceUp = faceUp))
                index++
            }
        }
        val stockCards = deck.subList(index, deck.size).map { it.copy(faceUp = false) }

        val piles = buildList {
            add(Pile(PileType.STOCK, cards = stockCards))
            add(Pile(PileType.WASTE, cards = emptyList()))
            repeat(config.groups.size) { add(Pile(PileType.FOUNDATION)) } // универсальные слоты
            for (col in tableau) add(Pile(PileType.TABLEAU, cards = col.toList()))
        }

        return GameState(
            piles = piles,
            groups = config.groups,
            level = config.level,
            collected = emptySet(),
            moves = 0,
            elapsedSec = 0,
            isWon = false
        )
    }

    // ----- Легальность -----------------------------------------------------

    fun canPlaceOnFoundation(card: Card, foundation: Pile): Boolean {
        if (foundation.type != PileType.FOUNDATION) return false
        return if (foundation.cards.isEmpty()) {
            card.isBase // пустой слот принимает любую основу
        } else {
            !card.isBase && foundation.group == card.group // предмет своей группы
        }
    }

    fun canPlaceOnTableau(card: Card, column: Pile): Boolean {
        if (column.type != PileType.TABLEAU) return false
        val top = column.cards.lastOrNull() ?: return true // на пустую — любую карту
        if (card.isBase) return false                       // основу нельзя ставить на карту
        return card.group == top.group                      // предмет на карту своей группы
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

    /** Перемещение одной (верхней) карты из [fromPile] в [toPile]. */
    fun move(state: GameState, fromPile: Int, cardIndex: Int, toPile: Int): GameState? {
        if (fromPile == toPile) return null
        val from = state.piles[fromPile]
        val to = state.piles[toPile]
        if (cardIndex != from.cards.lastIndex) return null // двигаем только верхнюю карту
        val card = from.cards.lastOrNull() ?: return null
        if (!card.faceUp) return null
        if (from.type == PileType.FOUNDATION) return null  // с фундамента не снимаем

        when (to.type) {
            PileType.FOUNDATION -> {
                if (to.group != null && to.group in state.collected) return null // слот уже собран
                if (!canPlaceOnFoundation(card, to)) return null
            }
            PileType.TABLEAU -> if (!canPlaceOnTableau(card, to)) return null
            else -> return null
        }

        // Убираем карту из источника, открываем нижнюю при необходимости.
        var newFromCards = from.cards.dropLast(1)
        if (from.type == PileType.TABLEAU && newFromCards.isNotEmpty() && !newFromCards.last().faceUp) {
            newFromCards = newFromCards.dropLast(1) + newFromCards.last().copy(faceUp = true)
        }

        // Кладём на назначение; фундамент закрепляем за группой; собранный — убираем.
        var newCollected = state.collected
        val newToCards = to.cards + card
        val finalTo = when {
            to.type == PileType.FOUNDATION && newToCards.size == GROUP_SIZE -> {
                newCollected = state.collected + card.group
                Pile(PileType.FOUNDATION, group = card.group, cards = emptyList())
            }
            to.type == PileType.FOUNDATION -> {
                to.copy(group = card.group, cards = newToCards)
            }
            else -> to.copy(cards = newToCards)
        }

        val result = state.replacePiles(
            fromPile to from.copy(cards = newFromCards),
            toPile to finalTo
        ).copy(moves = state.moves + 1, collected = newCollected)

        return result.copy(isWon = checkWin(result))
    }

    /** Авто-ход (двойной тап): верхнюю карту [fromPile] на подходящий фундамент. */
    fun autoToFoundation(state: GameState, fromPile: Int, cardIndex: Int): GameState? {
        val from = state.piles[fromPile]
        if (cardIndex != from.cards.lastIndex) return null
        val card = from.cards.lastOrNull() ?: return null
        if (!card.faceUp || from.type == PileType.FOUNDATION) return null

        // Сначала пытаемся положить предмет на уже открытый фундамент его группы.
        state.foundations.forEachIndexed { i, f ->
            if (f.cards.isNotEmpty() && canPlaceOnFoundation(card, f)) {
                return move(state, fromPile, cardIndex, GameState.FOUNDATION_START + i)
            }
        }
        // Иначе — основу в первый свободный (не собранный) фундамент.
        if (card.isBase) {
            state.foundations.forEachIndexed { i, f ->
                val free = f.cards.isEmpty() && (f.group == null || f.group !in state.collected)
                if (free) return move(state, fromPile, cardIndex, GameState.FOUNDATION_START + i)
            }
        }
        return null
    }

    fun checkWin(state: GameState): Boolean = state.collected.size == state.groups.size

    /** Награда в монетах за пройденный уровень. */
    fun coinsReward(level: Int, moves: Int, elapsedSec: Int): Int {
        val base = 50 + level * 15
        val timeBonus = (180 - elapsedSec).coerceAtLeast(0) / 3
        val moveBonus = (level * GROUP_SIZE * 3 - moves).coerceAtLeast(0)
        return base + timeBonus + moveBonus
    }

    // ----- Утилита ---------------------------------------------------------

    private fun GameState.replacePiles(vararg replacements: Pair<Int, Pile>): GameState {
        val newPiles = piles.toMutableList()
        for ((index, pile) in replacements) newPiles[index] = pile
        return copy(piles = newPiles)
    }
}
