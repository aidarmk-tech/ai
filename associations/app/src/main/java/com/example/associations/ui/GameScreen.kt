package com.example.associations.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.associations.game.GameLogic
import com.example.associations.game.GameViewModel
import com.example.associations.model.Card
import com.example.associations.model.GameState
import com.example.associations.model.Pile
import com.example.associations.model.PileType
import com.example.associations.ui.theme.Gold

private data class DragState(val fromPile: Int, val startIndex: Int)

@Composable
fun GameScreen(vm: GameViewModel, onMenu: () -> Unit) {
    val state by vm.state.collectAsStateValue()
    val canUndo by vm.canUndo.collectAsStateValue()
    val coins by vm.coins.collectAsStateValue()
    val reward by vm.lastReward.collectAsStateValue()

    // Ширина карты подбирается под экран и число колонок — поле заполняется целиком.
    val screenW = LocalConfiguration.current.screenWidthDp
    val cols = state.tableau.size.coerceAtLeast(1)
    val cardW: Dp = (((screenW - 16 - (cols - 1) * 6) / cols).coerceIn(44, 88)).dp
    val foundationW: Dp = cardW.coerceAtMost(66.dp)
    val faceUpPeek = (cardHeight(cardW).value * 0.34f)
    val faceDownPeek = (cardHeight(cardW).value * 0.18f)

    val pileBounds = remember { mutableStateMapOf<Int, Rect>() }
    val cardBounds = remember { mutableStateMapOf<Long, Rect>() }
    var drag by remember { mutableStateOf<DragState?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun cardKey(pile: Int, index: Int): Long = pile * 10_000L + index
    fun pileAt(point: Offset): Int? = pileBounds.entries.firstOrNull { it.value.contains(point) }?.key
    fun clearSelection() { selected = null }
    fun tryMove(from: Int, index: Int, to: Int) { vm.onMove(from, index, to); clearSelection() }

    val legalTargets: Set<Int> = remember(state, selected) {
        val sel = selected ?: return@remember emptySet()
        computeLegalTargets(state, sel.first, sel.second)
    }

    val handlers = remember(state) {
        DragHandlers(
            onStart = { d -> drag = d; dragOffset = Offset.Zero; clearSelection() },
            onDrag = { dragOffset += it },
            onEnd = {
                val d = drag
                if (d != null) {
                    val head = cardBounds[cardKey(d.fromPile, d.startIndex)]
                    if (head != null) pileAt(head.center + dragOffset)?.let { vm.onMove(d.fromPile, d.startIndex, it) }
                }
                drag = null; dragOffset = Offset.Zero
            },
            onCancel = { drag = null; dragOffset = Offset.Zero }
        )
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // Шапка.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill("🪙 $coins", Gold, dark = true)
            Spacer(Modifier.weight(1f))
            Text("Уровень ${state.level}", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.weight(1f))
            Pill(collectedText(state), Color(0x33FFFFFF))
        }
        Text(
            "Ходы ${state.moves} · ${formatTime(state.elapsedSec)}",
            fontSize = 12.sp, color = Color(0xCCEAF7EE),
            modifier = Modifier.padding(start = 14.dp, bottom = 2.dp)
        )

        // Фундаменты + колода/сброс.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top
        ) {
            state.foundations.forEachIndexed { fi, foundation ->
                val pileIndex = GameState.FOUNDATION_START + fi
                val collected = foundation.group != null && foundation.group in state.collected
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { pileBounds[pileIndex] = it.boundsInRoot() }
                        .clickable { selected?.let { tryMove(it.first, it.second, pileIndex) } }
                ) {
                    when {
                        collected -> CollectedSlot(foundation.group!!, w = foundationW)
                        foundation.cards.isNotEmpty() -> FoundationProgress(
                            base = foundation.cards.first(), count = foundation.cards.size,
                            w = foundationW, highlighted = legalTargets.contains(pileIndex)
                        )
                        else -> EmptySlot(w = foundationW, highlighted = legalTargets.contains(pileIndex), crown = true)
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // Колода.
            Box(
                modifier = Modifier
                    .onGloballyPositioned { pileBounds[GameState.STOCK_INDEX] = it.boundsInRoot() }
                    .clickable { vm.onStockClick(); clearSelection() }
            ) {
                if (state.stock.cards.isNotEmpty()) CardBack(w = foundationW) else EmptySlot(w = foundationW)
            }
            // Сброс.
            Box(modifier = Modifier.onGloballyPositioned { pileBounds[GameState.WASTE_INDEX] = it.boundsInRoot() }) {
                val wasteTop = state.waste.cards.lastOrNull()
                val wasteIndex = state.waste.cards.lastIndex
                if (wasteTop != null) {
                    DraggableCard(
                        card = wasteTop, pileIndex = GameState.WASTE_INDEX, cardIndex = wasteIndex, w = foundationW,
                        selected = selected == (GameState.WASTE_INDEX to wasteIndex), highlighted = false,
                        drag = drag, dragOffset = dragOffset, cardBounds = cardBounds,
                        onTap = { handleTap(state, GameState.WASTE_INDEX, wasteIndex, selected, { selected = it }, ::tryMove) },
                        onDoubleTap = { vm.onAutoMove(GameState.WASTE_INDEX, wasteIndex); clearSelection() },
                        handlers = handlers, cardKey = ::cardKey
                    )
                } else EmptySlot(w = foundationW)
            }
        }

        // Поле — заполняет оставшееся место.
        Box(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.tableau.forEachIndexed { ti, pile ->
                    val pileIndex = state.tableauStart + ti
                    TableauColumn(
                        pileIndex = pileIndex, pile = pile, w = cardW,
                        faceUpPeek = faceUpPeek, faceDownPeek = faceDownPeek,
                        selected = selected, highlighted = legalTargets.contains(pileIndex),
                        drag = drag, dragOffset = dragOffset, cardBounds = cardBounds, pileBounds = pileBounds,
                        onTapCard = { idx -> handleTap(state, pileIndex, idx, selected, { selected = it }, ::tryMove) },
                        onDoubleTapCard = { idx -> vm.onAutoMove(pileIndex, idx); clearSelection() },
                        onTapEmpty = { selected?.let { tryMove(it.first, it.second, pileIndex) } },
                        handlers = handlers, cardKey = ::cardKey
                    )
                }
            }
        }

        // Нижняя панель действий.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionButton("↶ Отменить", enabled = canUndo) { vm.undo(); clearSelection() }
            ActionButton("⟲ Заново", enabled = true) { vm.newGame(); clearSelection() }
            Spacer(Modifier.weight(1f))
            ActionButton("☰ Меню", enabled = true) { onMenu() }
        }
    }

    if (state.isWon) {
        WinDialog(
            level = state.level, moves = state.moves, elapsedSec = state.elapsedSec,
            reward = reward, totalCoins = coins, onNext = { vm.newGame() }, onMenu = onMenu
        )
    }
}

