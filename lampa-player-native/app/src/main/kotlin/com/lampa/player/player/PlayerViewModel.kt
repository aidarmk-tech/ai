package com.lampa.player.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.lampa.player.data.datastore.AppSettings
import com.lampa.player.data.datastore.PositionDataStore
import com.lampa.player.data.datastore.SettingsDataStore
import com.lampa.player.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

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
)

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val positionDataStore: PositionDataStore,
    private val settingsDataStore: SettingsDataStore,
    private val trackMemoryManager: TrackMemoryManager,
    private val introSkipManager: IntroSkipManager,
    private val autoNextManager: AutoNextManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _navigateToNext = MutableSharedFlow<Unit>()
    val navigateToNext: SharedFlow<Unit> = _navigateToNext.asSharedFlow()

    private val _showExitDialog = MutableSharedFlow<Unit>()
    val showExitDialog: SharedFlow<Unit> = _showExitDialog.asSharedFlow()

    lateinit var player: ExoPlayer
        private set

    private var currentUrl: String = ""
    private var currentCard: CardMeta? = null
    private var saveJob: Job? = null
    private var diagJob: Job? = null
    private var osdHideJob: Job? = null
    private var settings: AppSettings = AppSettings()

    private val errorRecovery = ErrorRecoveryManager()

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { s ->
                settings = s
                _uiState.update { it.copy(settings = s) }
            }
        }
    }

    fun initPlayer(context: Context, url: String, card: CardMeta) {
        currentUrl = url
        currentCard = card
        _uiState.update { it.copy(title = buildTitle(card), quality = card.quality ?: "", translator = card.translator ?: "", card = card) }

        val profile = BufferProfile.fromType(settings.buffer)
        val loadControl = buildLoadControl(profile)
        val trackSelector = DefaultTrackSelector(context)

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(card.headers)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)

        player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { setupPlayerListener(it) }

        val mediaSource = buildMediaSource(url, card.headers, dataSourceFactory)
        player.setMediaSource(mediaSource)

        viewModelScope.launch {
            val savedPos = positionDataStore.getPosition(url)
            val startMs = when {
                savedPos == null -> (card.timelineTime ?: 0.0).toLong() * 1000
                savedPos.watched -> 0L
                else -> (savedPos.time * 1000).toLong()
            }
            player.seekTo(startMs)
            player.prepare()
            player.playWhenReady = true

            val showData = currentCard?.let { positionDataStore.getShowData(it) }
            if (showData != null && settings.rememberTracks) {
                trackMemoryManager.applyTracks(
                    player, card, showData, viewModelScope, isHls = url.contains(".m3u8")
                )
            }
            showData?.introEnd?.let { introEnd ->
                _uiState.update { it.copy(introEnd = introEnd) }
            }
        }

        startAutoSave()
        if (settings.diag) startDiag()
    }

    private fun buildLoadControl(profile: BufferProfile): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.maxBufferLengthMs.toInt(),
                profile.maxMaxBufferLengthMs.toInt(),
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(profile.maxBufferSizeBytes.toInt())
            .setBackBuffer(profile.backBufferLengthMs.toInt(), true)
            .build()

    private fun buildMediaSource(
        url: String,
        headers: Map<String, String>,
        factory: DefaultHttpDataSource.Factory,
    ): MediaSource {
        val uri = Uri.parse(url)
        val item = MediaItem.fromUri(uri)
        return if (url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(factory).createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(factory).createMediaSource(item)
        }
    }

    private fun setupPlayerListener(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                _uiState.update { it.copy(isLoading = state == Player.STATE_BUFFERING) }
                if (state == Player.STATE_ENDED) onPlaybackEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerError(error)
            }

            override fun onTracksChanged(tracks: Tracks) {
                _uiState.update { it.copy(currentTracks = tracks) }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                checkAutoNext()
                checkIntroSkip()
            }
        })
    }

    fun onProgress(currentMs: Long, durationMs: Long) {
        val currentSec = currentMs / 1000.0
        val durationSec = durationMs / 1000.0

        val showSkip = settings.skipIntro && introSkipManager.shouldShowSkipButton(
            currentSec, _uiState.value.introEnd.takeIf { it > 0 }
        )
        _uiState.update { it.copy(showSkipIntro = showSkip) }

        if (settings.autonext) {
            autoNextManager.checkAndStart(
                durationSec, currentSec, settings.autonextDelay, viewModelScope,
                onTick = { countdown -> _uiState.update { it.copy(autoNextCountdown = countdown) } },
                onNext = { viewModelScope.launch { _navigateToNext.emit(Unit) } },
            )
        }
    }

    private fun checkAutoNext() {
        val pos = player.currentPosition
        val dur = player.duration
        if (dur > 0) onProgress(pos, dur)
    }

    private fun checkIntroSkip() {
        val currentSec = player.currentPosition / 1000.0
        val introEnd = _uiState.value.introEnd
        val show = settings.skipIntro && introSkipManager.shouldShowSkipButton(currentSec, introEnd.takeIf { it > 0 })
        _uiState.update { it.copy(showSkipIntro = show) }
    }

    fun skipIntro() {
        val introEnd = _uiState.value.introEnd
        if (introEnd > 0) {
            player.seekTo((introEnd * 1000).toLong())
            _uiState.update { it.copy(showSkipIntro = false) }
        }
    }

    fun markIntro() {
        val card = currentCard ?: return
        val currentSec = player.currentPosition / 1000.0
        introSkipManager.markIntro(card, currentSec, viewModelScope) { saved ->
            _uiState.update { it.copy(introEnd = saved) }
        }
    }

    fun cancelAutoNext() {
        autoNextManager.cancel()
        _uiState.update { it.copy(autoNextCountdown = -1) }
    }

    fun selectAudioTrack(index: Int) {
        val card = currentCard ?: return
        if (settings.rememberTracks) {
            trackMemoryManager.onAudioSelected(player, card, index, viewModelScope)
        }
    }

    fun selectSubtitleTrack(index: Int) {
        val card = currentCard ?: return
        if (settings.rememberTracks) {
            trackMemoryManager.onSubtitleSelected(player, card, index, viewModelScope)
        }
    }

    fun onKeyLeft() = player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
    fun onKeyRight() = player.seekTo(player.currentPosition + 10_000)
    fun onKeyPageUp() = player.seekTo(player.currentPosition + 30_000)
    fun onKeyPageDown() = player.seekTo((player.currentPosition - 30_000).coerceAtLeast(0))
    fun onKeyOk() = if (player.isPlaying) player.pause() else player.play()
    fun onKeyBack(osdVisible: Boolean) {
        if (osdVisible) {
            _uiState.update { it.copy(osdVisible = false) }
        } else {
            viewModelScope.launch { _showExitDialog.emit(Unit) }
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
            if (player.isPlaying) _uiState.update { it.copy(osdVisible = false) }
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        val action = errorRecovery.onError(error, player)
        if (action == ErrorRecoveryManager.Action.SHOW_FATAL) {
            _uiState.update { it.copy(hasError = true, errorMessage = error.message ?: "Playback error") }
        }
    }

    private fun onPlaybackEnded() {
        viewModelScope.launch {
            saveCurrentPosition(ended = true)
            if (settings.autonext) _navigateToNext.emit(Unit)
        }
    }

    private fun startAutoSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                if (player.isPlaying && player.currentPosition > 0 && player.duration != C.TIME_UNSET) {
                    saveCurrentPosition()
                }
            }
        }
    }

    private fun startDiag() {
        diagJob?.cancel()
        diagJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val buffered = player.bufferedPosition - player.currentPosition
                val bufferedSec = buffered / 1000.0
                val format = player.videoFormat
                val height = format?.height ?: 0
                val bitrate = (format?.bitrate ?: 0) / 1000
                _uiState.update { it.copy(diagText = "Буфер: %.1fс  ${height}p ${bitrate}kbps".format(bufferedSec)) }
            }
        }
    }

    private suspend fun saveCurrentPosition(ended: Boolean = false) {
        val url = currentUrl.ifEmpty { return }
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val currentMs = if (ended) durationMs else player.currentPosition
        val durationSec = durationMs / 1000.0
        val currentSec = currentMs / 1000.0
        val percent = if (durationSec > 0) ((currentSec / durationSec) * 100).toInt() else 0
        positionDataStore.savePosition(
            url,
            com.lampa.player.domain.model.PlaybackPosition(
                time = currentSec,
                duration = durationSec,
                percent = percent,
                watched = ended || percent >= 90,
            )
        )
    }

    fun buildResultExtras(): Triple<Long, Long, Int> {
        val posMs = player.currentPosition
        val durMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        val percent = if (durMs > 0) ((posMs.toFloat() / durMs) * 100).toInt() else 0
        return Triple(posMs, durMs, percent)
    }

    fun retryPlayback() {
        _uiState.update { it.copy(hasError = false) }
        player.prepare()
        player.play()
    }

    override fun onCleared() {
        saveJob?.cancel()
        diagJob?.cancel()
        osdHideJob?.cancel()
        viewModelScope.launch { saveCurrentPosition() }
        player.release()
        super.onCleared()
    }

    private fun buildTitle(card: CardMeta): String = buildString {
        append(card.title)
        if (card.seasonNumber != null && card.episodeNumber != null) {
            append(" — S${card.seasonNumber}E${card.episodeNumber}")
        }
    }
}
