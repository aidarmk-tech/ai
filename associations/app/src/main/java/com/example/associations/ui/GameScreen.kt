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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
private const val FACE_DOWN_PEEK = 15

private data class DragState(val fromPile: Int, val startIndex: Int)

@Composable
fun GameScreen(vm: GameViewModel, onMenu: () -> Unit) {
    val state by vm.state.collectAsStateValue()
    val canUndo by vm.canUndo.collectAsStateValue()
    val coins by vm.coins.collectAsStateValue()
    val reward by vm.lastReward.collectAsStateValue()

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

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) {
        HudBar(
            level = state.level,
            collected = state.collected.size,
            total = state.groups.size,
            coins = coins,
            moves = state.moves,
            elapsedSec = state.elapsedSec,
            canUndo = canUndo,
            onUndo = { vm.undo(); clearSelection() },
            onNew = { vm.newGame(); clearSelection() },
            onMenu = onMenu
        )

        // Фундаменты.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                        collected -> CollectedSlot(foundation.group!!)
                        foundation.cards.isNotEmpty() -> FoundationProgress(
                            base = foundation.cards.first(),
                            count = foundation.cards.size,
                            highlighted = legalTargets.contains(pileIndex)
                        )
                        else -> EmptySlot(label = "◇", highlighted = legalTargets.contains(pileIndex))
                    }
                }
            }
        }

        // Колода и сброс.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .onGloballyPositioned { pileBounds[GameState.STOCK_INDEX] = it.boundsInRoot() }
                    .clickable { vm.onStockClick(); clearSelection() }
            ) {
                if (state.stock.cards.isNotEmpty()) CardBack() else EmptySlot(label = "↻")
            }
            Box(modifier = Modifier.onGloballyPositioned { pileBounds[GameState.WASTE_INDEX] = it.boundsInRoot() }) {
                val wasteTop = state.waste.cards.lastOrNull()
                val wasteIndex = state.waste.cards.lastIndex
                if (wasteTop != null) {
                    DraggableCard(
                        card = wasteTop, pileIndex = GameState.WASTE_INDEX, cardIndex = wasteIndex,
                        selected = selected == (GameState.WASTE_INDEX to wasteIndex), highlighted = false,
                        drag = drag, dragOffset = dragOffset, cardBounds = cardBounds,
                        onTap = { handleTap(state, GameState.WASTE_INDEX, wasteIndex, selected, { selected = it }, ::tryMove) },
                        onDoubleTap = { vm.onAutoMove(GameState.WASTE_INDEX, wasteIndex); clearSelection() },
                        handlers = handlers, cardKey = ::cardKey
                    )
                } else EmptySlot()
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.align(Alignment.CenterVertically)) {
                Text(
                    if (selected != null) "Выберите, куда положить" else "Тяните карту или коснитесь дважды",
                    fontSize = 11.sp, color = Color(0x99FFFFFF)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Поле (tableau).
        Box(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                state.tableau.forEachIndexed { ti, pile ->
                    val pileIndex = state.tableauStart + ti
                    TableauColumn(
                        pileIndex = pileIndex, pile = pile, selected = selected,
                        highlighted = legalTargets.contains(pileIndex), drag = drag, dragOffset = dragOffset,
                        cardBounds = cardBounds, pileBounds = pileBounds,
                        onTapCard = { idx -> handleTap(state, pileIndex, idx, selected, { selected = it }, ::tryMove) },
                        onDoubleTapCard = { idx -> vm.onAutoMove(pileIndex, idx); clearSelection() },
                        onTapEmpty = { selected?.let { tryMove(it.first, it.second, pileIndex) } },
                        handlers = handlers, cardKey = ::cardKey
                    )
                }
            }
        }
    }

    if (state.isWon) {
        WinDialog(
            level = state.level, moves = state.moves, elapsedSec = state.elapsedSec,
            reward = reward, totalCoins = coins,
            onNext = { vm.newGame() }, onMenu = onMenu
        )
    }
}

// ----- HUD -----------------------------------------------------------------

@Composable
private fun HudBar(
    level: Int, collected: Int, total: Int, coins: Int, moves: Int, elapsedSec: Int,
    canUndo: Boolean, onUndo: () -> Unit, onNew: () -> Unit, onMenu: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Pill("🏆 Ур. $level", MaterialTheme.colorScheme.primary)
            Pill("🪙 $coins", MaterialTheme.colorScheme.secondary, dark = true)
            Spacer(Modifier.weight(1f))
            Pill("$collected/$total", Color(0x3334D399), textColor = Color(0xFF9CF6CE))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Ходы $moves · ${formatTime(elapsedSec)}", fontSize = 12.sp, color = Color(0xCCFFFFFF))
            Spacer(Modifier.weight(1f))
            HudButton("↶", enabled = canUndo, onClick = onUndo)
            HudButton("⟲", enabled = true, onClick = onNew)
            HudButton("☰", enabled = true, onClick = onMenu)
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, textColor: Color = Color.White, dark: Boolean = false) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = if (dark) Color(0xFF1A1033) else textColor)
    }
}