private fun collectedText(state: GameState): String = "${state.collected.size}/${state.groups.size}"

// ----- Компоненты HUD ------------------------------------------------------

@Composable
private fun Pill(text: String, bg: Color, dark: Boolean = false) {
    Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = if (dark) Color(0xFF3A2D10) else Color.White)
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.16f * alpha + 0.04f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = alpha))
    }
}

// ----- Колонка tableau -----------------------------------------------------

@Composable
private fun TableauColumn(
    pileIndex: Int, pile: Pile, w: Dp, faceUpPeek: Float, faceDownPeek: Float,
    selected: Pair<Int, Int>?, highlighted: Boolean, drag: DragState?, dragOffset: Offset,
    cardBounds: SnapshotStateMap<Long, Rect>, pileBounds: SnapshotStateMap<Int, Rect>,
    onTapCard: (Int) -> Unit, onDoubleTapCard: (Int) -> Unit, onTapEmpty: () -> Unit,
    handlers: DragHandlers, cardKey: (Int, Int) -> Long
) {
    var totalPeek = 0f
    pile.cards.forEachIndexed { i, c ->
        if (i != pile.cards.lastIndex) totalPeek += if (c.faceUp) faceUpPeek else faceDownPeek
    }
    val columnHeightDp = (totalPeek + cardHeight(w).value + 8f).dp

    Box(
        modifier = Modifier
            .width(w).height(columnHeightDp)
            .onGloballyPositioned { pileBounds[pileIndex] = it.boundsInRoot() }
            .clickable(enabled = pile.cards.isEmpty()) { onTapEmpty() }
    ) {
        if (pile.cards.isEmpty()) {
            EmptySlot(w = w, highlighted = highlighted)
        } else {
            var y = 0f
            pile.cards.forEachIndexed { ci, card ->
                val yOffset = y
                Box(modifier = Modifier.offset(y = yOffset.dp)) {
                    if (!card.faceUp) {
                        CardBack(w = w, modifier = Modifier.onGloballyPositioned { cardBounds[cardKey(pileIndex, ci)] = it.boundsInRoot() })
                    } else {
                        DraggableCard(
                            card = card, pileIndex = pileIndex, cardIndex = ci, w = w,
                            selected = selected == (pileIndex to ci),
                            highlighted = highlighted && ci == pile.cards.lastIndex,
                            drag = drag, dragOffset = dragOffset, cardBounds = cardBounds,
                            onTap = { onTapCard(ci) }, onDoubleTap = { onDoubleTapCard(ci) },
                            handlers = handlers, cardKey = cardKey
                        )
                    }
                }
                y += if (card.faceUp) faceUpPeek else faceDownPeek
            }
        }
    }
}

