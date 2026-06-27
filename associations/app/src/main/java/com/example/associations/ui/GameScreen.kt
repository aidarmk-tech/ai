package com.example.associations.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.associations.game.GameLogic
import com.example.associations.game.GameViewModel
import com.example.associations.model.Card
import com.example.associations.model.GameState
import com.example.associations.model.Pile
import com.example.associations.model.PileType

private const val FACE_UP_PEEK = 30
private const val FACE_DOWN_PEEK = 16

/** Текущее состояние перетаскивания. */
private data class DragState(val fromPile: Int, val startIndex: Int)

@Composable
fun GameScreen(
    vm: GameViewModel,
    onMenu: () -> Unit
) {
    val state by vm.state.collectAsStateValue()
    val canUndo by vm.canUndo.collectAsStateValue()

    // Координаты зон для drag-and-drop (в координатах корня).
    val pileBounds = remember { mutableStateMapOf<Int, Rect>() }
    val cardBounds = remember { mutableStateMapOf<Long, Rect>() }

    var drag by remember { mutableStateOf<DragState?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // tap-выбор: (pileIndex, cardIndex)
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun cardKey(pile: Int, index: Int): Long = pile * 10_000L + index

    fun pileAt(point: Offset): Int? =
        pileBounds.entries.firstOrNull { it.value.contains(point) }?.key

    fun clearSelection() { selected = null }

    fun tryMove(from: Int, index: Int, to: Int) {
        vm.onMove(from, index, to)
        clearSelection()
    }

    // Множество легальных целей для подсветки при выбранной карте.
    val legalTargets: Set<Int> = remember(state, selected) {
        val sel = selected ?: return@remember emptySet()
        computeLegalTargets(state, sel.first, sel.second)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopBar(
            level = state.level,
            collected = state.collected.size,
            totalCategories = state.categories.size,
            moves = state.moves,
            elapsedSec = state.elapsedSec,
            canUndo = canUndo,
            onUndo = { vm.undo(); clearSelection() },
            onNew = { vm.newGame(); clearSelection() },
            onMenu = onMenu
        )

        // Верхняя зона: основания + колода + сброс.
        TopPiles(
            state = state,
            selected = selected,
            legalTargets = legalTargets,
            pileBounds = pileBounds,
            cardBounds = cardBounds,
            drag = drag,
            dragOffset = dragOffset,
            onStockClick = { vm.onStockClick(); clearSelection() },
            onTapCard = { pile, index -> handleTap(state, pile, index, selected, { selected = it }, ::tryMove) },
            onDoubleTapCard = { pile, index -> vm.onAutoMove(pile, index); clearSelection() },
            onTapPile = { pile -> selected?.let { tryMove(it.first, it.second, pile) } },
            dragHandlers = DragHandlers(
                onStart = { d -> drag = d; dragOffset = Offset.Zero; clearSelection() },
                onDrag = { dragOffset += it },
                onEnd = {
                    val d = drag
                    if (d != null) {
                        val head = cardBounds[cardKey(d.fromPile, d.startIndex)]
                        if (head != null) {
                            val target = pileAt(head.center + dragOffset)
                            if (target != null) vm.onMove(d.fromPile, d.startIndex, target)
                        }
                    }
                    drag = null; dragOffset = Offset.Zero
                },
                onCancel = { drag = null; dragOffset = Offset.Zero }
            ),
            cardKey = ::cardKey
        )

        Spacer(Modifier.height(8.dp))

        // Tableau.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.tableau.forEachIndexed { ti, pile ->
                    val pileIndex = state.tableauStart + ti
                    TableauColumn(
                        pileIndex = pileIndex,
                        pile = pile,
                        selected = selected,
                        highlighted = legalTargets.contains(pileIndex),
                        drag = drag,
                        dragOffset = dragOffset,
                        cardBounds = cardBounds,
                        pileBounds = pileBounds,
                        onTapCard = { idx -> handleTap(state, pileIndex, idx, selected, { selected = it }, ::tryMove) },
                        onDoubleTapCard = { idx -> vm.onAutoMove(pileIndex, idx); clearSelection() },
                        onTapEmpty = { selected?.let { tryMove(it.first, it.second, pileIndex) } },
                        dragHandlers = DragHandlers(
                            onStart = { d -> drag = d; dragOffset = Offset.Zero; clearSelection() },
                            onDrag = { dragOffset += it },
                            onEnd = {
                                val d = drag
                                if (d != null) {
                                    val head = cardBounds[cardKey(d.fromPile, d.startIndex)]
                                    if (head != null) {
                                        val target = pileAt(head.center + dragOffset)
                                        if (target != null) vm.onMove(d.fromPile, d.startIndex, target)
                                    }
                                }
                                drag = null; dragOffset = Offset.Zero
                            },
                            onCancel = { drag = null; dragOffset = Offset.Zero }
                        ),
                        cardKey = ::cardKey
                    )
                }
            }
        }
    }

    if (state.isWon) {
        WinDialog(
            level = state.level,
            moves = state.moves,
            elapsedSec = state.elapsedSec,
            onNext = { vm.newGame() },
            onMenu = onMenu
        )
    }
}

