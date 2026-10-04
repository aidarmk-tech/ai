package com.lampplayer.tv.player

import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Keeps series navigation independent of the length of the synopsis or actor carousel. */
internal class InfoOverlayNavigation(
    private val episodes: RecyclerView,
    private val metadata: ScrollView,
    private val cast: RecyclerView,
    private val close: () -> Unit,
) {
    private var rememberedIndex = 0
    private val hasEpisodes: Boolean get() = episodes.visibility == View.VISIBLE && (episodes.adapter?.itemCount ?: 0) > 0

    fun open(currentIndex: Int) {
        metadata.scrollTo(0, 0)
        if (hasEpisodes) focusEpisode(currentIndex) else metadata.requestFocus()
    }

    fun focusEpisode(index: Int = rememberedIndex) {
        if (!hasEpisodes) return
        rememberedIndex = index.coerceIn(0, (episodes.adapter?.itemCount ?: 1) - 1)
        val target = rememberedIndex
        (episodes.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, 0)
        episodes.post {
            if (episodes.visibility != View.VISIBLE) return@post
            (episodes.findViewHolderForAdapterPosition(target)?.itemView ?: episodes).requestFocus()
        }
    }

    private fun focusedIndex(list: RecyclerView): Int = list.findFocus()?.let {
        list.findContainingViewHolder(it)?.bindingAdapterPosition
    }?.takeIf { it >= 0 } ?: -1

    fun focusedEpisodeIndex(): Int = focusedIndex(episodes).takeIf { it >= 0 } ?: rememberedIndex

    /** Called before the view tree dispatches the key, so horizontal jumps take one press. */
    fun onKeyDown(key: Int): Boolean {
        if (episodes.hasFocus()) {
            val index = focusedIndex(episodes)
            if (index >= 0) rememberedIndex = index
            return when (key) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { metadata.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                KeyEvent.KEYCODE_DPAD_UP -> if (index <= 0) { close(); true } else false
                KeyEvent.KEYCODE_DPAD_DOWN -> index == (episodes.adapter?.itemCount ?: 0) - 1
                KeyEvent.KEYCODE_BACK -> { close(); true }
                else -> false
            }
        }
        if (metadata.hasFocus()) {
            return when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // Actors keep normal horizontal navigation; the first actor exits to series.
                    if (cast.hasFocus() && focusedIndex(cast) > 0) false
                    else if (hasEpisodes) { focusEpisode(); true } else true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (hasEpisodes) focusEpisode() else close()
                    true
                }
                else -> false
            }
        }
        if (key == KeyEvent.KEYCODE_BACK) { close(); return true }
        return false
    }
}
