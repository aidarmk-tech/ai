package com.lampplayer.tv.player

import android.animation.ValueAnimator
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * Single-line label that scrolls its text horizontally while [isSelected] (focused),
 * at a controllable speed — unlike the framework marquee whose speed is fixed.
 * Not selected: shows an end-ellipsis like a normal label.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : AppCompatTextView(context, attrs, defStyle) {

    private var animator: ValueAnimator? = null

    // Scroll speed in dp/sec and the pause at each end before reversing.
    private val speedPxPerSec = 110f * resources.displayMetrics.density
    private val edgePauseMs = 600L

    init {
        setSingleLine(true)
        setHorizontallyScrolling(true)
        ellipsize = TextUtils.TruncateAt.END
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        if (selected) post { start() } else stop()
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        if (isSelected) post { start() } else stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun overflowPx(): Int {
        val avail = width - paddingLeft - paddingRight
        if (avail <= 0) return 0
        val textW = paint.measureText(text?.toString() ?: "")
        return (textW - avail).toInt().coerceAtLeast(0)
    }

    private fun start() {
        stop()
        val over = overflowPx()
        if (over <= 0) { scrollX = 0; return }
        ellipsize = null            // render the full string so scrollX can reveal it
        val dur = (over / speedPxPerSec * 1000f).toLong().coerceAtLeast(500L)
        animator = ValueAnimator.ofInt(0, over).apply {
            duration = dur
            startDelay = edgePauseMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { scrollX = it.animatedValue as Int }
            start()
        }
    }

    private fun stop() {
        animator?.cancel(); animator = null
        scrollX = 0
        ellipsize = TextUtils.TruncateAt.END
    }
}
