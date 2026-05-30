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
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.lampa.player.R
import com.lampa.player.databinding.ActivityPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val vm: PlayerViewModel by viewModels()

    private var longPressJob: kotlinx.coroutines.Job? = null
    private var longPressSpeed = 1
    private var isLongPressing = false

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
        binding.btnSkipIntro.setOnClickListener { vm.skipIntro() }
        binding.btnRetry.setOnClickListener { vm.retryPlayback() }
        binding.btnExit.setOnClickListener { finishWithResult() }
        binding.btnCancelAutoNext.setOnClickListener { vm.cancelAutoNext() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            vm.uiState.collectLatest { state ->
                binding.tvTitle.text = state.title
                binding.tvQuality.isVisible = state.quality.isNotEmpty()
                binding.tvQuality.text = state.quality
                binding.tvTranslator.isVisible = state.translator.isNotEmpty()
                binding.tvTranslator.text = state.translator

                binding.progressBuffering.isVisible = state.isLoading
                binding.osdContainer.isVisible = state.osdVisible
                binding.errorContainer.isVisible = state.hasError
                binding.tvErrorMessage.text = state.errorMessage

                binding.btnSkipIntro.isVisible = state.showSkipIntro && !state.hasError
                binding.diagText.isVisible = state.settings.diag && state.diagText.isNotEmpty()
                binding.diagText.text = state.diagText

                val showAutoNext = state.autoNextCountdown >= 0
                binding.autoNextOverlay.isVisible = showAutoNext
                if (showAutoNext) {
                    binding.tvAutoNextCountdown.text =
                        getString(R.string.autonext_countdown, state.autoNextCountdown)
                }
            }
        }

        lifecycleScope.launch {
            vm.navigateToNext.collect {
                // Signal Lampa that episode ended; result will carry position
                finishWithResult(completed = true)
            }
        }

        lifecycleScope.launch {
            vm.showExitDialog.collect { showExitDialog() }
        }

        // Progress polling
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val player = vm.player
                val pos = player.currentPosition
                val dur = player.duration
                if (dur > 0) {
                    vm.onProgress(pos, dur)
                    val progress = ((pos.toFloat() / dur) * 1000).toInt()
                    binding.progressBar.progress = progress
                    binding.tvCurrentTime.text = formatTime(pos)
                    binding.tvDuration.text = formatTime(dur)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val osdVisible = vm.uiState.value.osdVisible

        if (!osdVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    vm.onKeyLeft()
                    vm.showOsd()
                    startLongPressRewind(forward = false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    vm.onKeyRight()
                    vm.showOsd()
                    startLongPressRewind(forward = true)
                    return true
                }
                KeyEvent.KEYCODE_PAGE_UP -> { vm.onKeyPageUp(); return true }
                KeyEvent.KEYCODE_PAGE_DOWN -> { vm.onKeyPageDown(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    vm.onKeyOk()
                    vm.showOsd()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(false); return true }
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(true); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { vm.onKeyOk(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { vm.onKeyLeft(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { vm.onKeyRight(); return true }
            }
        }

        // Any key shows OSD
        vm.showOsd()
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        stopLongPress()
        return super.onKeyUp(keyCode, event)
    }

    private fun startLongPressRewind(forward: Boolean) {
        longPressJob?.cancel()
        longPressSpeed = 1
        isLongPressing = false
        longPressJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(700)
            isLongPressing = true
            while (true) {
                val seekMs = (longPressSpeed * 5_000).toLong()
                if (forward) vm.player.seekTo(vm.player.currentPosition + seekMs)
                else vm.player.seekTo((vm.player.currentPosition - seekMs).coerceAtLeast(0))
                kotlinx.coroutines.delay(300)
                if (longPressSpeed < 12) longPressSpeed++
            }
        }
    }

    private fun stopLongPress() {
        longPressJob?.cancel()
        isLongPressing = false
        longPressSpeed = 1
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_dialog_title)
            .setMessage(R.string.exit_dialog_message)
            .setPositiveButton(R.string.action_exit) { _, _ -> finishWithResult() }
            .setNegativeButton(R.string.action_cancel, null)
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

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
