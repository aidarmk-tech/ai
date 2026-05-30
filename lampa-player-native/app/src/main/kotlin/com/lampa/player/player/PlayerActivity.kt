package com.lampa.player.player

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.lampa.player.R
import com.lampa.player.databinding.ActivityPlayerBinding
import com.lampa.player.domain.model.EpisodeItem
import com.lampa.player.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val vm: PlayerViewModel by viewModels()

    private val episodeAdapter = InfoListAdapter<EpisodeItem>(
        labelOf = { it.title },
        onSelected = { vm.selectEpisode(it) }
    )
    private val audioAdapter = InfoListAdapter<String>(
        labelOf = { it },
        onSelected = { vm.selectAudio(it.substringBefore(":").trim().toIntOrNull() ?: 0) }
    )
    private val subtitleAdapter = InfoListAdapter<String>(
        labelOf = { it },
        onSelected = { vm.selectSubtitle(it.substringBefore(":").trim().toIntOrNull() ?: 0) }
    )

    // Seek preview state
    private var seekTargetMs = -1L
    private var seekDebounceJob: Job? = null
    private var hideSeekPreviewJob: Job? = null

    // Turbo rewind
    private var turboJob: Job? = null
    private var turboSpeed = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val (url, card) = IntentParser.parse(intent) ?: run {
            Toast.makeText(this, getString(R.string.error_no_url), Toast.LENGTH_LONG).show()
            finish(); return
        }

        vm.initPlayer(this, url, card)
        binding.playerView.player = vm.player
        binding.playerView.useController = false

        setupInfoPanel()
        setupOsdClicks()
        observeState()
    }

    // ─── Info panel setup ──────────────────────────────────────────

    private fun setupInfoPanel() {
        binding.rvInfoList.layoutManager = LinearLayoutManager(this)
        binding.rvInfoList.adapter = episodeAdapter

        binding.tabEpisodes.setOnClickListener { vm.setInfoPanelTab(InfoPanelTab.EPISODES) }
        binding.tabAudio.setOnClickListener { vm.setInfoPanelTab(InfoPanelTab.AUDIO) }
        binding.tabSubtitles.setOnClickListener { vm.setInfoPanelTab(InfoPanelTab.SUBTITLES) }
        binding.tabEpg.setOnClickListener { vm.setInfoPanelTab(InfoPanelTab.EPG) }
    }

    // ─── OSD button clicks (when focused) ──────────────────────────

    private fun setupOsdClicks() {
        binding.btnPlayPause.setOnClickListener { vm.onKeyOk(); vm.showOsd() }
        binding.btnRewind.setOnClickListener { doSeek(false, fast = false); vm.showOsd() }
        binding.btnForward.setOnClickListener { doSeek(true, fast = false); vm.showOsd() }
        binding.btnPrev.setOnClickListener { vm.onPrevEpisode(); vm.showOsd() }
        binding.btnNext.setOnClickListener { vm.onNextEpisode(); vm.showOsd() }
        binding.btnInfo.setOnClickListener { vm.toggleInfoPanel() }
        binding.btnMarkIntro.setOnClickListener {
            vm.markIntro()
            Toast.makeText(this, getString(R.string.intro_marked, vm.player.currentPosition / 1000f), Toast.LENGTH_SHORT).show()
        }
        binding.btnSkipIntro.setOnClickListener { vm.skipIntro() }
        binding.btnRetry.setOnClickListener { vm.retryPlayback() }
        binding.btnExit.setOnClickListener { finishWithResult() }
        binding.btnCancelAutoNext.setOnClickListener { vm.cancelAutoNext() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.metadataOverlay.setOnClickListener { hideMetadataOverlay() }
    }

    // ─── State observation ─────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            vm.uiState.collectLatest { s ->
                // Top bar
                binding.tvTitle.text = s.title
                binding.tvQuality.isVisible = s.quality.isNotEmpty()
                binding.tvQuality.text = s.quality
                binding.tvTranslator.isVisible = s.translator.isNotEmpty()
                binding.tvTranslator.text = s.translator
                binding.btnPlayPause.setImageResource(
                    if (s.isPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )
                binding.tvEpisodeBadge.isVisible = s.episodes.size > 1
                binding.tvEpisodeBadge.text = "${s.currentEpisodeIndex + 1}/${s.episodes.size}"

                // OSD visibility — when shown, focus play/pause button
                val osdWasHidden = !binding.osdContainer.isVisible
                binding.osdContainer.isVisible = s.osdVisible
                if (s.osdVisible && osdWasHidden) {
                    binding.btnPlayPause.requestFocus()
                }

                binding.progressBuffering.isVisible = s.isLoading && !s.hasError
                binding.errorContainer.isVisible = s.hasError
                binding.tvErrorMessage.text = s.errorMessage
                binding.btnSkipIntro.isVisible = s.showSkipIntro && !s.hasError
                binding.diagText.isVisible = s.settings.diag && s.diagText.isNotEmpty()
                binding.diagText.text = s.diagText

                val showNext = s.autoNextCountdown >= 0
                binding.autoNextOverlay.isVisible = showNext
                if (showNext) binding.tvAutoNextCountdown.text =
                    getString(R.string.autonext_countdown, s.autoNextCountdown)

                // Info panel
                binding.infoPanel.isVisible = s.infoPanelVisible
                if (s.infoPanelVisible) applyPanelTab(s)

                // Video info row (format/resolution/audio)
                binding.tvVideoInfo.isVisible = s.videoInfo.isNotEmpty()
                binding.tvVideoInfo.text = s.videoInfo

                // TMDB overlay
                s.metadata?.let { meta ->
                    if (s.showMetadata && !binding.metadataOverlay.isVisible) {
                        binding.metadataOverlay.alpha = 0f
                        binding.metadataOverlay.isVisible = true
                        binding.metadataOverlay.animate().alpha(1f).setDuration(400).start()
                    } else if (!s.showMetadata && binding.metadataOverlay.isVisible) {
                        hideMetadataOverlay()
                    }
                    binding.tvMetaTitle.text = meta.title
                    binding.tvMetaInfo.text = meta.info
                    binding.tvMetaOverview.text = meta.overview
                    if (!meta.posterUrl.isNullOrEmpty()) {
                        Glide.with(this@PlayerActivity).load(meta.posterUrl)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .into(binding.ivMetaPoster)
                    }
                }
            }
        }

        lifecycleScope.launch { vm.navigateToNext.collect { finishWithResult(completed = true) } }
        lifecycleScope.launch { vm.showExitDialog.collect { showExitDialog() } }

        // Progress polling
        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                val p = vm.player
                val pos = p.currentPosition
                val dur = p.duration
                if (dur > 0) {
                    vm.onProgress(pos, dur)
                    binding.progressBar.progress = ((pos.toFloat() / dur) * 1000).toInt()
                    binding.tvCurrentTime.text = formatTime(pos)
                    binding.tvDuration.text = formatTime(dur)
                    // Update seek indicator position
                    if (seekTargetMs >= 0) {
                        binding.seekIndicator.progress = ((seekTargetMs.toFloat() / dur) * 1000).toInt()
                    }
                }
            }
        }
    }

    private fun hideMetadataOverlay() {
        binding.metadataOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            binding.metadataOverlay.isVisible = false
            binding.metadataOverlay.alpha = 1f
        }.start()
    }

    private fun applyPanelTab(s: PlayerUiState) {
        val accent = getColor(R.color.accent_primary)
        val secondary = getColor(R.color.text_secondary)
        listOf(
            binding.tabEpisodes to InfoPanelTab.EPISODES,
            binding.tabAudio to InfoPanelTab.AUDIO,
            binding.tabSubtitles to InfoPanelTab.SUBTITLES,
            binding.tabEpg to InfoPanelTab.EPG,
        ).forEach { (tv, tab) -> tv.setTextColor(if (s.infoPanelTab == tab) accent else secondary) }

        binding.tabEpisodes.isVisible = s.episodes.isNotEmpty()
        binding.tabEpg.isVisible = s.card?.epgTitle != null

        when (s.infoPanelTab) {
            InfoPanelTab.EPISODES -> {
                binding.rvInfoList.adapter = episodeAdapter
                episodeAdapter.setItems(s.episodes, s.currentEpisodeIndex)
                binding.rvInfoList.scrollToPosition(s.currentEpisodeIndex)
            }
            InfoPanelTab.AUDIO -> {
                binding.rvInfoList.adapter = audioAdapter
                audioAdapter.setItems(s.audioTracks, s.selectedAudioIndex)
            }
            InfoPanelTab.SUBTITLES -> {
                binding.rvInfoList.adapter = subtitleAdapter
                subtitleAdapter.setItems(s.subtitleTracks, s.selectedSubtitleIndex)
            }
            InfoPanelTab.EPG -> {
                binding.tvEpgContent.text = buildString {
                    append(s.card?.epgTitle ?: "")
                    s.card?.epgStart?.let { append("\n$it") }
                    s.card?.epgEnd?.let { append(" — $it") }
                }
            }
        }
        binding.rvInfoList.isVisible = s.infoPanelTab != InfoPanelTab.EPG
        binding.tvEpgContent.isVisible = s.infoPanelTab == InfoPanelTab.EPG
    }

    // ─── Key handling ──────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = vm.uiState.value
        val panelVisible = s.infoPanelVisible
        val osdVisible = s.osdVisible

        // ── Panel open: only intercept BACK and LEFT ──────────────
        if (panelVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_LEFT -> { vm.hideInfoPanel(); true }
                else -> super.onKeyDown(keyCode, event) // let RecyclerView navigate
            }
        }

        // ── Media keys — always handle ────────────────────────────
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.onKeyOk(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { doSeek(true); vm.showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { doSeek(false); vm.showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { vm.onNextEpisode(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { vm.onPrevEpisode(); return true }
        }

        // ── OSD visible: focus system handles navigation ──────────
        if (osdVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(true, false); true }
                // ↓ while OSD visible → open info panel (audio/subtitles)
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    vm.setInfoPanelTab(InfoPanelTab.AUDIO)
                    vm.toggleInfoPanel()
                    true
                }
                // INFO / MENU → info panel
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_MENU -> { vm.toggleInfoPanel(); true }
                // ← / → while OSD: seek AND let focus navigate
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // If a button other than progress is focused, let focus system navigate
                    val focused = currentFocus
                    if (focused == binding.progressBar || focused == null) {
                        doSeek(false); vm.showOsd(); true
                    } else {
                        super.onKeyDown(keyCode, event)
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val focused = currentFocus
                    if (focused == binding.progressBar || focused == null) {
                        doSeek(true); vm.showOsd(); true
                    } else {
                        super.onKeyDown(keyCode, event)
                    }
                }
                // Everything else: let focus system handle (navigates between buttons, OK clicks)
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // ── OSD hidden: D-Pad controls playback ──────────────────
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                doSeek(false, repeatCount = event.repeatCount)
                vm.showOsd()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                doSeek(true, repeatCount = event.repeatCount)
                vm.showOsd()
                true
            }
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> { vm.onKeyPageUp(); vm.showOsd(); true }
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.onKeyPageDown(); vm.showOsd(); true }
            // ↓ or OK when OSD hidden → show OSD (with focus on controls)
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> { vm.showOsd(); true }
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU -> { vm.toggleInfoPanel(); true }
            KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(false, false); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            turboJob?.cancel(); turboSpeed = 1
        }
        return super.onKeyUp(keyCode, event)
    }

    // ─── Seek with preview ─────────────────────────────────────────

    private fun doSeek(forward: Boolean, fast: Boolean = false, repeatCount: Int = 0) {
        val dur = vm.player.duration.takeIf { it > 0 } ?: return

        val stepMs = when {
            fast || repeatCount > 8 -> 30_000L
            repeatCount > 3 -> 15_000L
            else -> 10_000L
        }

        // Initialise from current position if first in sequence
        if (seekTargetMs < 0) seekTargetMs = vm.player.currentPosition
        seekTargetMs = if (forward) (seekTargetMs + stepMs).coerceAtMost(dur)
                       else (seekTargetMs - stepMs).coerceAtLeast(0)

        // Show preview
        binding.seekPreviewContainer.isVisible = true
        binding.tvSeekTime.text = formatTime(seekTargetMs)
        binding.tvSeekDelta.text = if (forward) "+${stepMs / 1000}с →" else "← -${stepMs / 1000}с"
        binding.seekIndicator.progress = ((seekTargetMs.toFloat() / dur) * 1000).toInt()
        binding.seekIndicator.isVisible = true

        // Debounce: commit seek after 600ms inactivity
        seekDebounceJob?.cancel()
        seekDebounceJob = lifecycleScope.launch {
            delay(600)
            vm.player.seekTo(seekTargetMs)
            seekTargetMs = -1L
            delay(1500)
            binding.seekPreviewContainer.isVisible = false
            binding.seekIndicator.isVisible = false
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────

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
        setResult(RESULT_OK, Intent().apply {
            putExtra("position", posMs)
            putExtra("duration", durMs)
            putExtra("watched_percent", percent)
            putExtra("completed", completed || percent >= 90)
        })
        finish()
    }

    override fun onStop() { super.onStop(); vm.player.pause() }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
