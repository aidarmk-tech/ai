package com.lampa.player.player

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    // Turbo rewind
    private var turboJob: Job? = null
    private var turboSpeed = 1

    // When true: OSD opened by seek → ←/→ continue seeking.
    // When false: OSD opened by OK/↓ → ←/→ navigate OSD buttons.
    private var osdFromSeek = false

    // Info panel width in pixels (used for slide-in animation)
    private val infoPanelWidthPx by lazy {
        resources.getDimensionPixelSize(R.dimen.info_panel_width).toFloat()
    }

    // Ordered list of (tab enum → tab TextView) for the unified panel
    private val tabViews by lazy {
        listOf(
            InfoPanelTab.EPISODES  to binding.tabEpisodes,
            InfoPanelTab.AUDIO     to binding.tabAudio,
            InfoPanelTab.SUBTITLES to binding.tabSubtitles,
            InfoPanelTab.ABOUT     to binding.tabAbout,
            InfoPanelTab.EPG       to binding.tabEpg,
        )
    }

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

        setupLists()
        setupTabs()
        setupOsdClicks()
        observeState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Only update if the intent carries lampa_data — plain external-player intents
        // arrive here too and must not overwrite good data.
        if (!intent.hasExtra("lampa_data")) return
        val (_, card) = IntentParser.parse(intent) ?: return
        vm.updateCardMeta(card)
    }

    // ─── List setup ────────────────────────────────────────────────

    private fun setupLists() {
        binding.rvInfoList.layoutManager = LinearLayoutManager(this)
        binding.rvInfoList.adapter = episodeAdapter

        binding.rvAudioList.layoutManager = LinearLayoutManager(this)
        binding.rvAudioList.adapter = audioAdapter

        binding.rvSubtitleList.layoutManager = LinearLayoutManager(this)
        binding.rvSubtitleList.adapter = subtitleAdapter
    }

    // ─── Tab setup ─────────────────────────────────────────────────

    private fun setupTabs() {
        tabViews.forEach { (tab, view) ->
            view.setOnFocusChangeListener { _, focused -> if (focused) vm.setPanelTab(tab) }
            view.setOnClickListener { vm.setPanelTab(tab); focusPanelContent() }
        }
    }

    // ─── OSD button clicks ─────────────────────────────────────────

    private fun setupOsdClicks() {
        binding.btnPlayPause.setOnClickListener { vm.onKeyOk(); vm.showOsd() }
        binding.btnRewind.setOnClickListener { doSeek(false, fast = false); vm.showOsd() }
        binding.btnForward.setOnClickListener { doSeek(true, fast = false); vm.showOsd() }
        binding.btnPrev.setOnClickListener { vm.onPrevEpisode(); vm.showOsd() }
        binding.btnNext.setOnClickListener { vm.onNextEpisode(); vm.showOsd() }
        binding.btnInfo.setOnClickListener { if (vm.uiState.value.panelVisible) vm.hidePanel() else vm.openPanel() }
        binding.btnMarkIntro.setOnClickListener {
            vm.markIntro()
            Toast.makeText(
                this,
                getString(R.string.intro_marked, vm.player.currentPosition / 1000f),
                Toast.LENGTH_SHORT
            ).show()
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

                // OSD
                val osdWasHidden = !binding.osdContainer.isVisible
                binding.osdContainer.isVisible = s.osdVisible
                if (s.osdVisible && osdWasHidden && !osdFromSeek) {
                    binding.btnPlayPause.requestFocus()
                }

                // Unified info panel (right-side Netflix/Kivi style)
                val panelWasHidden = !binding.infoPanel.isVisible
                if (s.panelVisible) {
                    if (panelWasHidden) {
                        binding.infoPanel.translationX = infoPanelWidthPx
                        binding.infoPanel.isVisible = true
                        binding.infoPanel.animate()
                            .translationX(0f)
                            .setDuration(280)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    }
                    applyPanel(s)
                    if (panelWasHidden) focusActiveTab()
                } else if (!panelWasHidden) {
                    // Slide out to the right, then hide
                    binding.infoPanel.animate()
                        .translationX(infoPanelWidthPx)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.AccelerateInterpolator())
                        .withEndAction { binding.infoPanel.isVisible = false }
                        .start()
                }

                binding.progressBuffering.isVisible = s.isLoading && !s.isPlaying && !s.hasError
                binding.errorContainer.isVisible = s.hasError
                binding.tvErrorMessage.text = s.errorMessage
                binding.btnSkipIntro.isVisible = s.showSkipIntro && !s.hasError
                binding.diagText.isVisible = s.settings.diag && s.diagText.isNotEmpty()
                binding.diagText.text = s.diagText

                val showNext = s.autoNextCountdown >= 0
                binding.autoNextOverlay.isVisible = showNext
                if (showNext) binding.tvAutoNextCountdown.text =
                    getString(R.string.autonext_countdown, s.autoNextCountdown)

                // TMDB splash overlay
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
        lifecycleScope.launch { vm.showResumeDialog.collect { showResumeDialog(it) } }

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
                    if (seekTargetMs >= 0) {
                        binding.seekIndicator.progress = ((seekTargetMs.toFloat() / dur) * 1000).toInt()
                    }
                }
            }
        }
    }

    // ─── Unified info panel ────────────────────────────────────────

    private fun applyPanel(s: PlayerUiState) {
        // Determine which tabs should be visible
        val hasTracks = s.audioTracks.isNotEmpty() || s.subtitleTracks.isNotEmpty()
        val hasEpg = s.card?.epgTitle != null

        tabViews.forEach { (tab, view) ->
            view.isVisible = when (tab) {
                InfoPanelTab.EPISODES  -> s.episodes.size > 1
                InfoPanelTab.AUDIO     -> s.audioTracks.isNotEmpty()
                InfoPanelTab.SUBTITLES -> s.subtitleTracks.isNotEmpty()
                InfoPanelTab.ABOUT     -> true  // always shown
                InfoPanelTab.EPG       -> hasEpg
            }
            val active = tab == s.infoPanelTab
            view.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.accent_primary else R.color.text_secondary
                )
            )
        }

        // Fill lists
        episodeAdapter.setItems(s.episodes, s.currentEpisodeIndex)
        audioAdapter.setItems(s.audioTracks, s.selectedAudioIndex)
        subtitleAdapter.setItems(s.subtitleTracks, s.selectedSubtitleIndex)

        // About tab content
        val meta = s.metadata
        binding.tvPanelTitle.text = meta?.title ?: s.title
        binding.tvPanelInfo.text = meta?.info ?: ""
        binding.tvPanelOverview.text = meta?.overview ?: ""
        binding.tvPanelVideoInfo.text = s.videoInfo
        binding.tvPanelVideoInfo.isVisible = s.videoInfo.isNotEmpty()
        if (!meta?.posterUrl.isNullOrEmpty()) {
            Glide.with(this).load(meta!!.posterUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivPanelPoster)
        }

        // EPG text
        binding.tvEpgContent.text = buildString {
            s.card?.epgTitle?.let { append(it) }
            s.card?.epgStart?.let { if (isNotEmpty()) append("\n"); append(it) }
            s.card?.epgEnd?.let { append(" — $it") }
        }

        // Show only the active tab's content view
        binding.rvInfoList.isVisible    = s.infoPanelTab == InfoPanelTab.EPISODES
        binding.rvAudioList.isVisible   = s.infoPanelTab == InfoPanelTab.AUDIO
        binding.rvSubtitleList.isVisible = s.infoPanelTab == InfoPanelTab.SUBTITLES
        binding.panelAbout.isVisible    = s.infoPanelTab == InfoPanelTab.ABOUT
        binding.tvEpgContent.isVisible  = s.infoPanelTab == InfoPanelTab.EPG

        if (s.infoPanelTab == InfoPanelTab.EPISODES)
            binding.rvInfoList.scrollToPosition(s.currentEpisodeIndex)
    }

    /** Move focus to the active tab button in the panel tab strip. */
    private fun focusActiveTab() {
        val tab = vm.uiState.value.infoPanelTab
        tabViews.firstOrNull { it.first == tab && it.second.isVisible }?.second?.requestFocus()
            ?: tabViews.firstOrNull { it.second.isVisible }?.second?.requestFocus()
    }

    /** Move focus from the tab strip down into the active content area. */
    private fun focusPanelContent() {
        when (vm.uiState.value.infoPanelTab) {
            InfoPanelTab.EPISODES  -> binding.rvInfoList
            InfoPanelTab.AUDIO     -> binding.rvAudioList
            InfoPanelTab.SUBTITLES -> binding.rvSubtitleList
            InfoPanelTab.ABOUT     -> binding.panelAbout
            InfoPanelTab.EPG       -> binding.tvEpgContent
        }.requestFocus()
    }

    private fun hideMetadataOverlay() {
        binding.metadataOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            binding.metadataOverlay.isVisible = false
            binding.metadataOverlay.alpha = 1f
        }.start()
    }

    // ─── Key handling ──────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = vm.uiState.value
        val osdVisible = s.osdVisible

        // ── Info panel open (priority) ─────────────────────────────
        // ←/→ navigate between tab items, ↑/↓ move between tabs and content.
        // We only intercept BACK and ↓ from tabs into content.
        if (s.panelVisible) {
            val onTabs = binding.tabStrip.hasFocus()
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { vm.hidePanel(); true }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (onTabs) { focusPanelContent(); true } else super.onKeyDown(keyCode, event)
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (!onTabs) { focusActiveTab(); true } else super.onKeyDown(keyCode, event)
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // ── Media remote keys — always handled ───────────────────
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.onKeyOk(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { doSeek(true); vm.showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { doSeek(false); vm.showOsd(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { vm.onNextEpisode(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { vm.onPrevEpisode(); return true }
        }

        // ── OSD visible ───────────────────────────────────────────
        if (osdVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(true, s.panelVisible); true }
                // ↑ → open info panel
                KeyEvent.KEYCODE_DPAD_UP -> { if (vm.uiState.value.panelVisible) vm.hidePanel() else vm.openPanel(); true }
                // ↓ → open info panel
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (vm.uiState.value.panelVisible) vm.hidePanel() else vm.openPanel(); true }
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_MENU -> { if (vm.uiState.value.panelVisible) vm.hidePanel() else vm.openPanel(); true }
                // ← / → — seek if OSD was opened by seek, otherwise navigate OSD buttons
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (osdFromSeek) { doSeek(false); vm.showOsd(); true }
                    else super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (osdFromSeek) { doSeek(true); vm.showOsd(); true }
                    else super.onKeyDown(keyCode, event)
                }
                // OK in seek-mode: switch to button navigation
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (osdFromSeek) {
                        osdFromSeek = false; binding.btnPlayPause.requestFocus(); true
                    } else super.onKeyDown(keyCode, event)
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // ── OSD hidden ────────────────────────────────────────────
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                doSeek(false, repeatCount = event.repeatCount)
                osdFromSeek = true; vm.showOsd(); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                doSeek(true, repeatCount = event.repeatCount)
                osdFromSeek = true; vm.showOsd(); true
            }
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> { vm.onKeyPageUp(); vm.showOsd(); true }
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.onKeyPageDown(); vm.showOsd(); true }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP -> { osdFromSeek = false; vm.showOsd(); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> { osdFromSeek = false; vm.onKeyOk(); vm.showOsd(); true }
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU -> { if (vm.uiState.value.panelVisible) vm.hidePanel() else vm.openPanel(); true }
            KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(false, s.panelVisible); true }
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
        if (seekTargetMs < 0) seekTargetMs = vm.player.currentPosition
        seekTargetMs = if (forward) (seekTargetMs + stepMs).coerceAtMost(dur)
                       else (seekTargetMs - stepMs).coerceAtLeast(0)

        binding.seekPreviewContainer.isVisible = true
        binding.tvSeekTime.text = formatTime(seekTargetMs)
        binding.tvSeekDelta.text = if (forward) "+${stepMs / 1000}с →" else "← -${stepMs / 1000}с"
        binding.seekIndicator.progress = ((seekTargetMs.toFloat() / dur) * 1000).toInt()
        binding.seekIndicator.isVisible = true

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

    private fun showResumeDialog(prompt: PlayerViewModel.ResumePrompt) {
        val min = (prompt.positionSec / 60).toInt()
        val sec = (prompt.positionSec % 60).toInt()
        AlertDialog.Builder(this)
            .setTitle(R.string.resume_dialog_title)
            .setMessage(getString(R.string.resume_dialog_message, "%d:%02d".format(min, sec)))
            .setPositiveButton(R.string.action_resume) { _, _ -> vm.resumePlaybackConfirmed() }
            .setNegativeButton(R.string.action_restart) { _, _ -> vm.restartFromBeginning() }
            .setOnCancelListener { vm.resumePlaybackConfirmed() }
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
