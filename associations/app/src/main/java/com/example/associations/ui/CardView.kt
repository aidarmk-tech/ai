package com.example.associations.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.associations.model.Card
import com.example.associations.model.GROUP_SIZE
import com.example.associations.model.Group
import com.example.associations.ui.theme.Gold

val DEFAULT_CARD_WIDTH: Dp = 72.dp
fun cardHeight(w: Dp): Dp = w * 1.4f

private val Cream = Color(0xFFFBF7EC)
private val Brown = Color(0xFF4A3A29)
private val GoldBorder = Color(0xFFE3A92E)
private val GreenSel = Color(0xFF22A85B)

fun groupColor(group: Group): Color = Color(group.color)

private fun shapeFor(w: Dp) = RoundedCornerShape((w.value * 0.18f).dp)

/** Лицевая сторона: основа (с короной) или предмет (крупное эмодзи). */
@Composable
fun CardFace(
    card: Card,
    w: Dp = DEFAULT_CARD_WIDTH,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    highlighted: Boolean = false
) {
    val h = cardHeight(w)
    val shape = shapeFor(w)
    val accent = groupColor(card.group)
    val border = when {
        selected -> GreenSel
        highlighted -> GreenSel
        card.isBase -> GoldBorder
        else -> accent.copy(alpha = 0.5f)
    }
    val borderW = if (selected || highlighted) (w.value * 0.05f).dp else if (card.isBase) (w.value * 0.04f).dp else (w.value * 0.025f).dp

    Box(
        modifier = modifier
            .size(w, h)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(Cream)
            .border(borderW, border, shape)
    ) {
        if (card.isBase) {
            Text("♛", color = GoldBorder, fontSize = (w.value * 0.2f).sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 5.dp, top = 3.dp))
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(card.icon, fontSize = (w.value * 0.34f).sp)
                Text(
                    card.title,
                    color = Brown,
                    fontSize = (w.value * 0.2f).sp,
                    lineHeight = (w.value * 0.22f).sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(card.icon, fontSize = (w.value * 0.46f).sp)
                Text(
                    card.title,
                    color = Brown,
                    fontSize = (w.value * 0.16f).sp,
                    lineHeight = (w.value * 0.18f).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
                )
            }
        }
    }
}

/** Рубашка карты. */
@Composable
fun CardBack(w: Dp = DEFAULT_CARD_WIDTH, modifier: Modifier = Modifier) {
    val h = cardHeight(w)
    val shape = shapeFor(w)
    Box(
        modifier = modifier
            .size(w, h)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFFC0473B), Color(0xFF9A2F26))))
            .border((w.value * 0.03f).dp, Color(0x55FFFFFF), shape),
        contentAlignment = Alignment.Center
    ) {
        Text("♛", color = Color(0xCCF3C969), fontSize = (w.value * 0.34f).sp)
    }
}

/** Пустой слот — фундамент без основы или пустая колонка. */
@Composable
fun EmptySlot(w: Dp = DEFAULT_CARD_WIDTH, modifier: Modifier = Modifier, highlighted: Boolean = false, crown: Boolean = false) {
    val h = cardHeight(w)
    val shape = shapeFor(w)
    Box(
        modifier = modifier
            .size(w, h)
            .clip(shape)
            .background(Color(0x1A0E3D24))
            .border(if (highlighted) (w.value * 0.05f).dp else (w.value * 0.025f).dp,
                if (highlighted) GreenSel else Color(0x33FFFFFF), shape),
        contentAlignment = Alignment.Center
    ) {
        Text(if (crown) "♛" else "", color = Color(0x44FFFFFF), fontSize = (w.value * 0.34f).sp)
    }
}

/** Фундамент с основой + прогресс k/размер. */
@Composable
fun FoundationProgress(base: Card, count: Int, w: Dp = DEFAULT_CARD_WIDTH, highlighted: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(w, cardHeight(w))) {
        CardFace(base, w = w, highlighted = highlighted)
        Box(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp)
                .clip(RoundedCornerShape(7.dp)).background(Color(0xF20E3D24)).padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text("$count/$GROUP_SIZE", color = Gold, fontSize = (w.value * 0.16f).sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** Слот собранной группы — «ушла с поля» (✓). */
@Composable
fun CollectedSlot(group: Group, w: Dp = DEFAULT_CARD_WIDTH, modifier: Modifier = Modifier) {
    val shape = shapeFor(w)
    Box(
        modifier = modifier.size(w, cardHeight(w)).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2BB877), Color(0xFF18804F))))
            .border((w.value * 0.04f).dp, Color(0xFF7BE3AC), shape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(group.icon, fontSize = (w.value * 0.34f).sp)
            Text("✓", color = Color.White, fontSize = (w.value * 0.3f).sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
