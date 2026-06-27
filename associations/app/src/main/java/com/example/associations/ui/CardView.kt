package com.example.associations.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.associations.model.Card
import com.example.associations.model.GROUP_SIZE
import com.example.associations.model.Group

val CARD_WIDTH: Dp = 62.dp
val CARD_HEIGHT: Dp = 88.dp
private val CARD_SHAPE = RoundedCornerShape(14.dp)

fun groupColor(group: Group): Color = Color(group.color)

/** Лицевая сторона карты — основа или предмет. */
@Composable
fun CardFace(
    card: Card,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    highlighted: Boolean = false
) {
    val accent = groupColor(card.group)
    val ring = when {
        selected -> Color(0xFFFFD24D)
        highlighted -> Color(0xFF34D399)
        card.isBase -> Color(0xFFFFD24D)
        else -> accent.copy(alpha = 0.55f)
    }
    val ringWidth = if (selected || highlighted) 3.dp else if (card.isBase) 2.5.dp else 1.5.dp

    if (card.isBase) {
        // Премиальная карта-основа: насыщенный градиент группы + золотая рамка.
        Box(
            modifier = modifier
                .size(CARD_WIDTH, CARD_HEIGHT)
                .shadow(8.dp, CARD_SHAPE)
                .clip(CARD_SHAPE)
                .background(Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.18f), lerp(accent, Color.Black, 0.28f))))
                .border(ringWidth, ring, CARD_SHAPE)
        ) {
            Text("★", color = Color(0xFFFFE08A), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 3.dp))
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(card.icon, fontSize = 30.sp)
                Text(
                    card.title.uppercase(),
                    color = Color.White,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                )
            }
        }
        return
    }

    // Карта-предмет: светлая, с цветной «шапкой» группы.
    Box(
        modifier = modifier
            .size(CARD_WIDTH, CARD_HEIGHT)
            .shadow(5.dp, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(Brush.verticalGradient(listOf(Color.White, lerp(accent, Color.White, 0.86f))))
            .border(ringWidth, ring, CARD_SHAPE)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(accent)
        )
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(top = 6.dp, start = 3.dp, end = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(card.icon, fontSize = 26.sp)
            Text(
                card.title,
                color = Color(0xFF1C1840),
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
            )
        }
    }
}

/** Рубашка карты. */
@Composable
fun CardBack(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(CARD_WIDTH, CARD_HEIGHT)
            .shadow(4.dp, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(Brush.linearGradient(listOf(Color(0xFF3B2E78), Color(0xFF221A52))))
            .border(1.dp, Color(0x33FFFFFF), CARD_SHAPE),
        contentAlignment = Alignment.Center
    ) {
        Text("✦", color = Color(0x66B7A8FF), fontSize = 24.sp)
    }
}

/** Пустой слот (фундамент без основы или пустая колонка). */
@Composable
fun EmptySlot(modifier: Modifier = Modifier, label: String? = null, highlighted: Boolean = false) {
    Box(
        modifier = modifier
            .size(CARD_WIDTH, CARD_HEIGHT)
            .clip(CARD_SHAPE)
            .background(Color(0x14FFFFFF))
            .border(if (highlighted) 2.5.dp else 1.5.dp,
                if (highlighted) Color(0xFF34D399) else Color(0x33FFFFFF), CARD_SHAPE),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) Text(label, fontSize = 20.sp, color = Color(0x88FFFFFF))
    }
}

/** Фундамент с положенной основой + индикатор прогресса (k/размер). */
@Composable
fun FoundationProgress(base: Card, count: Int, highlighted: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(CARD_WIDTH, CARD_HEIGHT)) {
        CardFace(base, highlighted = highlighted)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xE6121029))
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text("$count/$GROUP_SIZE", color = Color(0xFFFFD24D), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Слот собранной группы — «ушла с поля». */
@Composable
fun CollectedSlot(group: Group, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(CARD_WIDTH, CARD_HEIGHT)
            .clip(CARD_SHAPE)
            .background(Brush.verticalGradient(listOf(lerp(groupColor(group), Color.White, 0.1f), lerp(groupColor(group), Color.Black, 0.3f))))
            .border(2.dp, Color(0xFF34D399), CARD_SHAPE),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(group.icon, fontSize = 22.sp)
            Text("✓", color = Color(0xFFB9FBDC), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
