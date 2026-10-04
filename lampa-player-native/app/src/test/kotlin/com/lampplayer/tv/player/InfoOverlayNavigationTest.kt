package com.lampplayer.tv.player

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lampplayer.tv.R
import com.lampplayer.tv.databinding.ActivityPlayerBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

/** Real view-tree focus/key tests, without a stream, native decoder or network. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@UnstableApi
class InfoOverlayNavigationTest {
    private fun startHost(): ActivityController<Host> {
        // Direct Activity key dispatch bypasses ViewRoot's switch out of touch mode.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        return Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
    }

    class Host : Activity() {
        lateinit var b: ActivityPlayerBinding
        internal lateinit var navigation: InfoOverlayNavigation
        lateinit var episodes: InfoListAdapter<String>
        var selected = -1
        var closed = 0

        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_LampaPlayer_Player)
            super.onCreate(state)
            b = ActivityPlayerBinding.inflate(layoutInflater)
            setContentView(b.root)
            b.playerView.visibility = View.GONE
            b.vlcLayout.visibility = View.GONE
            b.lampcoreContainer.visibility = View.GONE
            b.osdContainer.visibility = View.GONE
            b.osdContainer.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            b.infoOverlay.visibility = View.VISIBLE
            b.episodesPanel.visibility = View.VISIBLE
            b.rvInfoList.layoutManager = LinearLayoutManager(this)
            b.rvInfoList.itemAnimator = null
            episodes = InfoListAdapter(labelOf = { it }, onSelected = { _, index -> selected = index })
            b.rvInfoList.adapter = episodes
            episodes.setItems((1..12).map { "Серия $it · Тихий город" }, 2)
            b.tvEpisodesCount.text = "3 / 12"
            b.tvOverlayMetaTitle.text = "Тихий город"
            b.tvOverlayMetaInfo.text = "2026 · Драма · 1 сезон · 16+"
            b.tvOverlayMetaOverview.text = "Возвращение домой меняет привычную жизнь героев. Каждый новый день открывает историю города с другой стороны."
            b.tvOverlayVideoInfo.text = "1080p · HLS"
            b.tvOverlayVideoInfo.visibility = View.VISIBLE
            b.ivOverlayPoster.setImageDrawable(ColorDrawable(Color.rgb(38, 54, 74)))
            b.tvOverlayMore.visibility = View.VISIBLE
            b.rvCast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            b.rvCast.itemAnimator = null
            b.rvCast.adapter = CastAdapter().also { adapter ->
                adapter.setItems(listOf(
                    PlayerUiState.CastMember("Анна Волкова", "Мария", null),
                    PlayerUiState.CastMember("Иван Соколов", "Андрей", null),
                    PlayerUiState.CastMember("Елена Орлова", "Ольга", null),
                    PlayerUiState.CastMember("Павел Морозов", "Дмитрий", null),
                ))
            }
            navigation = InfoOverlayNavigation(b.rvInfoList, b.svOverlayMeta, b.rvCast) {
                closed++; b.infoOverlay.visibility = View.GONE
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && navigation.onKeyDown(event.keyCode)) return true
            return super.dispatchKeyEvent(event)
        }

        fun frame(width: Int = 960, height: Int = 540) {
            b.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            b.root.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
            b.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            b.root.layout(0, 0, width, height)
        }

        fun key(code: Int) {
            dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            frame()
        }

        fun focusedEpisode(): Int = b.rvInfoList.findFocus()?.let {
            b.rvInfoList.findContainingViewHolder(it)?.bindingAdapterPosition
        } ?: -1

        fun screenshot(name: String) {
            val bitmap = Bitmap.createBitmap(b.root.width, b.root.height, Bitmap.Config.ARGB_8888)
            b.root.draw(Canvas(bitmap))
            val file = File("build/ui-preview/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        fun diagnose(name: String) {
            println("UI: focus=$currentFocus touch=${b.root.isInTouchMode} window=${b.root.hasWindowFocus()} " +
                "shown=${b.infoOverlay.isShown} rows=${b.rvInfoList.childCount} meta=${b.svOverlayMeta.width}x${b.svOverlayMeta.height}")
            screenshot(name)
        }
    }

    @Test fun openingSeriesThenDownAndOkSelectsTheNextEpisodeWithoutVisitingActors() {
        val controller = startHost()
        try {
            val host = controller.get()
            host.frame()
            host.navigation.open(2)
            host.frame()
            host.diagnose("info-open")
            assertEquals(2, host.focusedEpisode())
            host.key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals(3, host.focusedEpisode())
            host.key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(3, host.selected)
            assertFalse(host.b.rvCast.hasFocus())
            host.screenshot("info-series")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rightOpensDescriptionAndLeftOrBackReturnsToTheSameEpisode() {
        val controller = startHost()
        try {
            val host = controller.get()
            host.frame(); host.navigation.open(2); host.frame()
            host.key(KeyEvent.KEYCODE_DPAD_DOWN)
            host.key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(host.b.svOverlayMeta.hasFocus())
            host.key(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(3, host.focusedEpisode())
            host.key(KeyEvent.KEYCODE_DPAD_RIGHT)
            host.key(KeyEvent.KEYCODE_BACK)
            assertEquals(3, host.focusedEpisode())
            assertEquals(0, host.closed)
            host.key(KeyEvent.KEYCODE_BACK)
            assertEquals(1, host.closed)
            host.b.osdContainer.visibility = View.VISIBLE
            host.b.osdContainer.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            host.b.tvTitle.text = "Тихий город · Серия 3"
            host.b.tvCurrentTime.text = "18:24"
            host.b.tvDuration.text = "44:10"
            host.b.progressBar.progress = 420
            host.b.btnPlayPause.requestFocus()
            host.frame()
            host.screenshot("player-controls")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unchangedPlaybackUpdatesDoNotResetTheListOrFocusedRow() {
        val controller = startHost()
        try {
            val host = controller.get()
            host.frame(); host.navigation.open(2); host.frame()
            host.key(KeyEvent.KEYCODE_DPAD_DOWN)
            val focused = host.currentFocus
            var invalidations = 0
            host.episodes.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { invalidations++ }
            })
            repeat(20) { host.episodes.setItems((1..12).map { "Серия $it · Тихий город" }, 2); host.frame() }
            assertEquals(0, invalidations)
            assertSame(focused, host.currentFocus)
            assertEquals(3, host.focusedEpisode())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun movieWithoutAPlaylistOpensItsDescriptionAndDetailsRender() {
        val controller = startHost()
        try {
            val host = controller.get()
            host.b.episodesPanel.visibility = View.GONE
            host.b.rvInfoList.visibility = View.GONE
            host.b.tvCastHeader.visibility = View.VISIBLE
            host.b.rvCast.visibility = View.VISIBLE
            host.b.tvOverlayDetails.visibility = View.VISIBLE
            host.b.tvOverlayDetails.text = "Режиссёр · Алексей Миронов\nЖанры · Драма, детектив"
            host.navigation.open(0); host.frame()
            host.diagnose("info-movie-open")
            assertTrue(host.b.svOverlayMeta.hasFocus())
            host.b.svOverlayMeta.scrollTo(0, 120); host.frame()
            host.screenshot("info-movie-details")
            host.key(KeyEvent.KEYCODE_BACK)
            assertEquals(1, host.closed)
        } finally { controller.pause().stop().destroy() }
    }
}
