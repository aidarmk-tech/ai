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
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.lampa.player.R
import com.lampa.player.databinding.ActivityPlayerBinding
import com.lampa.player.domain.model.EpisodeItem
import com.lampa.player.domain.model.InfoPanelTab
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

    private var longPressJob: kotlinx.coroutines.Job? = null
    private var longPressSpeed = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setupClickListeners()
        observeState()
    }

    private fun setupInfoPanel() {
        binding.rvInfoList.layoutManager = LinearLayoutManager(this)
        binding.rvInfoList.adapter = episodeAdapter

        binding.tabEpisodes.setOnClickListener { vm.setInfoPanelTab(InfoPanelTab.EPISODES) }
        binding.tabAudio.setOnClickListener { vm.setInfoPanelTab(InfoPanelTab.AUDIO) }
        binding.tabSubtitles.setOnClickListener { vm.setInfoPanelTab(InfoPanelTab.SUBTITLES) }
        binding.tabEpg.setOnClickListener { vm.setInfoPanelTab(InfoPanelTab.EPG) }
    }

    private fun setupClickListeners() {
        binding.btnPlayPause.setOnClickListener { vm.onKeyOk(); vm.showOsd() }
        binding.btnRewind.setOnClickListener { vm.onKeyLeft(); vm.showOsd() }
        binding.btnForward.setOnClickListener { vm.onKeyRight(); vm.showOsd() }
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
        binding.metadataOverlay.setOnClickListener {
            binding.metadataOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                binding.metadataOverlay.isVisible = false
                binding.metadataOverlay.alpha = 1f
            }.start()
        }
    }

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
                    if (s.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
                // Episode counter badge on info button
                binding.tvEpisodeBadge.isVisible = s.episodes.size > 1
                binding.tvEpisodeBadge.text = "${s.currentEpisodeIndex + 1}/${s.episodes.size}"

                // States
                binding.progressBuffering.isVisible = s.isLoading && !s.hasError
                binding.osdContainer.isVisible = s.osdVisible
                binding.errorContainer.isVisible = s.hasError
                binding.tvErrorMessage.text = s.errorMessage
                binding.btnSkipIntro.isVisible = s.showSkipIntro && !s.hasError
                binding.diagText.isVisible = s.settings.diag && s.diagText.isNotEmpty()
                binding.diagText.text = s.diagText

                // Auto-next
                val showNext = s.autoNextCountdown >= 0
                binding.autoNextOverlay.isVisible = showNext
                if (showNext) binding.tvAutoNextCountdown.text =
                    getString(R.string.autonext_countdown, s.autoNextCountdown)

                // Info panel
                binding.infoPanel.isVisible = s.infoPanelVisible
                if (s.infoPanelVisible) {
                    applyPanelTab(s)
                }

                // TMDB overlay
                s.metadata?.let { meta ->
                    if (s.showMetadata && !binding.metadataOverlay.isVisible) {
                        binding.metadataOverlay.alpha = 0f
                        binding.metadataOverlay.isVisible = true
                        binding.metadataOverlay.animate().alpha(1f).setDuration(400).start()
                    } else if (!s.showMetadata && binding.metadataOverlay.isVisible) {
                        binding.metadataOverlay.animate().alpha(0f).setDuration(300).withEndAction {
                            binding.metadataOverlay.isVisible = false
                            binding.metadataOverlay.alpha = 1f
                        }.start()
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

        // Progress loop
        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                val p = vm.player
                val pos = p.currentPosition; val dur = p.duration
                if (dur > 0) {
                    vm.onProgress(pos, dur)
                    binding.progressBar.progress = ((pos.toFloat() / dur) * 1000).toInt()
                    binding.tvCurrentTime.text = formatTime(pos)
                    binding.tvDuration.text = formatTime(dur)
                }
            }
        }
    }

    private fun applyPanelTab(s: PlayerUiState) {
        // Tab highlight
        val accent = getColor(R.color.accent_primary)
        val secondary = getColor(R.color.text_secondary)
        listOf(binding.tabEpisodes to InfoPanelTab.EPISODES,
            binding.tabAudio to InfoPanelTab.AUDIO,
            binding.tabSubtitles to InfoPanelTab.SUBTITLES,
            binding.tabEpg to InfoPanelTab.EPG
        ).forEach { (tv, tab) -> tv.setTextColor(if (s.infoPanelTab == tab) accent else secondary) }

        // Tab visibility
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = vm.uiState.value
        val panelVisible = s.infoPanelVisible
        val osdVisible = s.osdVisible

        // Panel open: let RecyclerView handle navigation
        if (panelVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { vm.hideInfoPanel(); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { vm.hideInfoPanel(); return true }
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                vm.onKeyLeft(); vm.showOsd()
                if (event.repeatCount > 3) startTurboRewind(false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                vm.onKeyRight(); vm.showOsd()
                if (event.repeatCount > 3) startTurboRewind(true)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { vm.onKeyPageUp(); vm.showOsd(); return true }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.onKeyPageDown(); vm.showOsd(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!osdVisible) vm.showOsd() else vm.onKeyOk()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                vm.onKeyOk(); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.onKeyRight(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { vm.onKeyLeft(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { vm.onNextEpisode(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { vm.onPrevEpisode(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> { vm.toggleInfoPanel(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (osdVisible) { vm.toggleInfoPanel(); return true }
            }
            KeyEvent.KEYCODE_BACK -> { vm.onKeyBack(osdVisible, panelVisible); return true }
        }

        vm.showOsd()
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) stopTurboRewind()
        return super.onKeyUp(keyCode, event)
    }

    private fun startTurboRewind(forward: Boolean) {
        if (longPressJob?.isActive == true) return
        longPressJob = lifecycleScope.launch {
            while (isActive) {
                val seekMs = (longPressSpeed * 5_000).toLong()
                val p = vm.player
                if (forward) p.seekTo(p.currentPosition + seekMs)
                else p.seekTo((p.currentPosition - seekMs).coerceAtLeast(0))
                delay(250)
                if (longPressSpeed < 12) longPressSpeed++
            }
        }
    }

    private fun stopTurboRewind() { longPressJob?.cancel(); longPressSpeed = 1 }

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
    override fun onDestroy() { longPressJob?.cancel(); super.onDestroy() }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
