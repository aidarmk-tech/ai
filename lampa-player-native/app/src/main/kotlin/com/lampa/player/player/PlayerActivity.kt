package com.lampa.player.player

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.lampa.player.R
import com.lampa.player.databinding.ActivityPlayerBinding
import com.lampa.player.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val vm: PlayerViewModel by viewModels()

    private var longPressJob: kotlinx.coroutines.Job? = null
    private var longPressSpeed = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val (url, card) = IntentParser.parse(intent) ?: run {
            Toast.makeText(this, getString(R.string.error_no_url), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        vm.initPlayer(this, url, card)
        binding.playerView.player = vm.player
        binding.playerView.useController = false

        setupClickListeners()
        observeState()
    }

    private fun setupClickListeners() {
        // OSD transport controls
        binding.btnPlayPause.setOnClickListener { vm.onKeyOk(); resetOsdTimer() }
        binding.btnRewind.setOnClickListener { vm.onKeyLeft(); resetOsdTimer() }
        binding.btnForward.setOnClickListener { vm.onKeyRight(); resetOsdTimer() }
        binding.btnPrev.setOnClickListener {
            val pos = vm.player.currentPosition
            // If past 5s — restart; else signal prev
            if (pos > 5000) vm.player.seekTo(0) else vm.onPrevEpisode()
            resetOsdTimer()
        }
        binding.btnNext.setOnClickListener { vm.onNextEpisode(); resetOsdTimer() }

        // Functional buttons
        binding.btnSkipIntro.setOnClickListener { vm.skipIntro() }
        binding.btnRetry.setOnClickListener { vm.retryPlayback() }
        binding.btnExit.setOnClickListener { finishWithResult() }
        binding.btnCancelAutoNext.setOnClickListener { vm.cancelAutoNext() }
        binding.btnMarkIntro.setOnClickListener {
            vm.markIntro()
            Toast.makeText(this, getString(R.string.intro_marked, vm.player.currentPosition / 1000f), Toast.LENGTH_SHORT).show()
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Metadata overlay — tap to dismiss
        binding.metadataOverlay.setOnClickListener { hideMetadata() }

        // Progress bar click to seek
        binding.progressBar.setOnClickListener { view ->
            val x = view.width
            if (x > 0) {
                val fraction = 0f // placeholder; real seek via D-Pad
            }
        }
    }

    private fun resetOsdTimer() = vm.showOsd()

    private fun observeState() {
        lifecycleScope.launch {
            vm.uiState.collectLatest { state ->
                // Title & pills
                binding.tvTitle.text = state.title
                binding.tvQuality.isVisible = state.quality.isNotEmpty()
                binding.tvQuality.text = state.quality
                binding.tvTranslator.isVisible = state.translator.isNotEmpty()
                binding.tvTranslator.text = state.translator

                // Play/Pause icon
                binding.btnPlayPause.setImageResource(
                    if (state.isPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )

                // Loading
                binding.progressBuffering.isVisible = state.isLoading && !state.hasError

                // OSD
                binding.osdContainer.isVisible = state.osdVisible

                // Error
                binding.errorContainer.isVisible = state.hasError
                binding.tvErrorMessage.text = state.errorMessage

                // Skip intro
                binding.btnSkipIntro.isVisible = state.showSkipIntro && !state.hasError

                // Diagnostics
                binding.diagText.isVisible = state.settings.diag && state.diagText.isNotEmpty()
                binding.diagText.text = state.diagText

                // Auto-next
                val showAutoNext = state.autoNextCountdown >= 0
                binding.autoNextOverlay.isVisible = showAutoNext
                if (showAutoNext) {
                    binding.tvAutoNextCountdown.text =
                        getString(R.string.autonext_countdown, state.autoNextCountdown)
                }

                // TMDB Metadata overlay
                state.metadata?.let { meta ->
                    if (state.showMetadata) showMetadata(meta) else hideMetadata()
                }
            }
        }

        lifecycleScope.launch {
            vm.navigateToNext.collect { finishWithResult(completed = true) }
        }

        lifecycleScope.launch {
            vm.showExitDialog.collect { showExitDialog() }
        }

        // Progress polling loop
        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                val player = vm.player
                val pos = player.currentPosition
                val dur = player.duration
                if (dur > 0) {
                    vm.onProgress(pos, dur)
                    binding.progressBar.progress = ((pos.toFloat() / dur) * 1000).toInt()
                    binding.tvCurrentTime.text = formatTime(pos)
                    binding.tvDuration.text = formatTime(dur)
                }
            }
        }
    }

    private fun showMetadata(meta: PlayerUiState.MetadataDisplay) {
        binding.metadataOverlay.isVisible = true
        binding.tvMetaTitle.text = meta.title
        binding.tvMetaInfo.text = meta.info
        binding.tvMetaOverview.text = meta.overview
        binding.tvMetaCast.text = meta.cast
        if (meta.posterUrl != null) {
            Glide.with(this)
                .load(meta.posterUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivMetaPoster)
        }
    }

    private fun hideMetadata() {
        binding.metadataOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            binding.metadataOverlay.isVisible = false
            binding.metadataOverlay.alpha = 1f
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val osdVisible = vm.uiState.value.osdVisible

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                vm.onKeyLeft(); vm.showOsd()
                if (event.isLongPress) startTurboRewind(false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                vm.onKeyRight(); vm.showOsd()
                if (event.isLongPress) startTurboRewind(true)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                vm.onKeyPageUp(); vm.showOsd(); return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                vm.onKeyPageDown(); vm.showOsd(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!osdVisible) { vm.showOsd() } else { vm.onKeyOk() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                vm.onKeyOk(); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.onKeyRight(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { vm.onKeyLeft(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { vm.onNextEpisode(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { vm.onPrevEpisode(); return true }
            KeyEvent.KEYCODE_INFO -> { vm.toggleMetadata(); return true }
            KeyEvent.KEYCODE_BACK -> {
                vm.onKeyBack(osdVisible); return true
            }
        }

        vm.showOsd()
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            stopTurboRewind()
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startTurboRewind(forward: Boolean) {
        longPressJob?.cancel()
        longPressSpeed = 1
        longPressJob = lifecycleScope.launch {
            delay(600)
            while (isActive) {
                val seekMs = (longPressSpeed * 5_000).toLong()
                val player = vm.player
                if (forward) player.seekTo(player.currentPosition + seekMs)
                else player.seekTo((player.currentPosition - seekMs).coerceAtLeast(0))
                delay(250)
                if (longPressSpeed < 12) longPressSpeed++
            }
        }
    }

    private fun stopTurboRewind() {
        longPressJob?.cancel()
        longPressSpeed = 1
    }

    private fun showExitDialog() {
        vm.player.pause()
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_dialog_title)
            .setMessage(R.string.exit_dialog_message)
            .setPositiveButton(R.string.action_exit) { _, _ -> finishWithResult() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> vm.player.play() }
            .setOnCancelListener { vm.player.play() }
            .show()
    }

    private fun finishWithResult(completed: Boolean = false) {
        val (posMs, durMs, percent) = vm.buildResultExtras()
        val resultIntent = Intent().apply {
            putExtra("position", posMs)
            putExtra("duration", durMs)
            putExtra("watched_percent", percent)
            putExtra("completed", completed || percent >= 90)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onStop() {
        super.onStop()
        vm.player.pause()
    }

    override fun onDestroy() {
        longPressJob?.cancel()
        super.onDestroy()
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
