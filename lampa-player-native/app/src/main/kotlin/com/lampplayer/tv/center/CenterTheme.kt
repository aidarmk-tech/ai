package com.lampplayer.tv.center

import android.graphics.Color

/**
 * Единая палитра и типографика LampMedia.
 * Все цвета интерфейса медиацентра берутся отсюда: новая тема — это новый экземпляр [CenterTheme].
 */
data class CenterTheme(
    val background: Int,
    val navigation: Int,
    val panel: Int,
    val panelActive: Int,
    val panelFocus: Int,
    val cardFocus: Int,
    val outline: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val placeholderInk: Int,
    val heroShade: Int,
    /** Множитель размера текста (настройка «Размер текста»). */
    val textScale: Float = 1f,
) {
    /** Размер шрифта в sp с нижней границей, читаемой с дивана, и пользовательским масштабом. */
    fun textSize(size: Int): Float = textSize(size, textScale)

    fun placeholderGradient(seed: Int) = intArrayOf(
        Color.rgb(30 + (seed * 7 and 31), 54, 79), Color.rgb(12, 23 + (seed * 3 and 15), 40))

    fun heroGradient() = intArrayOf(withAlpha(heroShade, 250), withAlpha(heroShade, 235), withAlpha(heroShade, 50))

    companion object {
        /** Минимальный размер текста интерфейса на ТВ, sp. Мельче с 2–3 м уже плохо читается. */
        const val MIN_TEXT_SP = 13

        val scales = linkedMapOf("normal" to 1f, "large" to 1.15f, "xlarge" to 1.3f)
        val scaleLabels = linkedMapOf("normal" to "Обычный", "large" to "Крупный", "xlarge" to "Очень крупный")
        val paletteLabels = linkedMapOf("default" to "Обычная", "contrast" to "Повышенный контраст")

        fun textSize(size: Int, scale: Float): Float = size.coerceAtLeast(MIN_TEXT_SP) * scale

        fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        val default = CenterTheme(
            background = Color.rgb(8, 13, 22),
            navigation = Color.rgb(13, 22, 36),
            panel = Color.rgb(20, 30, 45),
            panelActive = Color.rgb(25, 45, 66),
            panelFocus = Color.rgb(38, 65, 96),
            cardFocus = Color.rgb(34, 54, 79),
            outline = Color.rgb(35, 58, 82),
            ink = Color.rgb(242, 246, 252),
            muted = Color.rgb(153, 172, 197),
            accent = Color.rgb(136, 196, 255),
            placeholderInk = Color.rgb(77, 111, 144),
            heroShade = Color.rgb(15, 25, 41),
        )

        /** Чёрный фон, светлее второстепенный текст и яркая рамка фокуса — для солнечной комнаты и слабого зрения. */
        val contrast = default.copy(
            background = Color.BLACK,
            navigation = Color.rgb(8, 8, 10),
            panel = Color.rgb(22, 24, 28),
            panelActive = Color.rgb(40, 52, 70),
            panelFocus = Color.rgb(0, 70, 140),
            cardFocus = Color.rgb(0, 60, 120),
            outline = Color.rgb(120, 130, 150),
            muted = Color.rgb(205, 214, 228),
            accent = Color.rgb(255, 214, 10),
            placeholderInk = Color.rgb(170, 180, 200),
            heroShade = Color.BLACK,
        )

        fun of(palette: String, textScale: String) =
            (if (palette == "contrast") contrast else default).copy(textScale = scales[textScale] ?: 1f)
    }
}