// ----- Верхняя панель ------------------------------------------------------

@Composable
private fun TopBar(
    level: Int,
    collected: Int,
    totalCategories: Int,
    moves: Int,
    elapsedSec: Int,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onNew: () -> Unit,
    onMenu: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                "Уровень $level · собрано $collected/$totalCategories",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Ходы: $moves   Время: ${formatTime(elapsedSec)}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Отменить")
            }
            TextButton(onClick = onNew) {
                Icon(Icons.Filled.Refresh, contentDescription = "Новая")
            }
            TextButton(onClick = onMenu) { Text("Меню") }
        }
    }
}

// ----- Верхние стопки ------------------------------------------------------

private class DragHandlers(
    val onStart: (DragState) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit
)

@Composable
private fun TopPiles(
    state: GameState,
    selected: Pair<Int, Int>?,
    legalTargets: Set<Int>,
    pileBounds: SnapshotStateMap<Int, Rect>,
    cardBounds: SnapshotStateMap<Long, Rect>,
    drag: DragState?,
    dragOffset: Offset,
    onStockClick: () -> Unit,
    onTapCard: (Int, Int) -> Unit,
    onDoubleTapCard: (Int, Int) -> Unit,
    onTapPile: (Int) -> Unit,
    dragHandlers: DragHandlers,
    cardKey: (Int, Int) -> Long
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Stock
        Box(
            modifier = Modifier
                .onGloballyPositioned { pileBounds[GameState.STOCK_INDEX] = it.boundsInRoot() }
                .clickable { onStockClick() }
        ) {
            val stockTop = state.stock.cards.lastOrNull()
            if (stockTop != null) {
                CardView(stockTop.copy(faceUp = false))
            } else {
                EmptySlot(label = "↻")
            }
        }

        // Waste
        Box(
            modifier = Modifier
                .onGloballyPositioned { pileBounds[GameState.WASTE_INDEX] = it.boundsInRoot() }
        ) {
            val wasteTop = state.waste.cards.lastOrNull()
            val wasteIndex = state.waste.cards.lastIndex
            if (wasteTop != null) {
                DraggableCard(
                    card = wasteTop,
                    pileIndex = GameState.WASTE_INDEX,
                    cardIndex = wasteIndex,
                    isSource = true,
                    selected = selected == (GameState.WASTE_INDEX to wasteIndex),
                    highlighted = false,
                    drag = drag,
                    dragOffset = dragOffset,
                    cardBounds = cardBounds,
                    onTap = { onTapCard(GameState.WASTE_INDEX, wasteIndex) },
                    onDoubleTap = { onDoubleTapCard(GameState.WASTE_INDEX, wasteIndex) },
                    dragHandlers = dragHandlers,
                    cardKey = cardKey
                )
            } else {
                EmptySlot()
            }
        }

        Spacer(Modifier.width(6.dp))

        // Foundations
        state.foundations.forEachIndexed { fi, foundation ->
            val pileIndex = GameState.FOUNDATION_START + fi
            val isCollected = foundation.category != null && foundation.category in state.collected
            Box(
                modifier = Modifier
                    .onGloballyPositioned { pileBounds[pileIndex] = it.boundsInRoot() }
                    .clickable { onTapPile(pileIndex) }
            ) {
                val top = foundation.cards.lastOrNull()
                when {
                    isCollected -> CollectedSlot(foundation.category!!.icon)
                    top != null -> CardView(top, highlighted = legalTargets.contains(pileIndex))
                    else -> EmptySlot(
                        label = foundation.category?.icon,
                        highlighted = legalTargets.contains(pileIndex)
                    )
                }
            }
        }
    }
}

