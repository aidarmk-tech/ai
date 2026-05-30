package com.lampa.player.player

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
import com.lampa.player.data.datastore.AppSettings
import com.lampa.player.data.datastore.PositionDataStore
import com.lampa.player.data.datastore.SettingsDataStore
import com.lampa.player.data.tmdb.TmdbRepository
import com.lampa.player.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

enum class InfoPanelTab { EPISODES, AUDIO, SUBTITLES, EPG }

data class PlayerUiState(
    val title: String = "",
    val quality: String = "",
    val translator: String = "",
    val isPlaying: Boolean = false,
    val isLoading: Boolean = true,
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
    val infoPanelTab: InfoPanelTab = InfoPanelTab.EPISODES,
    val episodes: List<EpisodeItem> = emptyList(),
    val currentEpisodeIndex: Int = 0,
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    val selectedAudioIndex: Int = -1,
    val selectedSubtitleIndex: Int = -1,
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

    lateinit var player: ExoPlayer
        private set

    private var currentUrl = ""
    private var currentCard: CardMeta? = null
    private var dataSourceFactory: DefaultHttpDataSource.Factory? = null
    private var saveJob: Job? = null
    private var diagJob: Job? = null
    private var osdHideJob: Job? = null
    private var settings = AppSettings()
    private val errorRecovery = ErrorRecoveryManager()

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { s ->
                settings = s; _uiState.update { it.copy(settings = s) }
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

        loadUrl(url, card)
        startAutoSave()
        if (settings.diag) startDiag()

        card.tmdbId?.let { loadMetadata(it, card.isSerial, card) }

        viewModelScope.launch {
            delay(800)
            _uiState.update { it.copy(showMetadata = true) }
            delay(if (card.tmdbId != null) 6000L else 3000L)
            _uiState.update { it.copy(showMetadata = false) }
        }
    }

    private fun loadUrl(url: String, card: CardMeta) {
        viewModelScope.launch {
            val savedPos = positionDataStore.getPosition(url)
            val startMs = when {
                savedPos == null -> ((card.timelineTime ?: 0.0) * 1000).toLong()
                savedPos.watched -> 0L
                else -> (savedPos.time * 1000).toLong()
            }
            player.setMediaSource(buildMediaSource(url))
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

    private fun buildMediaSource(url: String): MediaSource {
        val item = MediaItem.fromUri(Uri.parse(url))
        val factory = dataSourceFactory!!
        return if (isHls(url))
            HlsMediaSource.Factory(factory).setAllowChunklessPreparation(true).createMediaSource(item)
        else
            DefaultMediaSourceFactory(factory).createMediaSource(item)
    }

    private fun isHls(url: String) = url.contains(".m3u8", ignoreCase = true)

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

    fun setInfoPanelTab(tab: InfoPanelTab) = _uiState.update { it.copy(infoPanelTab = tab) }

    private fun refreshTracks() {
        val tracks = player.currentTracks
        val audioTracks = trackMemoryManager.getAudioTracks(tracks)
        val subTracks = trackMemoryManager.getSubtitleTracks(tracks)
        _uiState.update { it.copy(audioTracks = audioTracks, subtitleTracks = subTracks) }
    }

    fun selectEpisode(episode: EpisodeItem) {
        viewModelScope.launch {
            saveCurrentPosition()
            currentUrl = episode.url
            _uiState.update { it.copy(currentEpisodeIndex = episode.index, infoPanelVisible = false) }
            player.stop()
            loadUrl(episode.url, currentCard!!)
        }
    }

    fun selectAudio(index: Int) {
        val card = currentCard ?: return
        trackMemoryManager.onAudioSelected(player, card, index, viewModelScope)
        _uiState.update { it.copy(selectedAudioIndex = index) }
    }

    fun selectSubtitle(index: Int) {
        val card = currentCard ?: return
        trackMemoryManager.onSubtitleSelected(player, card, index, viewModelScope)
        _uiState.update { it.copy(selectedSubtitleIndex = index) }
    }

    // ─── TMDB ──────────────────────────────────────────────────────
    private fun loadMetadata(tmdbId: Int, isSerial: Boolean, card: CardMeta) {
        viewModelScope.launch {
            val meta = tmdbRepository.getMetadata(tmdbId, isSerial) ?: return@launch
            val title = meta.title ?: meta.name ?: card.title
            val year = (meta.release_date ?: meta.first_air_date)?.take(4) ?: ""
            val rating = meta.vote_average?.let { "★ %.1f".format(it) } ?: ""
            val info = listOfNotNull(year.ifEmpty { null }, rating.ifEmpty { null }, card.quality).joinToString(" · ")
            _uiState.update { s ->
                s.copy(metadata = PlayerUiState.MetadataDisplay(
                    title = title, info = info,
                    overview = meta.overview ?: "",
                    cast = "",
                    posterUrl = TmdbRepository.posterUrl(meta.poster_path) ?: card.posterUrl,
                ))
            }
        }
    }

    fun toggleMetadata() = _uiState.update { it.copy(showMetadata = !it.showMetadata) }

    // ─── Playback controls ─────────────────────────────────────────
    fun onProgress(currentMs: Long, durationMs: Long) {
        val cur = currentMs / 1000.0; val dur = durationMs / 1000.0
        val showSkip = settings.skipIntro && introSkipManager.shouldShowSkipButton(cur, _uiState.value.introEnd.takeIf { it > 0 })
        _uiState.update { it.copy(showSkipIntro = showSkip) }
        if (settings.autonext && !_uiState.value.infoPanelVisible) {
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
        if (end > 0) { player.seekTo((end * 1000).toLong()); _uiState.update { it.copy(showSkipIntro = false) } }
    }

    fun markIntro() {
        val card = currentCard ?: return
        introSkipManager.markIntro(card, player.currentPosition / 1000.0, viewModelScope) { saved ->
            _uiState.update { it.copy(introEnd = saved) }
        }
    }

    fun cancelAutoNext() { autoNextManager.cancel(); _uiState.update { it.copy(autoNextCountdown = -1) } }

    fun onKeyLeft() = player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
    fun onKeyRight() = player.seekTo(player.currentPosition + 10_000)
    fun onKeyPageUp() = player.seekTo(player.currentPosition + 30_000)
    fun onKeyPageDown() = player.seekTo((player.currentPosition - 30_000).coerceAtLeast(0))
    fun onKeyOk() = if (player.isPlaying) player.pause() else player.play()

    fun onNextEpisode() {
        val state = _uiState.value
        val next = state.episodes.getOrNull(state.currentEpisodeIndex + 1)
        if (next != null) selectEpisode(next) else viewModelScope.launch { _navigateToNext.emit(Unit) }
    }

    fun onPrevEpisode() {
        val state = _uiState.value
        val prev = state.episodes.getOrNull(state.currentEpisodeIndex - 1)
        if (prev != null) selectEpisode(prev)
        else if (player.currentPosition > 5000) player.seekTo(0)
    }

    fun onKeyBack(osdVisible: Boolean, panelVisible: Boolean) {
        when {
            panelVisible -> hideInfoPanel()
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
            delay(4000)
            if (player.isPlaying && !_uiState.value.infoPanelVisible)
                _uiState.update { it.copy(osdVisible = false) }
        }
    }

    fun retryPlayback() {
        errorRecovery.reset()
        _uiState.update { it.copy(hasError = false) }
        player.prepare(); player.play()
    }

    fun buildResultExtras(): Triple<Long, Long, Int> {
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
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
                if (action == ErrorRecoveryManager.Action.SHOW_FATAL)
                    _uiState.update { it.copy(hasError = true, errorMessage = buildErrorMessage(error)) }
            }
            override fun onTracksChanged(tracks: Tracks) {
                _uiState.update { it.copy(currentTracks = tracks) }
                if (_uiState.value.infoPanelVisible) refreshTracks()
            }
        })
    }

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
        val durMs = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val posMs = if (ended) durMs else player.currentPosition
        val dur = durMs / 1000.0; val pos = posMs / 1000.0
        val pct = if (dur > 0) ((pos / dur) * 100).toInt() else 0
        positionDataStore.savePosition(url, PlaybackPosition(time = pos, duration = dur, percent = pct, watched = ended || pct >= 90))
    }

    override fun onCleared() {
        saveJob?.cancel(); diagJob?.cancel(); osdHideJob?.cancel()
        viewModelScope.launch { saveCurrentPosition() }
        player.release()
        super.onCleared()
    }

    private fun buildTitle(card: CardMeta) = buildString {
        append(card.title)
        if (card.seasonNumber != null && card.episodeNumber != null)
            append(" — S${card.seasonNumber}E${card.episodeNumber}")
    }
}
