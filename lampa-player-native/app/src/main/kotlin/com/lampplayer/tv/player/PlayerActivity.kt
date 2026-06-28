package com.lampplayer.tv.player

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
        onSelected = { item, _ -> vm.selectEpisode(item) },
        logoOf = { it.logoUrl },
    )
    // Rich TMDB episode list; plays the episode when a playable URL is available.
    private val episodeRowAdapter = EpisodeRowAdapter(
        onSelected = { row ->
            if (row.url != null) vm.playEpisodeRow(row)
            else Toast.makeText(this, "Серия недоступна для прямого запуска", Toast.LENGTH_SHORT).show()
        }
    )
    private val audioAdapter = InfoListAdapter<String>(
        labelOf = { it },
        onSelected = { _, pos -> vm.selectAudio(pos) }
    )
    private val subtitleAdapter = InfoListAdapter<String>(
        labelOf = { it },
        onSelected = { _, pos -> vm.selectSubtitle(pos) }
    )
    private val castAdapter = CastAdapter()

    // Seek preview state
    private var seekTargetMs = -1L
    private var seekDebounceJob: Job? = null

    // Turbo rewind
    private var turboJob: Job? = null
    private var turboSpeed = 1

    // Netflix-style OSD focus model: true = the scrubber owns ←/→ (seek);
    // false = focus is on the button row (←/→ move between buttons).
    private var scrubberFocused = true
    // Drill-down: when the info overlay is open, ↓ moves focus into the episodes list.
    private var episodesFocused = false
    // Info overlay: cast/crew block revealed (and scrolled) with ↓ before episodes.
    private var detailsExpanded = false
    private var tracksWasVisible = false
    private var infoWasVisible = false

    // IPTV channel card (slides up on switch) + which channel it last showed.
    private var lastChannelKey: String? = null
    private var channelCardJob: Job? = null

    // IPTV channel-number zapping (type digits on the remote → jump to channel N).
    private var zapBuffer = ""
    private var zapJob: Job? = null

    // Long-press OK → mark "intro ends here" (no menu, no seek conflict).
    private var okLongFired = false

    // Skip-intro pill: appears, then fades to an unobtrusive hint; OK skips it.
    private var skipShown = false
    private var skipFadeJob: Job? = null
    private val skipHintAlpha = 0.32f

    // True while shown as a Picture-in-Picture window (suppress all on-screen controls).
    private var inPip = false

    // Seek-bar-only mode: ←/→ shows just the progress scrubber, not the full button row.
    private var seekBarOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val (url, card) = IntentParser.parse(intent) ?: run {
            Toast.makeText(this, getString(R.string.error_no_url), Toast.LENGTH_LONG).show()
            finish(); return
        }

        val dm = resources.displayMetrics
        vm.setDisplayAspect("${dm.widthPixels}:${dm.heightPixels}")

        vm.initPlayer(this, url, card)
        bindEngineSurface()

        setupLists()
        setupOsdClicks()
        observeState()
        maybeCheckUpdate()
    }

    /** Once-a-day background update check → unobtrusive toast (install from Settings). */
    private fun maybeCheckUpdate() {
        val prefs = getSharedPreferences("update", MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last", 0) < 24 * 3600_000L) return
        prefs.edit().putLong("last", System.currentTimeMillis()).apply()
        lifecycleScope.launch {
            val info = com.lampplayer.tv.update.UpdateManager.check(com.lampplayer.tv.BuildConfig.VERSION_CODE)
                ?: return@launch
            Toast.makeText(
                this@PlayerActivity,
                "Доступно обновление LampPlayer ${info.versionName} — Настройки → Обновить",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private var debugVisible = false
    private var intentDebugDone = false

    /**
     * Show the full intent dump on screen (only with Diagnostics on). Called once per
     * launch from the settings-aware state collector, so the diag flag is loaded by then.
     */
    private fun showIntentDebug(card: com.lampplayer.tv.domain.model.CardMeta) {
        val noMeta = card.tmdbId == null && card.overview.isNullOrBlank() &&
            card.posterUrl.isNullOrBlank() && card.backdropUrl.isNullOrBlank()
        val dump = buildString {
            append(if (noMeta) "⚠ Метаданные не пришли\n" else "✓ Метаданные есть\n")
            append(IntentParser.debugDump(intent))
            append("\n— разобрано —\n")
            append("title: ").append(card.title.take(60)).append('\n')
            append("tmdb: ").append(card.tmdbId ?: "—")
            append("  poster: ").append(if (card.posterUrl != null) "да" else "нет")
            append("  iptv: ").append(if (card.iptv) "да" else "нет")
            card.debugInfo?.let { append("\n\nIPTV dump:\n").append(it.take(2000)) }
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
        val (url, card) = IntentParser.parse(intent) ?: return

        // Same media → only enrich metadata if a richer envelope arrived; don't
        // disrupt playback (Lampa may fire duplicate/secondary intents).
        if (url == vm.currentMediaUrl) {
            val hasMeta = intent.hasExtra("lampa_data") ||
                intent.hasExtra("lampa_meta") ||
                (intent.getStringExtra("title")?.startsWith("lmpmeta://") == true) ||
                (intent.getStringExtra("android.intent.extra.TITLE")?.startsWith("lmpmeta://") == true)
            if (hasMeta) vm.updateCardMeta(card)
            return
        }

        // Different media → full clean restart (singleTask reuses this activity).
        setIntent(intent)
        vm.resetForNewMedia()
        intentDebugDone = false
        vm.initPlayer(this, url, card)
        bindEngineSurface()
    }

    // ─── List setup ────────────────────────────────────────────────

    private fun setupLists() {
        binding.rvInfoList.layoutManager = LinearLayoutManager(this)
        binding.rvInfoList.adapter = episodeAdapter

        binding.rvAudioList.layoutManager = LinearLayoutManager(this)
        binding.rvAudioList.adapter = audioAdapter

        binding.rvSubtitleList.layoutManager = LinearLayoutManager(this)
        binding.rvSubtitleList.adapter = subtitleAdapter

        binding.rvCast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCast.adapter = castAdapter
    }

    // ─── OSD button clicks ─────────────────────────────────────────

    private fun setupOsdClicks() {
        binding.scrubber.setOnClickListener { vm.onKeyOk(); vm.showOsd() }
        binding.btnPlayPause.setOnClickListener { vm.onKeyOk(); vm.showOsd() }
        binding.btnRewind.setOnClickListener { doSeek(false, fast = false); vm.showOsd() }
        binding.btnForward.setOnClickListener { doSeek(true, fast = false); vm.showOsd() }
        binding.btnPrev.setOnClickListener { vm.onPrevEpisode(); vm.showOsd() }
        binding.btnNext.setOnClickListener { vm.onNextEpisode(); vm.showOsd() }
        binding.btnSpeed.setOnClickListener { vm.cyclePlaybackSpeed(); vm.showOsd() }
        binding.btnAspect.setOnClickListener { vm.cycleScaleMode(); vm.showOsd() }
        binding.btnVolume.setOnClickListener { vm.cycleVolumeBoost(); vm.showOsd() }
        binding.btnInfo.setOnClickListener { vm.toggleInfoOverlay() }
        binding.btnNight.setOnClickListener { vm.toggleNightMode(); vm.showOsd() }
        binding.btnSkipIntro.setOnClickListener { vm.skipIntro() }
        binding.btnRetry.setOnClickListener { vm.retryPlayback() }
        binding.btnExit.setOnClickListener { finishWithResult() }
        binding.btnCancelAutoNext.setOnClickListener { vm.cancelAutoNext() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.metadataOverlay.setOnClickListener { hideMetadataOverlay() }

        // Frameless TV focus: grow + lift the focused control instead of a boxed border.
        listOf(
            binding.btnInfo, binding.btnNight, binding.btnPrev, binding.btnRewind,
            binding.btnPlayPause, binding.btnForward, binding.btnNext,
            binding.btnSpeed, binding.btnAspect, binding.btnVolume, binding.btnSettings,
        ).forEach { v ->
            v.setOnFocusChangeListener { view, focused ->
                val s = if (focused) 1.18f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(140).start()
                view.elevation = if (focused) 12f else 0f
            }
        }
    }

    // ─── State observation ─────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            vm.uiState.collectLatest { s ->
                if (inPip) return@collectLatest   // no controls while in a PiP window
                // Intent diagnostic — once per launch, after settings (diag flag) loaded.
                if (!intentDebugDone && s.card != null && s.settings.diag) {
                    intentDebugDone = true
                    showIntentDebug(s.card)
                }
                // Top bar
                binding.tvTitle.text = s.title
                binding.tvQuality.isVisible = s.quality.isNotEmpty()
                binding.tvQuality.text = s.quality
                binding.tvTranslator.isVisible = s.translator.isNotEmpty()
                binding.tvTranslator.text = s.translator
                binding.btnPlayPause.setImageResource(
                    if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                binding.tvEpisodeBadge.isVisible = s.episodes.size > 1
                binding.tvEpisodeBadge.text = "${s.currentEpisodeIndex + 1}/${s.episodes.size}"
                binding.btnSpeed.text = formatSpeed(s.playbackSpeed)
                binding.btnVolume.text = "${s.volumeBoost}%"
                // Night-mode button lit when active.
                binding.btnNight.setColorFilter(
                    if (s.settings.nightMode) ContextCompat.getColor(this@PlayerActivity, R.color.accent_primary)
                    else ContextCompat.getColor(this@PlayerActivity, R.color.text_primary)
                )
                binding.btnAspect.text = when (s.scaleMode) {
                    com.lampplayer.tv.player.VideoScaleMode.AUTO -> "AUTO"
                    com.lampplayer.tv.player.VideoScaleMode.FIT -> "FIT"
                    com.lampplayer.tv.player.VideoScaleMode.FILL -> "FILL"
                    com.lampplayer.tv.player.VideoScaleMode.ZOOM -> "ZOOM"
                }
                binding.playerView.resizeMode = when (s.scaleMode) {
                    com.lampplayer.tv.player.VideoScaleMode.FILL ->
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    com.lampplayer.tv.player.VideoScaleMode.ZOOM ->
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    com.lampplayer.tv.player.VideoScaleMode.AUTO -> autoResizeMode(s.videoAspect)
                    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }

                // AFR: switch the panel to the content frame rate once it's known.
                if (s.settings.afr && s.videoFps > 0f) applyAfr(s.videoFps)

                // IPTV vs VOD bottom bar: live channels have no timeline to scrub.
                val isIptv = s.card?.iptv == true
                binding.vodProgressGroup.isVisible = !isIptv
                binding.liveGroup.isVisible = isIptv
                if (isIptv) {
                    binding.tvClock.text = formatClock()
                    binding.tvLiveNow.text = s.epgText.lineSequence().firstOrNull()
                        ?.removePrefix("Сейчас")?.trim().orEmpty()
                    // Channel switched (or just opened) → slide the channel card up.
                    val key = s.title + "|" + s.epgText.take(40)
                    if (key != lastChannelKey) { lastChannelKey = key; showChannelCard(s) }
                } else if (lastChannelKey != null) {
                    lastChannelKey = null
                    binding.channelCard.isVisible = false
                }

                // OSD — default focus on the scrubber (Netflix-style)
                val osdWasHidden = !binding.osdContainer.isVisible
                // Hide the OSD while an overlay is up — the compact info panel sits at the
                // bottom and would otherwise overlap the controls.
                val osdShown = s.osdVisible && !s.infoOverlayVisible && !s.tracksOverlayVisible
                binding.osdContainer.isVisible = osdShown
                if (!s.osdVisible) { scrubberFocused = true; seekBarOnly = false }   // reset when hidden
                // Seek-only: keep just the scrubber; hide the top bar + button row.
                binding.topBar.isVisible = osdShown && !seekBarOnly
                binding.buttonRow.isVisible = osdShown && !seekBarOnly
                if (osdShown && osdWasHidden && scrubberFocused) {
                    binding.scrubber.requestFocus()
                }

                // Overlays — fade/slide in for a less abrupt feel
                if (s.infoOverlayVisible) applyInfoOverlay(s)
                setOverlayVisible(binding.infoOverlay, s.infoOverlayVisible, slideY = 28f)
                if (s.tracksOverlayVisible) applyTracksOverlay(s)
                setOverlayVisible(binding.tracksOverlay, s.tracksOverlayVisible, slideX = 90f)

                if (!s.infoOverlayVisible) { episodesFocused = false; detailsExpanded = false }

                // Trap focus inside an open overlay (#1): block the OSD's focusables
                // so keys stay within the overlay, not the controls behind it.
                val overlayUp = s.tracksOverlayVisible || s.infoOverlayVisible
                val overlayWasUp = tracksWasVisible || infoWasVisible
                if (overlayUp != overlayWasUp) {
                    binding.osdContainer.descendantFocusability =
                        if (overlayUp) android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        else android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                    if (!overlayUp && s.osdVisible) {
                        (if (scrubberFocused) binding.scrubber else binding.btnPlayPause).requestFocus()
                    }
                }
                if (s.tracksOverlayVisible && !tracksWasVisible) binding.rvAudioList.requestFocus()
                if (s.infoOverlayVisible && !infoWasVisible) { episodesFocused = false; detailsExpanded = false }  // open at description
                tracksWasVisible = s.tracksOverlayVisible
                infoWasVisible = s.infoOverlayVisible

                binding.progressBuffering.isVisible = (s.isLoading || s.reconnecting) && !s.isPlaying && !s.hasError
                binding.tvReconnect.isVisible = s.reconnecting && !s.isPlaying
                binding.errorContainer.isVisible = s.hasError
                binding.tvErrorMessage.text = s.errorMessage
                updateSkipIntro(s.showSkipIntro && !s.hasError && !inPip)
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
                        applyPosterAccent(meta.posterUrl)
                    }
                }
            }
        }

        lifecycleScope.launch { vm.navigateToNext.collect { finishWithResult(completed = true) } }
        lifecycleScope.launch { vm.showExitDialog.collect { promptExit() } }
        lifecycleScope.launch { vm.resumedFromMs.collect { showResumeCard(it) } }
        lifecycleScope.launch {
            vm.weakStreamHint.collect {
                Toast.makeText(
                    this@PlayerActivity,
                    "Слабый поток: попробуйте увеличить буфер в Настройках или выбрать другое качество в Lampa",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        // AUTO fallback handed playback to libVLC — rebind the render surface.
        lifecycleScope.launch { vm.engineSwitched.collect { bindEngineSurface() } }

        // Progress polling
        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                if (vm.uiState.value.card?.iptv == true) {
                    binding.tvClock.text = formatClock()
                    continue
                }
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

    // Poster-driven accent: tint a few runtime-tintable views to match the artwork.
    private var lastAccentUrl: String? = null

    private fun applyPosterAccent(posterUrl: String?) {
        if (posterUrl.isNullOrEmpty() || posterUrl == lastAccentUrl) return
        lastAccentUrl = posterUrl
        Glide.with(this).asBitmap().load(posterUrl).into(
            object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                override fun onResourceReady(
                    res: android.graphics.Bitmap,
                    t: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?,
                ) {
                    androidx.palette.graphics.Palette.from(res).clearFilters().generate { p ->
                        val base = ContextCompat.getColor(this@PlayerActivity, R.color.accent_primary)
                        val accent = p?.let {
                            it.getVibrantColor(it.getLightVibrantColor(it.getDominantColor(base)))
                        } ?: base
                        applyAccentColor(accent)
                    }
                }
                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
            }
        )
    }

    private fun applyAccentColor(color: Int) {
        val tint = android.content.res.ColorStateList.valueOf(color)
        binding.progressBar.progressTintList = tint
        binding.tvMetaInfo.setTextColor(color)
        binding.tvOverlayMetaInfo.setTextColor(color)
        binding.tvEpisodesHeader.setTextColor(color)
    }

    /** Fade + slide an overlay in/out (slideX/Y is the off-screen offset it animates from/to). */
    private fun setOverlayVisible(view: android.view.View, visible: Boolean, slideX: Float = 0f, slideY: Float = 0f) {
        if (visible) {
            if (!view.isVisible) {
                view.alpha = 0f; view.translationX = slideX; view.translationY = slideY
                view.isVisible = true
            }
            view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(180).start()
        } else if (view.isVisible) {
            view.animate().alpha(0f).translationX(slideX).translationY(slideY).setDuration(150)
                .withEndAction { view.isVisible = false; view.alpha = 1f }.start()
        }
    }

    private fun formatClock(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    // Resume card: visible briefly after auto-resume; ← while shown restarts from zero.
    private var resumeCardJob: Job? = null

    private fun showResumeCard(fromMs: Long) {
        binding.tvResumeText.text = "▶  Продолжаем с ${formatTime(fromMs)}"
        binding.resumeCard.alpha = 0f
        binding.resumeCard.isVisible = true
        binding.resumeCard.animate().alpha(1f).setDuration(200).start()
        resumeCardJob?.cancel()
        resumeCardJob = lifecycleScope.launch {
            delay(6000)
            binding.resumeCard.animate().alpha(0f).setDuration(200)
                .withEndAction { binding.resumeCard.isVisible = false }.start()
        }
    }

    private fun hideResumeCard() {
        resumeCardJob?.cancel()
        binding.resumeCard.isVisible = false
    }

    /** Accumulate a typed channel number, show it, then commit after a short pause. */
    private fun onZapDigit(digit: Int) {
        if (zapBuffer.length >= 4) zapBuffer = ""
        zapBuffer += digit.toString()
        binding.tvZapNumber.text = zapBuffer
        binding.tvZapNumber.isVisible = true
        zapJob?.cancel()
        zapJob = lifecycleScope.launch {
            delay(1500)
            val n = zapBuffer.toIntOrNull()
            zapBuffer = ""
            binding.tvZapNumber.isVisible = false
            if (n != null) vm.zapToChannel(n)
        }
    }

    /** IPTV: slide a compact channel card up from the bottom; auto-hide after 4s. */
    private fun showChannelCard(s: PlayerUiState) {
        val lines = s.epgText.lines().filter { it.isNotBlank() }
        val now = lines.firstOrNull { it.startsWith("Сейчас") }?.removePrefix("Сейчас")?.trim()
        val next = lines.firstOrNull { it.startsWith("Далее") }?.trim()
        binding.tvCardChannel.text = s.title
        binding.tvCardNow.text = now?.let { "Сейчас  $it" } ?: "Нет программы"
        binding.tvCardNext.isVisible = next != null
        binding.tvCardNext.text = next.orEmpty()
        val logo = s.episodes.getOrNull(s.currentEpisodeIndex)?.logoUrl
        if (logo.isNullOrBlank()) {
            binding.ivCardLogo.isVisible = false
        } else {
            binding.ivCardLogo.isVisible = true
            Glide.with(this).load(logo).into(binding.ivCardLogo)
        }

        val card = binding.channelCard
        channelCardJob?.cancel()
        if (!card.isVisible) {
            card.translationY = 40f
            card.alpha = 0f
            card.isVisible = true
        }
        card.animate().translationY(0f).alpha(1f).setDuration(200).start()
        channelCardJob = lifecycleScope.launch {
            delay(4000)
            card.animate().translationY(40f).alpha(0f).setDuration(200)
                .withEndAction { card.isVisible = false }.start()
        }
    }

    private fun applyInfoOverlay(s: PlayerUiState) {
        val isIptv = s.card?.iptv == true
        binding.tvEpisodesHeader.setText(if (isIptv) R.string.tab_channels else R.string.tab_episodes)
        val meta = s.metadata
        binding.overlayMetaSection.isVisible = meta != null || s.title.isNotEmpty()
        // For IPTV always show the live channel name (metadata can lag a channel switch).
        binding.tvOverlayMetaTitle.text = if (isIptv) s.title else (meta?.title ?: s.title)
        binding.tvOverlayMetaInfo.text = meta?.info ?: ""
        // EPG (IPTV) or overview (films/series) — whichever is present.
        binding.tvOverlayMetaOverview.text =
            if (s.epgText.isNotEmpty()) s.epgText else (meta?.overview ?: "")
        binding.ivOverlayPoster.isVisible = !isIptv
        if (!isIptv && !meta?.posterUrl.isNullOrEmpty()) {
            Glide.with(this).load(meta!!.posterUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivOverlayPoster)
        }

        // Extended details (cast photos + director/writers/genres) — revealed with ↓.
        val details = meta?.details.orEmpty()
        val cast = meta?.castMembers.orEmpty()
        castAdapter.setItems(cast)
        binding.tvOverlayDetails.text = details
        val hasExtra = (details.isNotEmpty() || cast.isNotEmpty()) && !isIptv
        binding.tvOverlayDetails.isVisible = details.isNotEmpty() && detailsExpanded
        binding.tvCastHeader.isVisible = cast.isNotEmpty() && detailsExpanded
        binding.rvCast.isVisible = cast.isNotEmpty() && detailsExpanded
        binding.tvOverlayMore.isVisible = hasExtra && !detailsExpanded

        // Legacy single-line EPG (from the card) — only when we have no full guide.
        val epgText = if (isIptv && s.epgText.isNotEmpty()) "" else buildString {
            s.card?.epgTitle?.let { append(it) }
            s.card?.epgStart?.let { if (isNotEmpty()) append("\n"); append(it) }
            s.card?.epgEnd?.let { append(" — $it") }
        }
        binding.tvOverlayEpg.text = epgText
        binding.tvOverlayEpg.isVisible = epgText.isNotBlank()

        // Video format
        binding.tvOverlayVideoInfo.text = s.videoInfo
        binding.tvOverlayVideoInfo.isVisible = s.videoInfo.isNotEmpty()

        // Episodes / EPG panel — shown only when there's something to list, so a
        // movie gets the full-width readable description instead of an empty pane.
        val hasTmdbEps = s.episodeRows.size > 1
        val hasBalancerEps = s.episodes.size > 1
        val hasEpisodes = hasTmdbEps || hasBalancerEps
        val hasEpg = !hasEpisodes && s.card?.epgTitle != null
        binding.episodesPanel.isVisible = hasEpisodes || hasEpg
        binding.rvInfoList.isVisible = hasEpisodes
        binding.tvEpgContent.isVisible = hasEpg

        when {
            hasTmdbEps -> {
                if (binding.rvInfoList.adapter !== episodeRowAdapter) binding.rvInfoList.adapter = episodeRowAdapter
                episodeRowAdapter.setItems(s.episodeRows)
                binding.rvInfoList.scrollToPosition(episodeRowAdapter.currentIndex())
            }
            hasBalancerEps -> {
                if (binding.rvInfoList.adapter !== episodeAdapter) binding.rvInfoList.adapter = episodeAdapter
                episodeAdapter.setItems(s.episodes, s.currentEpisodeIndex)
                binding.rvInfoList.scrollToPosition(s.currentEpisodeIndex)
            }
            hasEpg -> binding.tvEpgContent.text = epgText
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

    /** AUTO sizing: zoom to fill when the aspect mismatch (black bars) is small, else fit. */
    private fun autoResizeMode(videoAspect: Float): Int {
        val fit = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        val zoom = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        if (videoAspect <= 0f) return fit
        val dm = resources.displayMetrics
        val screen = dm.widthPixels.toFloat() / dm.heightPixels.coerceAtLeast(1)
        val ratio = kotlin.math.max(videoAspect / screen, screen / videoAspect)
        // Fill (crop) up to a ~40% aspect mismatch — covers 4:3 and CinemaScope
        // 2.0–2.4:1 on a 16:9 panel (crops the edges); only пропускаем экстремальные
        // случаи (≈2.5:1+), где обрезка съела бы слишком много кадра.
        return if (ratio <= 1.40f) zoom else fit
    }

    /** Index of the currently-playing item in the episode list (for scroll/focus). */
    private fun currentEpisodeListIndex(): Int =
        if (binding.rvInfoList.adapter === episodeRowAdapter) episodeRowAdapter.currentIndex()
        else vm.uiState.value.currentEpisodeIndex

    private fun focusCurrentEpisode() {
        val idx = currentEpisodeListIndex()
        (binding.rvInfoList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 0)
            ?: binding.rvInfoList.scrollToPosition(idx)
        binding.rvInfoList.post {
            (binding.rvInfoList.findViewHolderForAdapterPosition(idx)?.itemView
                ?: binding.rvInfoList).requestFocus()
        }
    }

    /** Reveal/hide the cast row + crew block in the info panel (and the ▼ hint). */
    private fun setDetailsExpanded(expanded: Boolean) {
        detailsExpanded = expanded
        val hasDetails = binding.tvOverlayDetails.text.isNotEmpty()
        val hasCast = castAdapter.itemCount > 0
        binding.tvOverlayDetails.isVisible = expanded && hasDetails
        binding.tvCastHeader.isVisible = expanded && hasCast
        binding.rvCast.isVisible = expanded && hasCast
        binding.tvOverlayMore.isVisible = !expanded && (hasDetails || hasCast)
        if (expanded && hasCast) {
            binding.rvCast.post {
                (binding.rvCast.findViewHolderForAdapterPosition(0)?.itemView
                    ?: binding.rvCast).requestFocus()
            }
        } else if (!expanded) {
            binding.svOverlayMeta.smoothScrollTo(0, 0)
        }
    }

    private fun enterButtonZone() {
        binding.osdContainer.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        expandOsd()
        scrubberFocused = false
        binding.btnPlayPause.requestFocus()
        vm.showOsd()
    }

    /** Leave seek-only mode: reveal the top bar + button row (full controls). */
    private fun expandOsd() {
        seekBarOnly = false
        binding.topBar.isVisible = true
        binding.buttonRow.isVisible = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = vm.uiState.value
        val osdVisible = s.osdVisible

        // A faded skip pill brightens on any remote activity (it's "dismissable").
        restoreSkipHint()

        // Resume card: ← restarts from the beginning while it's on screen.
        if (binding.resumeCard.isVisible) {
            hideResumeCard()
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                vm.seekToMs(0)
                return true
            }
            // any other key: just dismiss the card and handle the key normally
        }

        // Diagnostic overlay: any key dismisses it first.
        if (debugVisible) {
            binding.intentDebug.isVisible = false
            debugVisible = false
            if (keyCode == KeyEvent.KEYCODE_BACK) return true
        }

        // ── Tracks overlay (↑): фокус заперт внутри окна, BACK закрывает ──
        if (s.tracksOverlayVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { vm.hideTracksOverlay(); true }
                // ↑/↓ scroll within a list, ←/→ switch between audio/subtitle
                // columns — all handled by the (trapped) Android focus.
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // ── Info overlay: описание → детали (актёры/создатели) → серии ───────
        if (s.infoOverlayVisible) {
            val hasEpisodes = s.episodes.size > 1 || s.episodeRows.size > 1
            val hasExtra = binding.tvOverlayDetails.text.isNotEmpty() || castAdapter.itemCount > 0
            val step = (180 * resources.displayMetrics.density).toInt()
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> when {
                    episodesFocused -> super.onKeyDown(keyCode, event)     // scroll the episode list
                    hasExtra && !detailsExpanded -> { setDetailsExpanded(true); true }
                    binding.svOverlayMeta.canScrollVertically(1) -> {
                        binding.svOverlayMeta.smoothScrollBy(0, step); true   // reveal more cast/crew
                    }
                    hasEpisodes -> { episodesFocused = true; focusCurrentEpisode(); true }
                    else -> true
                }
                KeyEvent.KEYCODE_DPAD_UP -> when {
                    episodesFocused -> super.onKeyDown(keyCode, event)
                    binding.svOverlayMeta.canScrollVertically(-1) -> {
                        binding.svOverlayMeta.smoothScrollBy(0, -step); true
                    }
                    detailsExpanded -> { setDetailsExpanded(false); true }
                    else -> { vm.hideInfoOverlay(); enterButtonZone(); true }
                }
                KeyEvent.KEYCODE_BACK -> when {
                    episodesFocused -> { episodesFocused = false; true }
                    detailsExpanded -> { setDetailsExpanded(false); true }
                    else -> { vm.hideInfoOverlay(); enterButtonZone(); true }
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // ── IPTV: цифры на пульте — переход к каналу по номеру ──────
        if (s.card?.iptv == true) {
            val digit = when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            if (digit >= 0) { onZapDigit(digit); return true }
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

        // ── OK: short = play/pause/open OSD; LONG = mark intro end ──
        // (Skip the button row — there OK must activate the focused button.)
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            !(osdVisible && !scrubberFocused)) {
            if (event.repeatCount == 0) { okLongFired = false; event.startTracking() }
            return true   // short action runs on key-up; long-press handled in onKeyLongPress
        }

        // ── OSD visible ───────────────────────────────────────────
        if (osdVisible) {
            if (scrubberFocused) {
                // Scrubber owns ←/→ (always seek) and OK (play/pause).
                return when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { doSeek(false, repeatCount = event.repeatCount); vm.showOsd(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { doSeek(true, repeatCount = event.repeatCount); vm.showOsd(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> { vm.onKeyOk(); vm.showOsd(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        expandOsd(); scrubberFocused = false; binding.btnPlayPause.requestFocus(); vm.showOsd(); true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> { vm.toggleTracksOverlay(); true }
                    KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> { vm.toggleInfoOverlay(); true }
                    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { vm.onKeyPageUp(); vm.showOsd(); true }
                    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.onKeyPageDown(); vm.showOsd(); true }
                    KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(true, false); true }
                    else -> super.onKeyDown(keyCode, event)
                }
            } else {
                // Button row: ←/→/OK handled by the focused button (Android focus).
                return when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_BACK -> {
                        scrubberFocused = true; binding.scrubber.requestFocus(); vm.showOsd(); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> { vm.toggleInfoOverlay(); true }
                    else -> { vm.showOsd(); super.onKeyDown(keyCode, event) }
                }
            }
        }

        // ── OSD скрыт: любое действие открывает OSD на полосе ──────
        // IPTV: перемотка живого эфира бессмысленна — ВПРАВО открывает программу.
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && s.card?.iptv == true) {
            vm.toggleInfoOverlay(); return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBarOnly = true; scrubberFocused = true; vm.showOsd(); doSeek(false, repeatCount = event.repeatCount); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBarOnly = true; scrubberFocused = true; vm.showOsd(); doSeek(true, repeatCount = event.repeatCount); true
            }
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> { vm.onKeyPageUp(); seekBarOnly = true; scrubberFocused = true; vm.showOsd(); true }
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.onKeyPageDown(); seekBarOnly = true; scrubberFocused = true; vm.showOsd(); true }
            KeyEvent.KEYCODE_DPAD_UP -> { vm.toggleTracksOverlay(); true }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> { seekBarOnly = false; scrubberFocused = true; vm.showOsd(); true }
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU -> { vm.toggleInfoOverlay(); true }
            KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(false, false); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            okLongFired = true
            if (vm.uiState.value.card?.iptv != true) {
                val pos = vm.positionMs()
                val credits = vm.markByLongPress(pos)
                val what = if (credits) "Начало титров отмечено" else "Конец заставки отмечен"
                showCenterToast("✓ $what · ${formatTime(pos)}")
            }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    /**
     * Skip-intro pill: slides/fades in at full strength, then settles to a faint
     * hint so it doesn't block the picture for the whole intro. Any remote key
     * (see restoreSkipHint) brings it back to full; OK skips.
     */
    private fun updateSkipIntro(show: Boolean) {
        val v = binding.btnSkipIntro
        if (show == skipShown) return
        skipShown = show
        skipFadeJob?.cancel()
        if (show) {
            v.alpha = 0f
            v.translationX = dp(36f)
            v.isVisible = true
            v.animate().alpha(1f).translationX(0f).setDuration(260).start()
            scheduleSkipFade()
        } else {
            v.animate().alpha(0f).translationX(dp(36f)).setDuration(200)
                .withEndAction { v.isVisible = false }.start()
        }
    }

    /** After a few seconds of no input, dim the pill to a hint. */
    private fun scheduleSkipFade() {
        skipFadeJob?.cancel()
        skipFadeJob = lifecycleScope.launch {
            delay(4500)
            if (skipShown) binding.btnSkipIntro.animate().alpha(skipHintAlpha).setDuration(500).start()
        }
    }

    /** Bring the dimmed pill back to full on any interaction while it's up. */
    private fun restoreSkipHint() {
        if (!skipShown) return
        if (binding.btnSkipIntro.alpha < 0.99f)
            binding.btnSkipIntro.animate().alpha(1f).setDuration(150).start()
        scheduleSkipFade()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private var centerToastJob: Job? = null
    /** Prominent centered confirmation that's hard to miss on a TV. */
    private fun showCenterToast(text: String) {
        binding.tvCenterToast.text = text
        binding.tvCenterToast.alpha = 0f
        binding.tvCenterToast.isVisible = true
        binding.tvCenterToast.animate().alpha(1f).setDuration(150).start()
        centerToastJob?.cancel()
        centerToastJob = lifecycleScope.launch {
            delay(2200)
            binding.tvCenterToast.animate().alpha(0f).setDuration(200)
                .withEndAction { binding.tvCenterToast.isVisible = false }.start()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            turboJob?.cancel(); turboSpeed = 1
        }
        // OK released: if it wasn't a long-press (intro mark), do the short action now.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (okLongFired) { okLongFired = false; return true }
            val s = vm.uiState.value
            if (s.osdVisible && !scrubberFocused) return super.onKeyUp(keyCode, event)  // button row
            if (s.infoOverlayVisible || s.tracksOverlayVisible) return super.onKeyUp(keyCode, event)
            // Pill is up and OSD hidden → OK is "skip intro", not "open scrubber".
            if (!s.osdVisible && s.showSkipIntro) { vm.skipIntro(); return true }
            if (s.osdVisible) { vm.onKeyOk(); vm.showOsd() }
            else { seekBarOnly = false; scrubberFocused = true; vm.showOsd() }
            return true
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

    // Double-tap BACK to exit: first press arms a brief window + hint toast,
    // a second press within it leaves the player; otherwise it disarms quietly.
    private var backExitArmed = false
    private var backExitJob: Job? = null

    private fun promptExit() {
        if (backExitArmed) { backExitJob?.cancel(); finishWithResult(); return }
        backExitArmed = true
        Toast.makeText(this, R.string.exit_press_again, Toast.LENGTH_SHORT).show()
        backExitJob = lifecycleScope.launch {
            delay(2000)
            backExitArmed = false
        }
    }

    /**
     * Hand the resume point back to Lampa. The Android wrappers read MX-Player-style
     * extras with getIntExtra(), so the MX keys MUST be Int (a Long silently reads as
     * the default = lost position). VLC-style mirrors stay Long per VLC's contract.
     */
    private fun finishWithResult(completed: Boolean = false) {
        val (posMs, durMs, percent) = vm.buildResultExtras()
        val done = completed || percent >= 90
        setResult(RESULT_OK, Intent().apply {
            // MX / Just Player convention (lampa wrappers parse these as Int ms)
            putExtra("position", posMs.toInt())
            putExtra("duration", durMs.toInt())
            putExtra("end_by", if (done) "playback_completion" else "user")
            // VLC convention (Long ms)
            putExtra("extra_position", posMs)
            putExtra("extra_duration", durMs)
            // Generic flags some forks look at
            putExtra("watched_percent", percent)
            putExtra("completed", done)
            putExtra("ended", done)
        })
        finish()
    }

    // ─── AFR: match the display refresh rate to the content frame rate ─────
    private var lastAfrFps = 0f

    /**
     * Pick a display mode (same resolution as current) whose refresh rate is an
     * integer multiple of the content fps and switch to it. Prefers the exact rate
     * (24 for 24fps) over multiples (48). The system restores the default mode
     * when the window goes away. Causes a brief HDMI re-sync (~1s black) — that's
     * inherent to AFR.
     */
    private fun applyAfr(fps: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        if (fps < 10f || fps == lastAfrFps) return
        lastAfrFps = fps
        runCatching {
            val display = windowManager.defaultDisplay ?: return
            val active = display.mode
            val candidates = display.supportedModes
                .filter { it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight }
            // 29.97/23.976 ↔ 30/24: NTSC rates need a tolerant match against both bases.
            fun matches(refresh: Float, base: Float): Boolean {
                val k = (refresh / base).let { Math.round(it) }
                if (k < 1) return false
                return kotlin.math.abs(refresh - base * k) < 0.12f * k
            }
            val best = candidates
                .filter { matches(it.refreshRate, fps) }
                .minByOrNull { Math.round(it.refreshRate / fps) }   // exact rate first, then 2×…
                ?: return
            if (best.modeId == active.modeId) return
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    // ─── Picture-in-Picture ────────────────────────────────────────
    private val supportsPip: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Shrink to a floating window so the user can keep watching while using other apps. */
    private fun enterPip() {
        if (!supportsPip || vm.uiState.value.hasError) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val a = vm.uiState.value.videoAspect.takeIf { it > 0f } ?: (16f / 9f)
        val ratio = android.util.Rational((a * 1000).toInt().coerceIn(418, 2390), 1000)
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build()
            )
        }
    }

    // Pressing HOME while watching tucks the player into a PiP window (where supported).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.isPlayingNow()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        inPip = isInPip
        // Hide every control/overlay so the PiP window shows clean video.
        if (isInPip) {
            binding.osdContainer.isVisible = false
            binding.infoOverlay.isVisible = false
            binding.tracksOverlay.isVisible = false
            binding.channelCard.isVisible = false
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep playing in PiP; only pause when actually backgrounded.
        if (!(supportsPip && isInPictureInPictureMode)) vm.pausePlayback()
    }

    private fun formatSpeed(rate: Float): String {
        val s = if (rate % 1f == 0f) rate.toInt().toString() else rate.toString().trimEnd('0').trimEnd('.')
        return "$s×"
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
