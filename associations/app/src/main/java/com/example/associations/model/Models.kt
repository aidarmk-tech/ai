package com.example.associations.model

/**
 * Категория карты. Внутри категории карта имеет ранг 1..MAX_RANK —
 * «ассоциативный порядок» (например, города по населению).
 *
 * Чтобы добавить новую категорию, достаточно дописать значение enum и
 * заполнить список названий в [CardContent]. Эмодзи легко заменить на ресурс
 * картинки в UI-слое.
 */
enum class Category(val titleRu: String, val icon: String) {
    CITY("Города", "🏙️"),
    FURNITURE("Мебель", "🛋️"),
    ANIMAL("Животные", "🐾"),
    FOOD("Еда", "🍽️")
}

/** Максимальный ранг (длина «ассоциативной цепочки» внутри категории). */
const val MAX_RANK = 6

data class Card(
    val id: Int,
    val category: Category,
    val rank: Int,        // 1..MAX_RANK — ассоциативный порядок
    val title: String,    // "Москва", "Диван"...
    val faceUp: Boolean = false
)

enum class PileType { STOCK, WASTE, FOUNDATION, TABLEAU }

data class Pile(
    val type: PileType,
    val category: Category? = null,  // задаётся для FOUNDATION
    val cards: List<Card> = emptyList()
)

data class GameState(
    val piles: List<Pile>,
    val moves: Int = 0,
    val elapsedSec: Int = 0,
    val isWon: Boolean = false
) {
    // Индексы фиксированы для удобного и стабильного обращения из UI:
    // 0 — STOCK, 1 — WASTE, 2..(2+N-1) — FOUNDATION по числу категорий,
    // далее — TABLEAU.
    val stock: Pile get() = piles[STOCK_INDEX]
    val waste: Pile get() = piles[WASTE_INDEX]
    val foundations: List<Pile>
        get() = piles.subList(FOUNDATION_START, FOUNDATION_START + Category.values().size)
    val tableau: List<Pile>
        get() = piles.subList(tableauStart, piles.size)

    val tableauStart: Int get() = FOUNDATION_START + Category.values().size

    companion object {
        const val STOCK_INDEX = 0
        const val WASTE_INDEX = 1
        const val FOUNDATION_START = 2
    }
}

/** Стартовый контент. Ранг = позиция в списке (1..MAX_RANK). */
object CardContent {
    val titles: Map<Category, List<String>> = mapOf(
        Category.CITY to listOf("Астана", "Алматы", "Лондон", "Москва", "Токио", "Стамбул"),
        Category.FURNITURE to listOf("Табурет", "Стул", "Стол", "Комод", "Шкаф", "Диван"),
        Category.ANIMAL to listOf("Мышь", "Кот", "Собака", "Волк", "Лошадь", "Слон"),
        Category.FOOD to listOf("Яблоко", "Салат", "Суп", "Паста", "Стейк", "Торт")
    )

    /** Полная колода: каждая категория × MAX_RANK карт. */
    fun buildDeck(): List<Card> {
        var id = 0
        val deck = mutableListOf<Card>()
        for (category in Category.values()) {
            val names = titles.getValue(category)
            for (rank in 1..MAX_RANK) {
                deck += Card(
                    id = id++,
                    category = category,
                    rank = rank,
                    title = names[rank - 1],
                    faceUp = false
                )
            }
        }
        return deck
    }
}