@Composable
private fun HudButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.35f
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22FFFFFF))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(label, fontSize = 16.sp, color = Color.White.copy(alpha = alpha))
    }
}

// ----- Колонка tableau -----------------------------------------------------

@Composable
private fun TableauColumn(
    pileIndex: Int, pile: Pile, selected: Pair<Int, Int>?, highlighted: Boolean,
    drag: DragState?, dragOffset: Offset, cardBounds: SnapshotStateMap<Long, Rect>,
    pileBounds: SnapshotStateMap<Int, Rect>, onTapCard: (Int) -> Unit, onDoubleTapCard: (Int) -> Unit,
    onTapEmpty: () -> Unit, handlers: DragHandlers, cardKey: (Int, Int) -> Long
) {
    var totalPeek = 0
    pile.cards.forEachIndexed { i, c ->
        if (i != pile.cards.lastIndex) totalPeek += if (c.faceUp) FACE_UP_PEEK else FACE_DOWN_PEEK
    }
    val columnHeightDp = (totalPeek + 88 + 8).dp

    Box(
        modifier = Modifier
            .width(CARD_WIDTH).height(columnHeightDp)
            .onGloballyPositioned { pileBounds[pileIndex] = it.boundsInRoot() }
            .clickable(enabled = pile.cards.isEmpty()) { onTapEmpty() }
    ) {
        if (pile.cards.isEmpty()) {
            EmptySlot(highlighted = highlighted)
        } else {
            var yPx = 0
            pile.cards.forEachIndexed { ci, card ->
                val yOffset = yPx
                Box(modifier = Modifier.offset(y = yOffset.dp)) {
                    if (!card.faceUp) {
                        CardBack(modifier = Modifier.onGloballyPositioned { cardBounds[cardKey(pileIndex, ci)] = it.boundsInRoot() })
                    } else {
                        DraggableCard(
                            card = card, pileIndex = pileIndex, cardIndex = ci,
                            selected = selected == (pileIndex to ci),
                            highlighted = highlighted && ci == pile.cards.lastIndex,
                            drag = drag, dragOffset = dragOffset, cardBounds = cardBounds,
                            onTap = { onTapCard(ci) }, onDoubleTap = { onDoubleTapCard(ci) },
                            handlers = handlers, cardKey = cardKey
                        )
                    }
                }
                yPx += if (card.faceUp) FACE_UP_PEEK else FACE_DOWN_PEEK
            }
        }
    }
}

// ----- Перетаскиваемая карта -----------------------------------------------

private class DragHandlers(
    val onStart: (DragState) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit
)

@Composable
private fun DraggableCard(
    card: Card, pileIndex: Int, cardIndex: Int, selected: Boolean, highlighted: Boolean,
    drag: DragState?, dragOffset: Offset, cardBounds: SnapshotStateMap<Long, Rect>,
    onTap: () -> Unit, onDoubleTap: () -> Unit, handlers: DragHandlers, cardKey: (Int, Int) -> Long
) {
    val isDragging = drag != null && drag.fromPile == pileIndex && cardIndex >= drag.startIndex
    val key = cardKey(pileIndex, cardIndex)

    val mod = Modifier
        .onGloballyPositioned { cardBounds[key] = it.boundsInRoot() }
        .graphicsLayer {
            if (isDragging) { translationX = dragOffset.x; translationY = dragOffset.y }
        }
        .zIndex(if (isDragging) 100f else cardIndex.toFloat())
        .scale(if (selected) 1.06f else 1f)
        .pointerInput(pileIndex, cardIndex) {
            detectTapGesturesCompat(onTap = onTap, onDoubleTap = onDoubleTap)
        }
        .pointerInput(pileIndex, cardIndex) {
            detectDragGesturesCompat(
                onStart = { handlers.onStart(DragState(pileIndex, cardIndex)) },
                onDrag = { handlers.onDrag(it) },
                onEnd = { handlers.onEnd() },
                onCancel = { handlers.onCancel() }
            )
        }

    CardFace(card = card, modifier = mod, selected = selected, highlighted = highlighted)
}

// ----- Диалог победы -------------------------------------------------------

@Composable
private fun WinDialog(
    level: Int, moves: Int, elapsedSec: Int, reward: Int, totalCoins: Int,
    onNext: () -> Unit, onMenu: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "win")
    val scale by pulse.animateFloat(
        initialValue = 0.96f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse"
    )
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xE6090714)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .scale(scale)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🎉", fontSize = 44.sp)
            Text("Уровень $level пройден!", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(Color(0x33FFC94D)).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("🪙 +$reward фишек", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(10.dp))
            Text("Ходы: $moves   Время: ${formatTime(elapsedSec)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("Всего фишек: $totalCoins", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Уровень ${level + 1} ▶", MaterialTheme.colorScheme.primary, onNext)
                BigButton("В меню", Color(0x33FFFFFF), onMenu)
            }
        }
    }
}

@Composable
private fun BigButton(text: String, bg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 11.dp)
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