// ----- Колонка tableau -----------------------------------------------------

@Composable
private fun TableauColumn(
    pileIndex: Int,
    pile: Pile,
    selected: Pair<Int, Int>?,
    highlighted: Boolean,
    drag: DragState?,
    dragOffset: Offset,
    cardBounds: SnapshotStateMap<Long, Rect>,
    pileBounds: SnapshotStateMap<Int, Rect>,
    onTapCard: (Int) -> Unit,
    onDoubleTapCard: (Int) -> Unit,
    onTapEmpty: () -> Unit,
    dragHandlers: DragHandlers,
    cardKey: (Int, Int) -> Long
) {
    // Высота колонки рассчитывается по числу карт.
    var totalPeek = 0
    pile.cards.forEachIndexed { i, c ->
        if (i == pile.cards.lastIndex) return@forEachIndexed
        totalPeek += if (c.faceUp) FACE_UP_PEEK else FACE_DOWN_PEEK
    }
    val columnHeightDp = (totalPeek + 84 + 8).dp

    Box(
        modifier = Modifier
            .width(CARD_WIDTH)
            .height(columnHeightDp)
            .onGloballyPositioned { pileBounds[pileIndex] = it.boundsInRoot() }
            .clickable(enabled = pile.cards.isEmpty()) { onTapEmpty() }
    ) {
        if (pile.cards.isEmpty()) {
            EmptySlot(highlighted = highlighted)
        } else {
            var yPx = 0
            pile.cards.forEachIndexed { ci, card ->
                val yOffset = yPx
                val isSource = card.faceUp
                // Используем offset (а не graphicsLayer): он влияет на layout,
                // поэтому boundsInRoot отражает реальную позицию карты — это нужно
                // для корректного хит-теста при drag-and-drop.
                Box(
                    modifier = Modifier.offset(y = yOffset.dp)
                ) {
                    DraggableCard(
                        card = card,
                        pileIndex = pileIndex,
                        cardIndex = ci,
                        isSource = isSource,
                        selected = selected == (pileIndex to ci),
                        highlighted = highlighted && ci == pile.cards.lastIndex,
                        drag = drag,
                        dragOffset = dragOffset,
                        cardBounds = cardBounds,
                        onTap = { onTapCard(ci) },
                        onDoubleTap = { onDoubleTapCard(ci) },
                        dragHandlers = dragHandlers,
                        cardKey = cardKey
                    )
                }
                yPx += if (card.faceUp) FACE_UP_PEEK else FACE_DOWN_PEEK
            }
        }
    }
}

// ----- Одна перетаскиваемая карта ------------------------------------------

