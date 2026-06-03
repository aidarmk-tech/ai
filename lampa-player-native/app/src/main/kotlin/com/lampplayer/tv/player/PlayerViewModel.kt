package com.lampplayer.tv.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.lampplayer.tv.data.datastore.AppSettings
import com.lampplayer.tv.data.datastore.PositionDataStore
import com.lampplayer.tv.data.datastore.SettingsDataStore
import com.lampplayer.tv.data.tmdb.TmdbRepository
import com.lampplayer.tv.domain.model.*
import com.lampplayer.tv.engine.EngineListener
import com.lampplayer.tv.engine.EngineType
import com.lampplayer.tv.engine.VlcController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

enum class InfoPanelTab { EPISODES, AUDIO, SUBTITLES, EPG }

/** Selectable playback speeds cycled by the OSD speed button. */
private val SPEED_STEPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Video sizing mode. ExoPlayer maps these to PlayerView resizeMode; libVLC supports FIT/FILL. */
enum class VideoScaleMode { FIT, FILL, ZOOM }

data class PlayerUiState(
    val title: String = "",
    val quality: String = "",
    val translator: String = "",
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String = "",
    val osdVisible: Boolean = true,
    val showSkipIntro: Boolean = false,
    val introEnd: Double = 0.0,
    val autoNextCountdown: Int = -1,
    val diagText: String = "",
    val currentTracks: Tracks = Tracks.EMPTY,
    val card: CardMeta? = null,
    val settings: AppSettings = AppSettings(),
    val metadata: MetadataDisplay? = null,
    val showMetadata: Boolean = false,
    // Info panel
    val infoPanelVisible: Boolean = false,
    val infoOverlayVisible: Boolean = false,
    val tracksOverlayVisible: Boolean = false,
    val infoPanelTab: InfoPanelTab = InfoPanelTab.EPISODES,
    val episodes: List<EpisodeItem> = emptyList(),
    val currentEpisodeIndex: Int = 0,
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    val selectedAudioIndex: Int = -1,
    val selectedSubtitleIndex: Int = -1,
    val videoInfo: String = "",
    val playbackSpeed: Float = 1f,
    val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
) {
    data class MetadataDisplay(
        val title: String,
        val info: String,
        val overview: String,
        val cast: String,
        val posterUrl: String?,
    )
}

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val positionDataStore: PositionDataStore,
    private val settingsDataStore: SettingsDataStore,
    private val trackMemoryManager: TrackMemoryManager,
    private val introSkipManager: IntroSkipManager,
    private val autoNextManager: AutoNextManager,
    private val tmdbRepository: TmdbRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _navigateToNext = MutableSharedFlow<Unit>()
    val navigateToNext: SharedFlow<Unit> = _navigateToNext.asSharedFlow()

    private val _showExitDialog = MutableSharedFlow<Unit>()
    val showExitDialog: SharedFlow<Unit> = _showExitDialog.asSharedFlow()

    private val _playEpisode = MutableSharedFlow<EpisodeItem>()
    val playEpisode: SharedFlow<EpisodeItem> = _playEpisode.asSharedFlow()

    // ExoPlayer instance — only initialized when the active engine is ExoPlayer.
    // Guard external/internal access with [usingVlc]; in VLC mode it is never created.
    lateinit var player: ExoPlayer
        private set

    // libVLC fallback engine (non-null only while usingVlc).
    var vlc: VlcController? = null
        private set
    private var usingVlc = false

    /** Emitted when AUTO mode hands playback over to libVLC; the Activity rebinds surfaces. */
    private val _engineSwitched = MutableSharedFlow<Unit>()
    val engineSwitched: SharedFlow<Unit> = _engineSwitched.asSharedFlow()

    private var currentUrl = ""
    private var currentCard: CardMeta? = null
    private var dataSourceFactory: DefaultHttpDataSource.Factory? = null
    private var saveJob: Job? = null
    private var diagJob: Job? = null
    private var osdHideJob: Job? = null
    private var vlcPollJob: Job? = null
    private var sleepJob: Job? = null
    private var metaJob: Job? = null
    private var settings = AppSettings()
    private val errorRecovery = ErrorRecoveryManager()
    private var didAutoFallback = false

    val isUsingVlc: Boolean get() = usingVlc
    val currentMediaUrl: String get() = currentUrl

    /**
     * Tear down the active engine so a different media item can start fresh.
     * Used when the singleTask activity is relaunched for another film.
     */
    fun resetForNewMedia() {
        // Persist the outgoing position using captured values (engine about to die).
        val url = currentUrl
        val pos = engPositionMs()
        val dur = engDurationMs()
        if (url.isNotEmpty() && dur > 0) {
            val p = pos / 1000.0; val d = dur / 1000.0
            val pct = ((p / d) * 100).toInt()
            viewModelScope.launch {
                positionDataStore.savePosition(url, PlaybackPosition(time = p, duration = d, percent = pct, watched = pct >= 90))
            }
        }
        saveJob?.cancel(); diagJob?.cancel(); vlcPollJob?.cancel(); osdHideJob?.cancel(); metaJob?.cancel()
        if (::player.isInitialized) runCatching { player.release() }
        runCatching { vlc?.release() }; vlc = null
        usingVlc = false
        didAutoFallback = false
        currentRate = 1f
        currentUrl = ""
        currentCard = null
        _uiState.value = PlayerUiState(settings = settings)
    }

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { s ->
                settings = s; _uiState.update { it.copy(settings = s) }
                restartSleepTimer(s.sleepTimerMin)
            }
        }
    }

    fun initPlayer(context: Context, url: String, card: CardMeta) {
        currentUrl = url
        currentCard = card
        _uiState.update {
            it.copy(
                title = buildTitle(card), quality = card.quality ?: "",
                translator = card.translator ?: "", card = card,
                episodes = card.episodes, currentEpisodeIndex = card.currentEpisodeIndex,
            )
        }

        // Engine selection (see EngineType). "vlc" → libVLC immediately;
        // "exoplayer"/"auto" → ExoPlayer (AUTO falls back to libVLC on fatal decode errors).
        if (settings.engine == EngineType.VLC) {
            startVlc(context, url, card, useIntentStart = true)
        } else {
            initExo(context, url, card)
        }

        // Show card metadata immediately from intent data, enhance with TMDB if available
        populateMetadataFromCard(card)
        card.tmdbId?.let { loadMetadata(it, card.isSerial, card) }
    }

    private fun initExo(context: Context, url: String, card: CardMeta) {
        usingVlc = false
        val profile = BufferProfile.fromType(settings.buffer)
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                parameters.buildUpon()
                    .setPreferredAudioLanguage("ru")
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .build()
            )
        }

        dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(buildMap {
                putAll(card.headers)
                if (!containsKey("User-Agent"))
                    put("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/91.0 Mobile Safari/537.36")
            })
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.maxBufferLengthMs.toInt(), profile.maxMaxBufferLengthMs.toInt(),
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(profile.maxBufferSizeBytes.toInt())
            .setBackBuffer(profile.backBufferLengthMs.toInt(), true)
            .build()

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory!!))
            .build()
            .also { setupPlayerListener(it) }

        loadUrl(url, card, useIntentStart = true)
        startAutoSave()
        if (settings.diag) startDiag()
    }

    // ─── libVLC engine ─────────────────────────────────────────────

    /** Build the libVLC controller and start playback. Surface is attached by the Activity. */
    private fun startVlc(context: Context, url: String, card: CardMeta, useIntentStart: Boolean) {
        usingVlc = true
        val profile = BufferProfile.fromType(settings.buffer)
        val controller = VlcController(
            context = appContext,
            networkCachingMs = profile.maxBufferLengthMs.toInt().coerceIn(1500, 60_000),
            listener = vlcListener,
        )
        vlc = controller
        viewModelScope.launch {
            val savedPos = positionDataStore.getPosition(url)
            val startMs = when {
                useIntentStart && card.fromStart -> 0L
                useIntentStart && card.startPositionMs != null -> card.startPositionMs
                savedPos == null -> ((card.timelineTime ?: 0.0) * 1000).toLong()
                savedPos.watched -> 0L
                else -> (savedPos.time * 1000).toLong()
            }
            controller.setMedia(
                context = appContext,
                url = url,
                headers = card.headers,
                startMs = startMs.coerceAtLeast(0),
                subtitles = if (useIntentStart) card.subtitles else emptyList(),
                hardwareDecode = true,
            )
            reapplyRate()
            startVlcPoll()
        }
    }

    private val vlcListener = object : EngineListener {
        override fun onPlaying() { _uiState.update { it.copy(isPlaying = true, isLoading = false) } }
        override fun onPaused() { _uiState.update { it.copy(isPlaying = false) } }
        override fun onBuffering(percent: Float) {
            _uiState.update { it.copy(isLoading = percent < 100f) }
        }
        override fun onEnded() {
            viewModelScope.launch { saveCurrentPosition(ended = true) }
            if (settings.autonext) viewModelScope.launch { navigateNext() }
        }
        override fun onError(message: String) {
            _uiState.update { it.copy(hasError = true, errorMessage = "libVLC: $message") }
        }
        override fun onTracksChanged() { if (_uiState.value.tracksOverlayVisible) refreshTracks() }
    }

    private fun startVlcPoll() {
        vlcPollJob?.cancel()
        vlcPollJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val v = vlc ?: break
                if (v.isPlaying && v.positionMs > 0 && v.durationMs > 0) saveCurrentPosition()
                if (settings.diag) {
                    _uiState.update { it.copy(diagText = "libVLC  %ds / %ds".format(v.positionMs / 1000, v.durationMs / 1000)) }
                }
            }
        }
    }

    /**
     * AUTO mode: ExoPlayer hit a fatal decode/container error — hand the same media,
     * at the last known position, over to libVLC. The Activity rebinds the surfaces
     * on [engineSwitched].
     */
    private fun fallbackToVlc() {
        if (didAutoFallback) return
        didAutoFallback = true
        val card = currentCard ?: return
        val resumeMs = runCatching { player.currentPosition }.getOrDefault(0L)
        runCatching { player.release() }
        saveJob?.cancel(); diagJob?.cancel()
        _uiState.update { it.copy(hasError = false, isLoading = true) }
        usingVlc = true
        val profile = BufferProfile.fromType(settings.buffer)
        val controller = VlcController(appContext, profile.maxBufferLengthMs.toInt().coerceIn(1500, 60_000), vlcListener)
        vlc = controller
        controller.setMedia(appContext, currentUrl, card.headers, resumeMs.coerceAtLeast(0), card.subtitles, hardwareDecode = true)
        reapplyRate()
        startVlcPoll()
        viewModelScope.launch { _engineSwitched.emit(Unit) }
    }

    // useIntentStart=true only for the initially launched item: honour the
    // explicit `position`/`from_start` extras. Episode switches resume from
    // their own saved position instead.
    private fun loadUrl(url: String, card: CardMeta, useIntentStart: Boolean = false) {
        viewModelScope.launch {
            val savedPos = positionDataStore.getPosition(url)
            val startMs = when {
                useIntentStart && card.fromStart -> 0L
                useIntentStart && card.startPositionMs != null -> card.startPositionMs
                savedPos == null -> ((card.timelineTime ?: 0.0) * 1000).toLong()
                savedPos.watched -> 0L
                else -> (savedPos.time * 1000).toLong()
            }
            player.setMediaSource(buildMediaSource(url, card.takeIf { useIntentStart }))
            if (startMs > 0) player.seekTo(startMs)
            player.prepare()
            player.playWhenReady = true

            val showData = positionDataStore.getShowData(card)
            if (showData != null && settings.rememberTracks) {
                trackMemoryManager.applyTracks(player, card, showData, viewModelScope, isHls = isHls(url))
            }
            showData?.introEnd?.let { _uiState.update { s -> s.copy(introEnd = it) } }
        }
    }

    private fun buildMediaSource(url: String, card: CardMeta?): MediaSource {
        val factory = dataSourceFactory!!
        val subtitleConfigs = card?.subtitles.orEmpty().mapNotNull { sub ->
            val uri = runCatching { Uri.parse(sub.uri) }.getOrNull() ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(subtitleMimeType(sub.uri))
                .setLabel(sub.name)
                .setSelectionFlags(if (sub.enabled) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .apply { if (subtitleConfigs.isNotEmpty()) setSubtitleConfigurations(subtitleConfigs) }
            .build()
        // Sideloaded subtitles require DefaultMediaSourceFactory (it merges the
        // subtitle sources); the HLS-specific factory does not.
        return if (isHls(url) && subtitleConfigs.isEmpty())
            HlsMediaSource.Factory(factory).setAllowChunklessPreparation(true).createMediaSource(item)
        else
            DefaultMediaSourceFactory(factory).createMediaSource(item)
    }

    private fun subtitleMimeType(uri: String): String {
        val path = uri.substringBefore('?').lowercase()
        return when {
            path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".dfxp") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun isHls(url: String) = url.contains(".m3u8", ignoreCase = true)

    // ─── Engine-agnostic accessors (route to ExoPlayer or libVLC) ──
    private fun engPositionMs(): Long = when {
        usingVlc -> vlc?.positionMs ?: 0L
        ::player.isInitialized -> player.currentPosition
        else -> 0L
    }
    private fun engDurationMs(): Long = when {
        usingVlc -> (vlc?.durationMs ?: -1L).takeIf { it > 0 } ?: 0L
        ::player.isInitialized -> player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        else -> 0L
    }
    private fun engIsPlaying(): Boolean = when {
        usingVlc -> vlc?.isPlaying ?: false
        ::player.isInitialized -> player.isPlaying
        else -> false
    }
    private fun engSeekTo(ms: Long) {
        if (usingVlc) vlc?.seekTo(ms) else if (::player.isInitialized) player.seekTo(ms)
    }

    // Public unified controls for the Activity (engine-agnostic).
    fun positionMs(): Long = engPositionMs()
    fun durationMs(): Long = engDurationMs()
    fun bufferedMs(): Long = when {
        usingVlc -> vlc?.bufferedMs ?: 0L
        ::player.isInitialized -> player.bufferedPosition
        else -> 0L
    }
    fun isPlayingNow(): Boolean = engIsPlaying()
    fun seekToMs(ms: Long) = engSeekTo(ms)
    fun pausePlayback() { if (usingVlc) vlc?.pause() else if (::player.isInitialized) player.pause() }
    fun resumePlayback() { if (usingVlc) vlc?.play() else if (::player.isInitialized) player.play() }

    // ─── Playback speed (engine-agnostic) ──────────────────────────
    private var currentRate = 1f
    private fun applyRate(rate: Float) {
        currentRate = rate
        if (usingVlc) vlc?.setRate(rate)
        else if (::player.isInitialized) player.setPlaybackSpeed(rate)
    }
    /** Re-apply rate + sizing after (re)loading media — VLC resets them per media. */
    private fun reapplyRate() {
        if (currentRate != 1f) applyRate(currentRate)
        if (usingVlc && _uiState.value.scaleMode != VideoScaleMode.FIT) applyScaleMode(_uiState.value.scaleMode)
    }

    fun cyclePlaybackSpeed() {
        val idx = SPEED_STEPS.indexOfFirst { it >= currentRate - 0.001f }.takeIf { it >= 0 } ?: 2
        val next = SPEED_STEPS[(idx + 1) % SPEED_STEPS.size]
        applyRate(next)
        _uiState.update { it.copy(playbackSpeed = next) }
    }

    // ─── Video sizing (aspect / zoom) ──────────────────────────────
    // Screen ratio "W:H" used for libVLC FILL (stretch). Set by the Activity.
    private var displayAspect: String? = null
    fun setDisplayAspect(ratio: String) { displayAspect = ratio }

    fun cycleScaleMode() {
        // libVLC has no reliable keep-aspect crop without video-track dims, so it
        // cycles FIT↔FILL only; ExoPlayer offers the full FIT→FILL→ZOOM set.
        val order = if (usingVlc) listOf(VideoScaleMode.FIT, VideoScaleMode.FILL)
                    else listOf(VideoScaleMode.FIT, VideoScaleMode.FILL, VideoScaleMode.ZOOM)
        val idx = order.indexOf(_uiState.value.scaleMode).coerceAtLeast(0)
        val next = order[(idx + 1) % order.size]
        _uiState.update { it.copy(scaleMode = next) }
        applyScaleMode(next)   // ExoPlayer sizing is applied by the Activity via resizeMode
    }

    private fun applyScaleMode(mode: VideoScaleMode) {
        if (!usingVlc) return
        when (mode) {
            VideoScaleMode.FILL -> { vlc?.setAspectRatio(displayAspect); vlc?.setScale(0f) }
            else -> { vlc?.setAspectRatio(null); vlc?.setScale(0f) }   // FIT (and unreachable ZOOM)
        }
    }

    // ─── Info Panel ────────────────────────────────────────────────
    fun toggleInfoPanel() {
        val visible = !_uiState.value.infoPanelVisible
        _uiState.update { it.copy(infoPanelVisible = visible) }
        if (visible) {
            refreshTracks()
            osdHideJob?.cancel()
        } else {
            scheduleOsdHide()
        }
    }

    fun hideInfoPanel() = _uiState.update { it.copy(infoPanelVisible = false) }

    fun toggleInfoOverlay() {
        val visible = !_uiState.value.infoOverlayVisible
        _uiState.update { it.copy(infoOverlayVisible = visible) }
        if (visible) { refreshTracks(); osdHideJob?.cancel() }
        else scheduleOsdHide()
    }

    fun hideInfoOverlay() = _uiState.update { it.copy(infoOverlayVisible = false) }

    fun toggleTracksOverlay() {
        val visible = !_uiState.value.tracksOverlayVisible
        _uiState.update { it.copy(tracksOverlayVisible = visible, infoOverlayVisible = false) }
        if (visible) { refreshTracks(); osdHideJob?.cancel() }
        else scheduleOsdHide()
    }

    fun hideTracksOverlay() = _uiState.update { it.copy(tracksOverlayVisible = false) }

    fun setInfoPanelTab(tab: InfoPanelTab) = _uiState.update { it.copy(infoPanelTab = tab) }

    private fun refreshTracks() {
        if (usingVlc) {
            val v = vlc ?: return
            _uiState.update {
                it.copy(
                    audioTracks = v.audioTracks().mapIndexed { i, t -> "$i: ${t.name}" },
                    subtitleTracks = v.subtitleTracks().mapIndexed { i, t -> "$i: ${t.name}" },
                )
            }
            return
        }
        val tracks = player.currentTracks
        val audioTracks = trackMemoryManager.getAudioTracks(tracks)
        val subTracks = trackMemoryManager.getSubtitleTracks(tracks)
        _uiState.update { it.copy(audioTracks = audioTracks, subtitleTracks = subTracks) }
    }

    fun selectEpisode(episode: EpisodeItem) {
        viewModelScope.launch {
            saveCurrentPosition()
            currentUrl = episode.url
            _uiState.update { it.copy(currentEpisodeIndex = episode.index, infoOverlayVisible = false) }
            if (usingVlc) {
                val card = currentCard ?: return@launch
                vlc?.setMedia(appContext, episode.url, card.headers, 0L, emptyList(), hardwareDecode = true)
                reapplyRate()
            } else {
                player.stop()
                loadUrl(episode.url, currentCard!!)
            }
        }
    }

    fun selectAudio(index: Int) {
        val card = currentCard ?: return
        if (usingVlc) {
            vlc?.audioTracks()?.getOrNull(index)?.let { vlc?.selectAudio(it.id) }
        } else {
            trackMemoryManager.onAudioSelected(player, card, index, viewModelScope)
        }
        _uiState.update { it.copy(selectedAudioIndex = index) }
    }

    fun selectSubtitle(index: Int) {
        val card = currentCard ?: return
        if (usingVlc) {
            vlc?.subtitleTracks()?.getOrNull(index)?.let { vlc?.selectSubtitle(it.id) }
        } else {
            trackMemoryManager.onSubtitleSelected(player, card, index, viewModelScope)
        }
        _uiState.update { it.copy(selectedSubtitleIndex = index) }
    }

    // ─── TMDB ──────────────────────────────────────────────────────

    private fun populateMetadataFromCard(card: CardMeta) {
        val year = card.releaseYear?.toString() ?: ""
        val rating = card.rating?.let { "★ %.1f".format(it) } ?: ""
        val info = listOfNotNull(year.ifEmpty { null }, rating.ifEmpty { null }, card.quality).joinToString(" · ")
        _uiState.update { s ->
            s.copy(metadata = PlayerUiState.MetadataDisplay(
                title = card.title,
                info = info,
                overview = card.overview ?: "",
                cast = "",
                posterUrl = card.posterUrl,
            ))
        }
    }

    private fun loadMetadata(tmdbId: Int, isSerial: Boolean, card: CardMeta) {
        // Cancel any in-flight request from a previous film so a slow response
        // can't clobber the current film's metadata (stale title/poster).
        metaJob?.cancel()
        metaJob = viewModelScope.launch {
            val meta = tmdbRepository.getMetadata(tmdbId, isSerial) ?: return@launch
            val title = meta.title ?: meta.name ?: card.title
            val year = (meta.release_date ?: meta.first_air_date)?.take(4) ?: ""
            val rating = meta.vote_average?.let { "★ %.1f".format(it) } ?: ""
            val info = listOfNotNull(year.ifEmpty { null }, rating.ifEmpty { null }, card.quality).joinToString(" · ")
            // Enrich the metadata used by the info overlay. No auto-splash — the
            // card is shown only on ↓ (user request).
            _uiState.update { s ->
                s.copy(
                    metadata = PlayerUiState.MetadataDisplay(
                        title = title, info = info,
                        overview = meta.overview ?: s.metadata?.overview ?: "",
                        cast = "",
                        posterUrl = TmdbRepository.posterUrl(meta.poster_path) ?: card.posterUrl,
                    ),
                )
            }
        }
    }

    fun updateCardMeta(card: CardMeta) {
        val merged = card.copy(
            episodes = card.episodes.takeIf { it.isNotEmpty() } ?: (currentCard?.episodes ?: emptyList()),
        )
        currentCard = merged
        _uiState.update { s ->
            s.copy(
                title = buildTitle(merged),
                quality = merged.quality ?: s.quality,
                translator = merged.translator ?: s.translator,
                card = merged,
                episodes = merged.episodes,
            )
        }
        populateMetadataFromCard(merged)
        merged.tmdbId?.let { loadMetadata(it, merged.isSerial, merged) }
    }

    fun toggleMetadata() = _uiState.update { it.copy(showMetadata = !it.showMetadata) }

    // ─── Playback controls ─────────────────────────────────────────
    fun onProgress(currentMs: Long, durationMs: Long) {
        val cur = currentMs / 1000.0; val dur = durationMs / 1000.0
        val showSkip = settings.skipIntro && introSkipManager.shouldShowSkipButton(cur, _uiState.value.introEnd.takeIf { it > 0 })
        _uiState.update { it.copy(showSkipIntro = showSkip) }
        if (settings.autonext && !_uiState.value.infoOverlayVisible) {
            autoNextManager.checkAndStart(dur, cur, settings.autonextDelay, viewModelScope,
                onTick = { t -> _uiState.update { it.copy(autoNextCountdown = t) } },
                onNext = { viewModelScope.launch { navigateNext() } })
        }
    }

    private fun navigateNext() {
        val state = _uiState.value
        val nextIdx = state.currentEpisodeIndex + 1
        if (state.episodes.isNotEmpty() && nextIdx < state.episodes.size) {
            selectEpisode(state.episodes[nextIdx])
        } else {
            viewModelScope.launch { _navigateToNext.emit(Unit) }
        }
    }

    fun skipIntro() {
        val end = _uiState.value.introEnd
        if (end > 0) { engSeekTo((end * 1000).toLong()); _uiState.update { it.copy(showSkipIntro = false) } }
    }

    fun markIntro() {
        val card = currentCard ?: return
        introSkipManager.markIntro(card, engPositionMs() / 1000.0, viewModelScope) { saved ->
            _uiState.update { it.copy(introEnd = saved) }
        }
    }

    fun cancelAutoNext() { autoNextManager.cancel(); _uiState.update { it.copy(autoNextCountdown = -1) } }

    // Sleep timer: pause playback and surface the exit dialog when it elapses.
    private fun restartSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) return
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            pausePlayback()
            _showExitDialog.emit(Unit)
        }
    }

    fun onKeyLeft() = engSeekTo((engPositionMs() - 10_000).coerceAtLeast(0))
    fun onKeyRight() = engSeekTo(engPositionMs() + 10_000)
    fun onKeyPageUp() = engSeekTo(engPositionMs() + 30_000)
    fun onKeyPageDown() = engSeekTo((engPositionMs() - 30_000).coerceAtLeast(0))
    fun onKeyOk() { if (engIsPlaying()) pausePlayback() else resumePlayback() }

    fun onNextEpisode() {
        val state = _uiState.value
        val next = state.episodes.getOrNull(state.currentEpisodeIndex + 1)
        if (next != null) selectEpisode(next) else viewModelScope.launch { _navigateToNext.emit(Unit) }
    }

    fun onPrevEpisode() {
        val state = _uiState.value
        val prev = state.episodes.getOrNull(state.currentEpisodeIndex - 1)
        if (prev != null) selectEpisode(prev)
        else if (engPositionMs() > 5000) engSeekTo(0)
    }

    fun onKeyBack(osdVisible: Boolean, panelVisible: Boolean) {
        when {
            _uiState.value.infoOverlayVisible -> hideInfoOverlay()
            osdVisible -> _uiState.update { it.copy(osdVisible = false) }
            else -> viewModelScope.launch { _showExitDialog.emit(Unit) }
        }
    }

    fun showOsd() {
        _uiState.update { it.copy(osdVisible = true) }
        scheduleOsdHide()
    }

    private fun scheduleOsdHide() {
        osdHideJob?.cancel()
        osdHideJob = viewModelScope.launch {
            delay(settings.osdTimeoutSec.coerceAtLeast(3) * 1000L)
            if (engIsPlaying() && !_uiState.value.infoOverlayVisible && !_uiState.value.tracksOverlayVisible)
                _uiState.update { it.copy(osdVisible = false) }
        }
    }

    fun retryPlayback() {
        errorRecovery.reset()
        _uiState.update { it.copy(hasError = false) }
        if (usingVlc) vlc?.play() else { player.prepare(); player.play() }
    }

    fun buildResultExtras(): Triple<Long, Long, Int> {
        val pos = engPositionMs()
        val dur = engDurationMs()
        val pct = if (dur > 0) ((pos.toFloat() / dur) * 100).toInt() else 0
        return Triple(pos, dur, pct)
    }

    // ─── Internal ──────────────────────────────────────────────────
    private fun setupPlayerListener(p: ExoPlayer) {
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                _uiState.update { it.copy(isLoading = state == Player.STATE_BUFFERING) }
                if (state == Player.STATE_ENDED) {
                    viewModelScope.launch { saveCurrentPosition(ended = true) }
                    if (settings.autonext) viewModelScope.launch { navigateNext() }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) =
                _uiState.update { it.copy(isPlaying = isPlaying) }
            override fun onPlayerError(error: PlaybackException) {
                val action = errorRecovery.onError(error, p)
                if (action == ErrorRecoveryManager.Action.SHOW_FATAL) {
                    // AUTO mode: a decode/container failure is exactly what libVLC may handle.
                    if (settings.engine == EngineType.AUTO && !didAutoFallback && isDecodeError(error)) {
                        fallbackToVlc()
                    } else {
                        _uiState.update { it.copy(hasError = true, errorMessage = buildErrorMessage(error)) }
                    }
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                _uiState.update { it.copy(currentTracks = tracks) }
                if (_uiState.value.tracksOverlayVisible) refreshTracks()
                updateVideoInfo()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) = updateVideoInfo()
        })
    }

    private fun updateVideoInfo() {
        val vfmt = player.videoFormat
        val afmt = player.audioFormat
        val info = buildString {
            if (vfmt != null) {
                if (vfmt.width > 0) append("${vfmt.width}×${vfmt.height}  ")
                if (vfmt.frameRate > 0) append("%.0ffps  ".format(vfmt.frameRate))
                vfmt.codecs?.let { append("$it  ") }
                if (vfmt.bitrate > 0) append("${vfmt.bitrate / 1000}kbps  ")
            }
            if (afmt != null) {
                afmt.language?.let { append("Аудио: $it  ") }
                afmt.codecs?.let { append("$it  ") }
                if (afmt.channelCount > 0) append("${afmt.channelCount}ch  ")
            }
        }.trim()
        _uiState.update { it.copy(videoInfo = info) }
    }

    private fun isDecodeError(e: PlaybackException): Boolean = e.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    private fun buildErrorMessage(e: PlaybackException) = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Нет соединения с сервером"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Таймаут подключения"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Сервер вернул ошибку HTTP"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Ошибка декодера видео"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Повреждённый контейнер"
        else -> "Ошибка воспроизведения (${e.errorCode})"
    }

    private fun startAutoSave() {
        saveJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                if (player.isPlaying && player.currentPosition > 0 && player.duration != C.TIME_UNSET)
                    saveCurrentPosition()
            }
        }
    }

    private fun startDiag() {
        diagJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val buf = (player.bufferedPosition - player.currentPosition) / 1000.0
                val fmt = player.videoFormat
                _uiState.update {
                    it.copy(diagText = "Буфер: %.1fс  ${fmt?.height ?: 0}p ${(fmt?.bitrate ?: 0) / 1000}kbps".format(buf))
                }
            }
        }
    }

    private suspend fun saveCurrentPosition(ended: Boolean = false) {
        val url = currentUrl.ifEmpty { return }
        val durMs = engDurationMs().takeIf { it > 0 } ?: return
        val posMs = if (ended) durMs else engPositionMs()
        val dur = durMs / 1000.0; val pos = posMs / 1000.0
        val pct = if (dur > 0) ((pos / dur) * 100).toInt() else 0
        positionDataStore.savePosition(url, PlaybackPosition(time = pos, duration = dur, percent = pct, watched = ended || pct >= 90))
    }

    override fun onCleared() {
        saveJob?.cancel(); diagJob?.cancel(); osdHideJob?.cancel(); vlcPollJob?.cancel(); sleepJob?.cancel(); metaJob?.cancel()
        viewModelScope.launch { saveCurrentPosition() }
        if (::player.isInitialized) player.release()
        vlc?.release(); vlc = null
        super.onCleared()
    }

    private fun buildTitle(card: CardMeta) = buildString {
        append(card.title)
        if (card.seasonNumber != null && card.episodeNumber != null)
            append(" — S${card.seasonNumber}E${card.episodeNumber}")
    }
}
