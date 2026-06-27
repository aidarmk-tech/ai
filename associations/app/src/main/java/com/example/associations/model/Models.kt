package com.example.associations.model

/**
 * Категория карты. Внутри категории карта имеет ранг 1..M —
 * «ассоциативный порядок» (города по населению, мебель по размеру и т.д.).
 *
 * Чтобы добавить новую категорию — допишите значение enum и список названий
 * в [CardContent] (минимум [MAX_RANK_CAP] элементов). Эмодзи легко заменить
 * на ресурс-картинку в UI.
 */
enum class Category(val titleRu: String, val icon: String) {
    CITY("Города", "🏙️"),
    FURNITURE("Мебель", "🛋️"),
    ANIMAL("Животные", "🐾"),
    FOOD("Еда", "🍽️"),
    TRANSPORT("Транспорт", "🚗"),
    CONTAINER("Ёмкости", "🪣"),
    WATER("Водоёмы", "🌊"),
    CLOTHES("Одежда", "🧥"),
    SPACE("Космос", "🪐"),
    TIME("Время", "⏳"),
    BUILDING("Постройки", "🏢"),
    WEATHER("Погода", "🌧️")
}

/** Максимально возможный ранг (длина «ассоциативной цепочки»). */
const val MAX_RANK_CAP = 10

data class Card(
    val id: Int,
    val category: Category,
    val rank: Int,        // 1..M — ассоциативный порядок
    val title: String,    // "Москва", "Диван"...
    val faceUp: Boolean = false
)

enum class PileType { STOCK, WASTE, FOUNDATION, TABLEAU }

data class Pile(
    val type: PileType,
    val category: Category? = null,  // задаётся для FOUNDATION
    val cards: List<Card> = emptyList()
)

/**
 * Полное состояние партии. Конфигурация уровня (набор категорий, длина ранга)
 * хранится прямо в состоянии — так UI и логика не зависят от глобальных констант.
 *
 * Раскладка piles фиксирована: 0 — STOCK, 1 — WASTE, далее [categories].size
 * оснований, затем колонки tableau.
 */
data class GameState(
    val piles: List<Pile>,
    val categories: List<Category>,     // активные категории уровня
    val rankLength: Int,                // M — длина цепочки на этом уровне
    val level: Int = 1,
    val collected: Set<Category> = emptySet(), // собранные (ушедшие с поля) категории
    val moves: Int = 0,
    val elapsedSec: Int = 0,
    val isWon: Boolean = false
) {
    val foundationCount: Int get() = categories.size
    val tableauStart: Int get() = FOUNDATION_START + foundationCount

    val stock: Pile get() = piles[STOCK_INDEX]
    val waste: Pile get() = piles[WASTE_INDEX]
    val foundations: List<Pile>
        get() = piles.subList(FOUNDATION_START, FOUNDATION_START + foundationCount)
    val tableau: List<Pile>
        get() = piles.subList(tableauStart, piles.size)

    companion object {
        const val STOCK_INDEX = 0
        const val WASTE_INDEX = 1
        const val FOUNDATION_START = 2
    }
}

/**
 * Контент. Каждый список упорядочен по «ассоциативному» принципу (возрастание):
 * города — по населению, мебель/животные/постройки — по размеру, еда — по
 * «тяжести», транспорт — по скорости, время — по длительности и т.д.
 * Берётся первые M элементов под текущий уровень.
 */
object CardContent {
    val titles: Map<Category, List<String>> = mapOf(
        Category.CITY to listOf(
            "Астана", "Алматы", "Берлин", "Лондон", "Москва",
            "Каир", "Стамбул", "Дели", "Шанхай", "Токио"
        ),
        Category.FURNITURE to listOf(
            "Табурет", "Стул", "Тумба", "Стол", "Комод",
            "Кресло", "Шкаф", "Диван", "Кровать", "Гардероб"
        ),
        Category.ANIMAL to listOf(
            "Мышь", "Кот", "Собака", "Волк", "Кабан",
            "Лошадь", "Корова", "Медведь", "Носорог", "Слон"
        ),
        Category.FOOD to listOf(
            "Яблоко", "Салат", "Суп", "Паста", "Пицца",
            "Бургер", "Стейк", "Плов", "Торт", "Шашлык"
        ),
        Category.TRANSPORT to listOf(
            "Пешеход", "Велосипед", "Самокат", "Скутер", "Машина",
            "Поезд", "Электричка", "Самолёт", "Истребитель", "Ракета"
        ),
        Category.CONTAINER to listOf(
            "Напёрсток", "Рюмка", "Стакан", "Кружка", "Бутылка",
            "Кувшин", "Ведро", "Бочка", "Цистерна", "Резервуар"
        ),
        Category.WATER to listOf(
            "Лужа", "Ручей", "Пруд", "Озеро", "Река",
            "Залив", "Канал", "Море", "Пролив", "Океан"
        ),
        Category.CLOTHES to listOf(
            "Майка", "Футболка", "Рубашка", "Джемпер", "Худи",
            "Жилет", "Кофта", "Куртка", "Пальто", "Шуба"
        ),
        Category.SPACE to listOf(
            "Астероид", "Спутник", "Луна", "Меркурий", "Марс",
            "Земля", "Нептун", "Сатурн", "Юпитер", "Солнце"
        ),
        Category.TIME to listOf(
            "Секунда", "Минута", "Час", "Сутки", "Неделя",
            "Месяц", "Год", "Век", "Тысячелетие", "Эра"
        ),
        Category.BUILDING to listOf(
            "Будка", "Сарай", "Изба", "Дом", "Коттедж",
            "Башня", "Высотка", "Небоскрёб", "Телебашня", "Мегаполис"
        ),
        Category.WEATHER to listOf(
            "Роса", "Туман", "Морось", "Дождь", "Снег",
            "Град", "Ливень", "Гроза", "Шторм", "Ураган"
        )
    )

    /** Колода для заданных категорий и длины ранга M. */
    fun buildDeck(categories: List<Category>, rankLength: Int): List<Card> {
        var id = 0
        val deck = mutableListOf<Card>()
        for (category in categories) {
            val names = titles.getValue(category)
            for (rank in 1..rankLength) {
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
