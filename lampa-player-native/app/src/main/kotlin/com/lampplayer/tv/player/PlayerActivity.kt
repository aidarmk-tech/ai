package com.lampplayer.tv.player

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
import com.lampplayer.tv.R
import com.lampplayer.tv.databinding.ActivityPlayerBinding
import com.lampplayer.tv.domain.model.EpisodeItem
import com.lampplayer.tv.settings.SettingsActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val (url, card) = IntentParser.parse(intent) ?: run {
            Toast.makeText(this, getString(R.string.error_no_url), Toast.LENGTH_LONG).show()
            finish(); return
        }

        maybeShowIntentDebug(card)

        val dm = resources.displayMetrics
        vm.setDisplayAspect("${dm.widthPixels}:${dm.heightPixels}")

        vm.initPlayer(this, url, card)
        bindEngineSurface()

        setupLists()
        setupOsdClicks()
        observeState()
    }

    private var debugVisible = false

    /**
     * Show the intent-diagnostic overlay automatically when no rich metadata arrived,
     * so we can see on-device which channel survived. Dismiss with BACK; auto-hides.
     */
    private fun maybeShowIntentDebug(card: com.lampplayer.tv.domain.model.CardMeta) {
        val noMeta = card.tmdbId == null && card.overview.isNullOrBlank() &&
            card.posterUrl.isNullOrBlank() && card.backdropUrl.isNullOrBlank()
        if (!noMeta) return
        val dump = buildString {
            append("⚠ Метаданные не пришли\n")
            append(IntentParser.debugDump(intent))
            append("\n— разобрано —\n")
            append("title: ").append(card.title.take(60)).append('\n')
            append("tmdb: ").append(card.tmdbId ?: "—")
            append("  poster: ").append(if (card.posterUrl != null) "да" else "нет")
        }
        binding.intentDebug.text = dump
        binding.intentDebug.isVisible = true
        debugVisible = true
        lifecycleScope.launch {
            delay(30_000)
            binding.intentDebug.isVisible = false
            debugVisible = false
        }
    }

    /** Attach the render surface for the engine the ViewModel actually started. */
    private fun bindEngineSurface() {
        if (vm.isUsingVlc) {
            binding.playerView.player = null
            binding.playerView.isVisible = false
            binding.vlcLayout.isVisible = true
            vm.vlc?.attachViews(binding.vlcLayout, subtitlesEnabled = true)
        } else {
            binding.vlcLayout.isVisible = false
            binding.playerView.isVisible = true
            binding.playerView.player = vm.player
            binding.playerView.useController = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Обновляем данные только если intent несёт богатые метаданные.
        // Lampa также посылает стандартный external-player intent (без них)
        // — он приходит в onNewIntent() и не должен перезаписывать хорошие данные.
        val hasMeta = intent.hasExtra("lampa_data") ||
            intent.hasExtra("lampa_meta") ||
            (intent.getStringExtra("title")?.startsWith("lmpmeta://") == true) ||
            (intent.getStringExtra("android.intent.extra.TITLE")?.startsWith("lmpmeta://") == true)
        if (!hasMeta) return
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

    // ─── OSD button clicks ─────────────────────────────────────────

    private fun setupOsdClicks() {
        binding.btnPlayPause.setOnClickListener { vm.onKeyOk(); vm.showOsd() }
        binding.btnRewind.setOnClickListener { doSeek(false, fast = false); vm.showOsd() }
        binding.btnForward.setOnClickListener { doSeek(true, fast = false); vm.showOsd() }
        binding.btnPrev.setOnClickListener { vm.onPrevEpisode(); vm.showOsd() }
        binding.btnNext.setOnClickListener { vm.onNextEpisode(); vm.showOsd() }
        binding.btnSpeed.setOnClickListener { vm.cyclePlaybackSpeed(); vm.showOsd() }
        binding.btnAspect.setOnClickListener { vm.cycleScaleMode(); vm.showOsd() }
        binding.btnInfo.setOnClickListener { vm.toggleInfoOverlay() }
        binding.btnMarkIntro.setOnClickListener {
            vm.markIntro()
            Toast.makeText(this, getString(R.string.intro_marked, vm.positionMs() / 1000f), Toast.LENGTH_SHORT).show()
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
                binding.btnSpeed.text = formatSpeed(s.playbackSpeed)
                binding.btnAspect.text = when (s.scaleMode) {
                    com.lampplayer.tv.player.VideoScaleMode.FIT -> "FIT"
                    com.lampplayer.tv.player.VideoScaleMode.FILL -> "FILL"
                    com.lampplayer.tv.player.VideoScaleMode.ZOOM -> "ZOOM"
                }
                binding.playerView.resizeMode = when (s.scaleMode) {
                    com.lampplayer.tv.player.VideoScaleMode.FILL ->
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    com.lampplayer.tv.player.VideoScaleMode.ZOOM ->
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }

                // OSD
                val osdWasHidden = !binding.osdContainer.isVisible
                binding.osdContainer.isVisible = s.osdVisible
                if (s.osdVisible && osdWasHidden && !osdFromSeek) {
                    binding.btnPlayPause.requestFocus()
                }

                // Overlays
                binding.infoOverlay.isVisible = s.infoOverlayVisible
                if (s.infoOverlayVisible) applyInfoOverlay(s)

                binding.tracksOverlay.isVisible = s.tracksOverlayVisible
                if (s.tracksOverlayVisible) applyTracksOverlay(s)

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
        // AUTO fallback handed playback to libVLC — rebind the render surface.
        lifecycleScope.launch { vm.engineSwitched.collect { bindEngineSurface() } }

        // Progress polling
        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                val pos = vm.positionMs()
                val dur = vm.durationMs()
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

    private fun applyInfoOverlay(s: PlayerUiState) {
        val meta = s.metadata
        binding.overlayMetaSection.isVisible = meta != null || s.title.isNotEmpty()
        binding.tvOverlayMetaTitle.text = meta?.title ?: s.title
        binding.tvOverlayMetaInfo.text = meta?.info ?: ""
        binding.tvOverlayMetaOverview.text = meta?.overview ?: ""
        if (!meta?.posterUrl.isNullOrEmpty()) {
            Glide.with(this).load(meta!!.posterUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivOverlayPoster)
        }

        // EPG
        val epgText = buildString {
            s.card?.epgTitle?.let { append(it) }
            s.card?.epgStart?.let { if (isNotEmpty()) append("\n"); append(it) }
            s.card?.epgEnd?.let { append(" — $it") }
        }
        binding.tvOverlayEpg.text = epgText
        binding.tvOverlayEpg.isVisible = epgText.isNotBlank()

        // Video format
        binding.tvOverlayVideoInfo.text = s.videoInfo
        binding.tvOverlayVideoInfo.isVisible = s.videoInfo.isNotEmpty()

        // Episodes or EPG content on the right
        val hasEpisodes = s.episodes.size > 1
        binding.rvInfoList.isVisible = hasEpisodes
        binding.tvEpgContent.isVisible = !hasEpisodes && s.card?.epgTitle != null

        if (hasEpisodes) {
            episodeAdapter.setItems(s.episodes, s.currentEpisodeIndex)
            binding.rvInfoList.scrollToPosition(s.currentEpisodeIndex)
        } else {
            binding.tvEpgContent.text = epgText
        }
    }

    private fun applyTracksOverlay(s: PlayerUiState) {
        audioAdapter.setItems(s.audioTracks, s.selectedAudioIndex)
        subtitleAdapter.setItems(s.subtitleTracks, s.selectedSubtitleIndex)
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

        // Diagnostic overlay: any key dismisses it first.
        if (debugVisible) {
            binding.intentDebug.isVisible = false
            debugVisible = false
            if (keyCode == KeyEvent.KEYCODE_BACK) return true
        }

        // ── Tracks overlay (↑): BACK закрывает ────────────────────
        if (s.tracksOverlayVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_UP -> { vm.hideTracksOverlay(); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // ── Info overlay (↓↓): BACK/↑ закрывает ──────────────────
        if (s.infoOverlayVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_UP -> { vm.hideInfoOverlay(); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // ── Медиа-кнопки — всегда обрабатываем ───────────────────
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
                KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(true, false); true }
                // ↑ → попап аудио/субтитров
                KeyEvent.KEYCODE_DPAD_UP -> {
                    osdFromSeek = false; vm.toggleTracksOverlay(); true
                }
                // ↓ → инфо-оверлей
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    vm.toggleInfoOverlay(); true
                }
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_MENU -> { vm.toggleInfoOverlay(); true }
                // ← / → — перемотка если OSD открылся от перемотки; иначе навигация
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (osdFromSeek) { doSeek(false); vm.showOsd(); true }
                    else super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (osdFromSeek) { doSeek(true); vm.showOsd(); true }
                    else super.onKeyDown(keyCode, event)
                }
                // OK в режиме перемотки — переходим к навигации по кнопкам
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (osdFromSeek) {
                        osdFromSeek = false; binding.btnPlayPause.requestFocus(); true
                    } else super.onKeyDown(keyCode, event)
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // ── OSD скрыт ─────────────────────────────────────────────
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
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> { osdFromSeek = false; vm.showOsd(); true }
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU -> { vm.toggleInfoOverlay(); true }
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
        val dur = vm.durationMs().takeIf { it > 0 } ?: return
        val stepMs = when {
            fast || repeatCount > 8 -> 30_000L
            repeatCount > 3 -> 15_000L
            else -> 10_000L
        }
        if (seekTargetMs < 0) seekTargetMs = vm.positionMs()
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
            vm.seekToMs(seekTargetMs)
            seekTargetMs = -1L
            delay(1500)
            binding.seekPreviewContainer.isVisible = false
            binding.seekIndicator.isVisible = false
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private fun showExitDialog() {
        vm.pausePlayback()
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_dialog_title)
            .setMessage(R.string.exit_dialog_message)
            .setPositiveButton(R.string.action_exit) { _, _ -> finishWithResult() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> vm.resumePlayback() }
            .setOnCancelListener { vm.resumePlayback() }
            .show()
    }

    private fun finishWithResult(completed: Boolean = false) {
        val (posMs, durMs, percent) = vm.buildResultExtras()
        val done = completed || percent >= 90
        setResult(RESULT_OK, Intent().apply {
            // Lampa / generic contract
            putExtra("position", posMs)
            putExtra("duration", durMs)
            putExtra("watched_percent", percent)
            putExtra("completed", done)
            putExtra("end_by", if (done) "playback_completion" else "user")
            // VLC-style mirrors for broader compatibility
            putExtra("extra_position", posMs)
            putExtra("extra_duration", durMs)
        })
        finish()
    }

    override fun onStop() { super.onStop(); vm.pausePlayback() }

    private fun formatSpeed(rate: Float): String {
        val s = if (rate % 1f == 0f) rate.toInt().toString() else rate.toString().trimEnd('0').trimEnd('.')
        return "$s×"
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
