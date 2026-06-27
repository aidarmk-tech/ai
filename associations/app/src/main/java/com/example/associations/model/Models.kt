package com.example.associations.model

/**
 * Ассоциативная группа. У каждой группы есть карта-ОСНОВА (сам концепт, напр.
 * «Город», «Рыцарь») и набор ПРЕДМЕТОВ, которые с ней ассоциируются.
 *
 * `color` — фирменный цвет группы (ARGB), используется для оформления карт.
 * Новую группу добавить просто: дописать значение enum и список предметов в
 * [CardContent].
 */
enum class Group(val title: String, val icon: String, val color: Long) {
    CITY("Город", "🏙️", 0xFF3B82F6),
    KNIGHT("Рыцарь", "⚔️", 0xFF8B5CF6),
    KITCHEN("Кухня", "🍳", 0xFFEF4444),
    SEA("Море", "🌊", 0xFF06B6D4),
    SPACE("Космос", "🪐", 0xFF6366F1),
    FOREST("Лес", "🌲", 0xFF22C55E),
    SCHOOL("Школа", "🎒", 0xFFF59E0B),
    SPORT("Спорт", "🏟️", 0xFFF97316),
    MUSIC("Музыка", "🎵", 0xFFEC4899),
    WINTER("Зима", "❄️", 0xFF38BDF8),
    FARM("Ферма", "🌾", 0xFF84CC16),
    CASTLE("Замок", "🏰", 0xFFA855F7)
}

/** Предметов в каждой группе (+1 карта-основа = размер группы). */
const val MEMBERS_PER_GROUP = 5
const val GROUP_SIZE = MEMBERS_PER_GROUP + 1

data class Card(
    val id: Int,
    val group: Group,
    val isBase: Boolean,     // true — карта-основа, false — предмет
    val title: String,
    val icon: String,
    val faceUp: Boolean = false
)

enum class PileType { STOCK, WASTE, FOUNDATION, TABLEAU }

data class Pile(
    val type: PileType,
    val group: Group? = null,    // для FOUNDATION задаётся, когда положена основа
    val cards: List<Card> = emptyList()
)

/**
 * Состояние партии. Фундаменты (основания) — универсальные слоты: пустой слот
 * принимает любую основу, после чего «закрепляется» за её группой и принимает
 * её предметы. Собранная группа уходит с поля (в [collected]).
 *
 * Раскладка piles: 0 — STOCK, 1 — WASTE, далее [groups].size фундаментов,
 * затем колонки tableau.
 */
data class GameState(
    val piles: List<Pile>,
    val groups: List<Group>,
    val level: Int = 1,
    val collected: Set<Group> = emptySet(),
    val moves: Int = 0,
    val elapsedSec: Int = 0,
    val isWon: Boolean = false
) {
    val foundationCount: Int get() = groups.size
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

/** Контент: для каждой группы — список предметов (название, эмодзи). */
object CardContent {
    val members: Map<Group, List<Pair<String, String>>> = mapOf(
        Group.CITY to listOf(
            "Москва" to "🏰", "Лондон" to "🎡", "Париж" to "🗼", "Токио" to "🗾", "Каир" to "🐫"
        ),
        Group.KNIGHT to listOf(
            "Меч" to "🗡️", "Щит" to "🛡️", "Шлем" to "⛑️", "Доспехи" to "🦾", "Конь" to "🐴"
        ),
        Group.KITCHEN to listOf(
            "Нож" to "🔪", "Ложка" to "🥄", "Тарелка" to "🍽️", "Кастрюля" to "🍲", "Чайник" to "🫖"
        ),
        Group.SEA to listOf(
            "Рыба" to "🐟", "Краб" to "🦀", "Корабль" to "🚢", "Осьминог" to "🐙", "Ракушка" to "🐚"
        ),
        Group.SPACE to listOf(
            "Звезда" to "⭐", "Ракета" to "🚀", "Комета" to "☄️", "Луна" to "🌙", "Спутник" to "🛰️"
        ),
        Group.FOREST to listOf(
            "Дерево" to "🌳", "Гриб" to "🍄", "Лиса" to "🦊", "Сова" to "🦉", "Ягода" to "🫐"
        ),
        Group.SCHOOL to listOf(
            "Книга" to "📖", "Ручка" to "🖊️", "Глобус" to "🌍", "Линейка" to "📐", "Карандаш" to "✏️"
        ),
        Group.SPORT to listOf(
            "Мяч" to "⚽", "Кубок" to "🏆", "Кроссовки" to "👟", "Гиря" to "🏋️", "Медаль" to "🏅"
        ),
        Group.MUSIC to listOf(
            "Гитара" to "🎸", "Скрипка" to "🎻", "Барабан" to "🥁", "Труба" to "🎺", "Микрофон" to "🎤"
        ),
        Group.WINTER to listOf(
            "Снеговик" to "⛄", "Санки" to "🛷", "Ёлка" to "🎄", "Коньки" to "⛸️", "Варежка" to "🧤"
        ),
        Group.FARM to listOf(
            "Корова" to "🐄", "Курица" to "🐔", "Трактор" to "🚜", "Свинья" to "🐖", "Овца" to "🐑"
        ),
        Group.CASTLE to listOf(
            "Корона" to "👑", "Трон" to "🪑", "Флаг" to "🚩", "Ключ" to "🗝️", "Дракон" to "🐉"
        )
    )

    /** Колода для заданных групп: на каждую группу — основа + её предметы. */
    fun buildDeck(groups: List<Group>): List<Card> {
        var id = 0
        val deck = mutableListOf<Card>()
        for (g in groups) {
            deck += Card(id++, g, isBase = true, title = g.title, icon = g.icon)
            for ((title, icon) in members.getValue(g)) {
                deck += Card(id++, g, isBase = false, title = title, icon = icon)
            }
        }
        return deck
    }
}