// ----- Перетаскиваемая карта -----------------------------------------------

private class DragHandlers(
    val onStart: (DragState) -> Unit, val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit, val onCancel: () -> Unit
)

@Composable
private fun DraggableCard(
    card: Card, pileIndex: Int, cardIndex: Int, w: Dp, selected: Boolean, highlighted: Boolean,
    drag: DragState?, dragOffset: Offset, cardBounds: SnapshotStateMap<Long, Rect>,
    onTap: () -> Unit, onDoubleTap: () -> Unit, handlers: DragHandlers, cardKey: (Int, Int) -> Long
) {
    val isDragging = drag != null && drag.fromPile == pileIndex && cardIndex >= drag.startIndex
    val key = cardKey(pileIndex, cardIndex)

    val mod = Modifier
        .onGloballyPositioned { cardBounds[key] = it.boundsInRoot() }
        .graphicsLayer { if (isDragging) { translationX = dragOffset.x; translationY = dragOffset.y } }
        .zIndex(if (isDragging) 100f else cardIndex.toFloat())
        .scale(if (selected) 1.06f else 1f)
        .pointerInput(pileIndex, cardIndex) { detectTapGesturesCompat(onTap = onTap, onDoubleTap = onDoubleTap) }
        .pointerInput(pileIndex, cardIndex) {
            detectDragGesturesCompat(
                onStart = { handlers.onStart(DragState(pileIndex, cardIndex)) },
                onDrag = { handlers.onDrag(it) }, onEnd = { handlers.onEnd() }, onCancel = { handlers.onCancel() }
            )
        }

    CardFace(card = card, w = w, modifier = mod, selected = selected, highlighted = highlighted)
}

// ----- Диалог победы -------------------------------------------------------

@Composable
private fun WinDialog(
    level: Int, moves: Int, elapsedSec: Int, reward: Int, totalCoins: Int,
    onNext: () -> Unit, onMenu: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "win")
    val scale by pulse.animateFloat(
        initialValue = 0.97f, targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse"
    )
    Box(modifier = Modifier.fillMaxSize().background(Color(0xCC07260F)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.scale(scale).clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🎉", fontSize = 46.sp)
            Text("Уровень $level пройден!", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1F8049))
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x33E9B23C)).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("🪙 +$reward фишек", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFB07A12))
            }
            Spacer(Modifier.height(10.dp))
            Text("Ходы: $moves   Время: ${formatTime(elapsedSec)}", fontSize = 13.sp, color = Color(0xFF4A3A29))
            Text("Всего фишек: $totalCoins", fontSize = 13.sp, color = Color(0xFF4A3A29))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Уровень ${level + 1} ▶", Brush.horizontalGradient(listOf(Color(0xFF2E9E63), Color(0xFF1F8049))), onNext)
                BigButton("В меню", Brush.horizontalGradient(listOf(Color(0xFF8A7A5E), Color(0xFF6E5F45))), onMenu)
            }
        }
    }
}

@Composable
private fun BigButton(text: String, bg: Brush, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable { onClick() }.padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// ----- Помощники -----------------------------------------------------------

private fun formatTime(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

private fun handleTap(
    state: GameState, pileIndex: Int, cardIndex: Int, selected: Pair<Int, Int>?,
    setSelected: (Pair<Int, Int>?) -> Unit, tryMove: (Int, Int, Int) -> Unit
) {
    if (selected == null) {
        if (isValidSource(state, pileIndex, cardIndex)) setSelected(pileIndex to cardIndex)
        return
    }
    if (selected.first == pileIndex) { setSelected(null); return }
    tryMove(selected.first, selected.second, pileIndex)
}

private fun isValidSource(state: GameState, pileIndex: Int, cardIndex: Int): Boolean {
    val pile = state.piles[pileIndex]
    val card = pile.cards.getOrNull(cardIndex) ?: return false
    if (!card.faceUp || cardIndex != pile.cards.lastIndex) return false
    return pile.type == PileType.WASTE || pile.type == PileType.TABLEAU
}

private fun computeLegalTargets(state: GameState, fromPile: Int, cardIndex: Int): Set<Int> {
    if (!isValidSource(state, fromPile, cardIndex)) return emptySet()
    val card = state.piles[fromPile].cards[cardIndex]
    val targets = mutableSetOf<Int>()
    state.foundations.forEachIndexed { i, f ->
        val idx = GameState.FOUNDATION_START + i
        if (f.group == null || f.group !in state.collected) {
            if (GameLogic.canPlaceOnFoundation(card, f)) targets += idx
        }
    }
    state.tableau.forEachIndexed { ti, t ->
        val idx = state.tableauStart + ti
        if (idx != fromPile && GameLogic.canPlaceOnTableau(card, t)) targets += idx
    }
    return targets
}
