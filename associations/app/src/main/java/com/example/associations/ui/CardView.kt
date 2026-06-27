package com.example.associations.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.associations.model.Card
import com.example.associations.model.Category
import com.example.associations.ui.theme.CategoryColors

val CARD_WIDTH: Dp = 56.dp
val CARD_HEIGHT: Dp = 84.dp

fun categoryColor(category: Category): Color = when (category) {
    Category.CITY -> CategoryColors.city
    Category.FURNITURE -> CategoryColors.furniture
    Category.ANIMAL -> CategoryColors.animal
    Category.FOOD -> CategoryColors.food
}

/** Рисует одну карту (лицом или рубашкой). */
@Composable
fun CardView(
    card: Card,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    highlighted: Boolean = false
) {
    val shape = RoundedCornerShape(8.dp)
    if (!card.faceUp) {
        Box(
            modifier = modifier
                .size(CARD_WIDTH, CARD_HEIGHT)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF3949AB), Color(0xFF1A237E))
                    )
                )
                .border(1.dp, Color(0x55FFFFFF), shape),
            contentAlignment = Alignment.Center
        ) {
            Text("✦", color = Color(0x66FFFFFF), fontSize = 22.sp)
        }
        return
    }

    val accent = categoryColor(card.category)
    val borderColor = when {
        selected -> Color(0xFFFFC107)
        highlighted -> Color(0xFF00C853)
        else -> accent.copy(alpha = 0.6f)
    }
    val borderWidth = if (selected || highlighted) 3.dp else 1.5.dp

    Box(
        modifier = modifier
            .size(CARD_WIDTH, CARD_HEIGHT)
            .clip(shape)
            .background(Color.White)
            .border(borderWidth, borderColor, shape)
    ) {
        // Ранг в углу.
        Text(
            text = card.rank.toString(),
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 5.dp, top = 2.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PaddingValues(top = 16.dp, start = 3.dp, end = 3.dp, bottom = 3.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(card.category.icon, fontSize = 20.sp)
            Text(
                text = card.title,
                color = Color(0xFF222222),
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
        }
    }
}

/** Пустой слот (основание / пустая колонка). */
@Composable
fun EmptySlot(
    modifier: Modifier = Modifier,
    label: String? = null,
    highlighted: Boolean = false
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(CARD_WIDTH, CARD_HEIGHT)
            .clip(shape)
            .background(Color(0x14000000))
            .border(
                1.5.dp,
                if (highlighted) Color(0xFF00C853) else Color(0x33FFFFFF),
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) {
            Text(label, fontSize = 18.sp, color = Color(0x88FFFFFF), textAlign = TextAlign.Center)
        }
    }
}
