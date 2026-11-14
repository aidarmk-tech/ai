package com.lampplayer.tv.center

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Набор элементов интерфейса LampMedia, управляемых пультом: текст, кнопки, панели и отступы.
 * Цвета и размеры берутся из текущей [CenterTheme], поэтому экраны не задают их сами.
 */
class CenterViews(private val context: Context, private val theme: () -> CenterTheme) {
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun sp(size: Int) = theme().textSize(size)

    fun bg(color: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(2), stroke)
    }

    fun text(value: String, size: Int = 16, color: Int = theme().ink) = TextView(context).apply {
        text = value; textSize = sp(size); setTextColor(color); includeFontPadding = false
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** Фон и цвет текста кнопки: фокус — рамка акцента, активное — подсвеченная панель. */
    fun paintButton(view: View, focus: Boolean) {
        val t = theme()
        view.background = bg(when { focus -> t.panelFocus; view.isActivated -> t.panelActive; else -> t.panel },
            if (focus) t.accent else Color.TRANSPARENT)
        (view as? TextView)?.setTextColor(if (focus || view.isActivated) t.ink else t.muted)
    }

    /** Фон карточки (постер, история, серия) с рамкой фокуса. */
    fun paintCard(view: View, focus: Boolean) {
        val t = theme()
        view.background = bg(if (focus) t.cardFocus else t.panel, if (focus) t.accent else Color.TRANSPARENT)
    }

    fun button(label: String, action: () -> Unit) = text(label, 15).apply {
        id = View.generateViewId(); tag = label; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(10), dp(15), dp(10)); isFocusable = true; isClickable = true
        background = bg(theme().panel)
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) }
        setOnClickListener { action() }
        setOnFocusChangeListener { v, focus -> paintButton(v, focus) }
    }

    fun iconButton(symbol: String, label: String, action: () -> Unit): TextView =
        button(label, action).apply {
            text = "$symbol  $label"; textSize = sp(14); contentDescription = label; tag = label
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(6) }
            setOnFocusChangeListener { view, focused -> paintButton(view, focused) }
        }

    fun space(parent: LinearLayout, n: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(n))) }

    companion object {
        /** Ищет элемент по tag в дереве — так экраны восстанавливают фокус после перерисовки. */
        fun findTag(view: View, key: String): View? {
            if (view.tag == key) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findTag(view.getChildAt(i), key)?.let { return it }
            return null
        }
    }
}