@Composable
private fun DraggableCard(
    card: Card,
    pileIndex: Int,
    cardIndex: Int,
    isSource: Boolean,
    selected: Boolean,
    highlighted: Boolean,
    drag: DragState?,
    dragOffset: Offset,
    cardBounds: SnapshotStateMap<Long, Rect>,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    dragHandlers: DragHandlers,
    cardKey: (Int, Int) -> Long
) {
    val isDragging = drag != null && drag.fromPile == pileIndex && cardIndex >= drag.startIndex
    val key = cardKey(pileIndex, cardIndex)

    var mod = Modifier
        .onGloballyPositioned { cardBounds[key] = it.boundsInRoot() }
        .graphicsLayer {
            if (isDragging) {
                translationX = dragOffset.x
                translationY = dragOffset.y
            }
        }
        .zIndex(if (isDragging) 100f else cardIndex.toFloat())

    if (isSource) {
        mod = mod
            .pointerInput(pileIndex, cardIndex) {
                detectTapGesturesCompat(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() }
                )
            }
            .pointerInput(pileIndex, cardIndex) {
                detectDragGesturesCompat(
                    onStart = { dragHandlers.onStart(DragState(pileIndex, cardIndex)) },
                    onDrag = { dragHandlers.onDrag(it) },
                    onEnd = { dragHandlers.onEnd() },
                    onCancel = { dragHandlers.onCancel() }
                )
            }
    }

    CardView(
        card = card,
        modifier = mod,
        selected = selected,
        highlighted = highlighted
    )
}

// ----- Диалог победы -------------------------------------------------------

@Composable
private fun WinDialog(
    level: Int,
    moves: Int,
    elapsedSec: Int,
    onNext: () -> Unit,
    onMenu: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Уровень $level пройден! 🎉", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Ходы: $moves", color = MaterialTheme.colorScheme.onSurface)
            Text("Время: ${formatTime(elapsedSec)}", color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                "Уровень ${level + 1} будет сложнее: больше категорий и длиннее цепочки.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNext) { Text("Уровень ${level + 1} ▶") }
                TextButton(onClick = onMenu) { Text("В меню") }
            }
        }
    }
}

// ----- Помощники -----------------------------------------------------------

private fun formatTime(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}

/** Логика tap-выбора: первый тап выбирает источник, второй — выполняет ход. */
private fun handleTap(
    state: GameState,
    pileIndex: Int,
    cardIndex: Int,
    selected: Pair<Int, Int>?,
    setSelected: (Pair<Int, Int>?) -> Unit,
    tryMove: (Int, Int, Int) -> Unit
) {
    if (selected == null) {
        // Выбираем только корректный источник.
        if (isValidSource(state, pileIndex, cardIndex)) {
            setSelected(pileIndex to cardIndex)
        }
        return
    }
    if (selected.first == pileIndex) {
        // Повторный тап по той же стопке — снимаем выбор.
        setSelected(null)
        return
    }
    tryMove(selected.first, selected.second, pileIndex)
}

private fun isValidSource(state: GameState, pileIndex: Int, cardIndex: Int): Boolean {
    val pile = state.piles[pileIndex]
    val card = pile.cards.getOrNull(cardIndex) ?: return false
    if (!card.faceUp) return false
    return when (pile.type) {
        PileType.WASTE -> cardIndex == pile.cards.lastIndex
        PileType.TABLEAU -> GameLogic.isMovableRun(pile, cardIndex)
        else -> false
    }
}

private fun computeLegalTargets(state: GameState, fromPile: Int, cardIndex: Int): Set<Int> {
    if (!isValidSource(state, fromPile, cardIndex)) return emptySet()
    val targets = mutableSetOf<Int>()
    val pile = state.piles[fromPile]
    val head = pile.cards[cardIndex]
    val singleCard = cardIndex == pile.cards.lastIndex

    state.foundations.forEachIndexed { i, f ->
        val idx = GameState.FOUNDATION_START + i
        if (singleCard && GameLogic.canPlaceOnFoundation(head, f)) targets += idx
    }
    state.tableau.forEachIndexed { ti, t ->
        val idx = state.tableauStart + ti
        if (idx != fromPile && GameLogic.canPlaceOnTableau(head, t)) targets += idx
    }
    return targets
}
