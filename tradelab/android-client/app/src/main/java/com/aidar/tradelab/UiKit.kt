package com.aidar.tradelab

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    const val BG = 0xFF0E1116.toInt()
    const val CARD = 0xFF171C24.toInt()
    const val CARD_ALT = 0xFF1D2430.toInt()
    const val TEXT = 0xFFE8EDF4.toInt()
    const val MUTED = 0xFF8B96A5.toInt()
    const val ACCENT = 0xFF4FA3FF.toInt()
    const val GREEN = 0xFF39C98E.toInt()
    const val RED = 0xFFFF6B6B.toInt()
    const val AMBER = 0xFFFFC24B.toInt()

    fun dp(c: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    fun card(context: Context): LinearLayout {
        val c = context
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        val bg = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(c, 14).toFloat()
        }
        box.background = bg
        return box
    }

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Int = 15,
        color: Int = TEXT,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView {
        val t = TextView(context)
        t.text = value
        t.textSize = sizeSp.toFloat()
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        if (mono) t.typeface = Typeface.MONOSPACE
        return t
    }

    fun row(context: Context): LinearLayout {
        val r = LinearLayout(context)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        return r
    }

    fun column(context: Context): LinearLayout {
        val v = LinearLayout(context)
        v.orientation = LinearLayout.VERTICAL
        return v
    }

    fun spacer(context: Context, h: Int): View {
        val s = View(context)
        s.layoutParams = LinearLayout.LayoutParams(1, dp(context, h))
        return s
    }

    fun hspace(context: Context, w: Int): View {
        val s = View(context)
        s.layoutParams = LinearLayout.LayoutParams(dp(context, w), 1)
        return s
    }

    fun pill(context: Context, label: String, color: Int): TextView {
        val p = text(context, label, 12, color, bold = true)
        p.setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2))
        val bg = GradientDrawable().apply {
            setColor((color and 0x00FFFFFF) or 0x22000000)
            cornerRadius = dp(context, 10).toFloat()
        }
        p.background = bg
        return p
    }

    fun pnlColor(v: Double): Int = when {
        v.isNaN() -> MUTED
        v > 0.0001 -> GREEN
        v < -0.0001 -> RED
        else -> MUTED
    }

    fun signed(value: Double, digits: Int = 4, suffix: String = ""): String {
        if (value.isNaN()) return "—"
        val sign = if (value > 0) "+" else ""
        return "$sign${"%.${digits}f".format(value)}$suffix"
    }

    fun ageText(ms: Long, nowMs: Long): String {
        if (ms <= 0) return "никогда"
        val sec = (nowMs - ms) / 1000
        return when {
            sec < 90 -> "${sec}s назад"
            sec < 5400 -> "${sec / 60}м назад"
            sec < 172800 -> "${sec / 3600}ч назад"
            else -> "${sec / 86400}д назад"
        }
    }
}
