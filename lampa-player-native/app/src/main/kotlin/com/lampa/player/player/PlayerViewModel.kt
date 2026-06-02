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

enum class InfoPanelTab { EPISODES, AUDIO, SUBTITLES, ABOUT, EPG }

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
    // Единая панель с вкладками
    val panelVisible: Boolean = false,
    val infoPanelTab: InfoPanelTab = InfoPanelTab.EPISODES,
    val episodes: List<EpisodeItem> = emptyList(),
    val currentEpisodeIndex: Int = 0,
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    val selectedAudioIndex: Int = -1,
    val selectedSubtitleIndex: Int = -1,
    val videoInfo: String = "",
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

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    private val _showResumeDialog = MutableSharedFlow<ResumePrompt>()
    val showResumeDialog: SharedFlow<ResumePrompt> = _showResumeDialog.asSharedFlow()

    data class ResumePrompt(val url: String, val positionSec: Double)

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
        // EXTENSION_RENDERER_MODE_ON (не PREFER!): аппаратный MediaCodec в приоритете,
        // программный ffmpeg — только как запасной. PREFER ломал 4K HEVC, т.к. софт-декодер
        // его не тянет. enableDecoderFallback: если первичный декодер не стартует — пробуем следующий.
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

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
            // Для высокобитрейтного 4K важно не упираться в байтовый лимит:
            // отдаём приоритет времени буфера, иначе плеер «замирает», ожидая байты.
            .setPrioritizeTimeOverSizeThresholds(true)
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

        // Показываем данные из карточки сразу, затем обогащаем через TMDB.
        // Цепочка: tmdbId → поиск по originalTitle (оригинальное название, не "Дубляж").
        populateMetadataFromCard(card)
        when {
            card.tmdbId != null -> loadMetadata(card.tmdbId, card.isSerial, card)
            !card.originalTitle.isNullOrBlank() ->
                searchAndLoadMetadata(card.originalTitle, card.isSerial, card)
        }
    }

    private fun loadUrl(url: String, card: CardMeta, askResume: Boolean = true) {
        viewModelScope.launch {
            val savedPos = positionDataStore.getPosition(url)
            // Точка возобновления: из нашей памяти позиций или из таймлайна Lampa.
            val resumeSec: Double = when {
                savedPos != null && !savedPos.watched && savedPos.percent < 95 -> savedPos.time
                savedPos == null -> (card.timelineTime ?: 0.0)
                else -> 0.0
            }

            player.setMediaSource(buildMediaSource(url))
            player.prepare()

            if (askResume && resumeSec > 30) {
                // Буферизуемся в точке возобновления, но ждём выбор пользователя.
                player.seekTo((resumeSec * 1000).toLong())
                player.playWhenReady = false
                _showResumeDialog.emit(ResumePrompt(url, resumeSec))
            } else {
                if (resumeSec > 0) player.seekTo((resumeSec * 1000).toLong())
                player.playWhenReady = true
            }

            val showData = positionDataStore.getShowData(card)
            if (showData != null && settings.rememberTracks) {
                trackMemoryManager.applyTracks(player, card, showData, viewModelScope, isHls = isHls(url))
            }
            showData?.introEnd?.let { _uiState.update { s -> s.copy(introEnd = it) } }
        }
    }

    /** Пользователь выбрал «Продолжить» — остаёмся в точке возобновления. */
    fun resumePlaybackConfirmed() { player.playWhenReady = true }

    /** Пользователь выбрал «Сначала» — перематываем в начало. */
    fun restartFromBeginning() { player.seekTo(0); player.playWhenReady = true }

    private fun buildMediaSource(url: String): MediaSource {
        val item = MediaItem.fromUri(Uri.parse(url))
        val factory = dataSourceFactory!!
        return if (isHls(url))
            HlsMediaSource.Factory(factory).setAllowChunklessPreparation(true).createMediaSource(item)
        else
            DefaultMediaSourceFactory(factory).createMediaSource(item)
    }

    private fun isHls(url: String) = url.contains(".m3u8", ignoreCase = true)

    // ─── Единая панель с вкладками ─────────────────────────────────
    /** Список доступных вкладок для текущего контента (в порядке отображения). */
    fun availableTabs(): List<InfoPanelTab> {
        val s = _uiState.value
        val tabs = mutableListOf<InfoPanelTab>()
        if (s.episodes.size > 1) tabs += InfoPanelTab.EPISODES
        if (s.audioTracks.isNotEmpty()) tabs += InfoPanelTab.AUDIO
        if (s.subtitleTracks.isNotEmpty()) tabs += InfoPanelTab.SUBTITLES
        if (s.metadata != null || s.title.isNotEmpty()) tabs += InfoPanelTab.ABOUT
        if (s.card?.epgTitle != null) tabs += InfoPanelTab.EPG
        return tabs.ifEmpty { listOf(InfoPanelTab.ABOUT) }
    }

    fun openPanel(tab: InfoPanelTab? = null) {
        refreshTracks()
        val tabs = availableTabs()
        val target = tab?.takeIf { it in tabs } ?: tabs.first()
        osdHideJob?.cancel()
        _uiState.update { it.copy(panelVisible = true, infoPanelTab = target) }
    }

    fun hidePanel() {
        _uiState.update { it.copy(panelVisible = false) }
        scheduleOsdHide()
    }

    fun setPanelTab(tab: InfoPanelTab) {
        if (tab == InfoPanelTab.AUDIO || tab == InfoPanelTab.SUBTITLES) refreshTracks()
        _uiState.update { it.copy(infoPanelTab = tab) }
    }

    private fun refreshTracks() {
        val tracks = player.currentTracks
        val audioTracks = trackMemoryManager.getAudioTracks(tracks)
        val subTracks = trackMemoryManager.getSubtitleTracks(tracks)
        _uiState.update { it.copy(audioTracks = audioTracks, subtitleTracks = subTracks) }
    }

    fun selectEpisode(episode: EpisodeItem, askResume: Boolean = true) {
        viewModelScope.launch {
            saveCurrentPosition()
            currentUrl = episode.url
            _uiState.update { it.copy(currentEpisodeIndex = episode.index, panelVisible = false) }
            player.stop()
            loadUrl(episode.url, currentCard!!, askResume = askResume)
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
        viewModelScope.launch {
            val meta = tmdbRepository.getMetadata(tmdbId, isSerial) ?: return@launch
            val title = meta.title ?: meta.name ?: card.title
            val year = (meta.release_date ?: meta.first_air_date)?.take(4) ?: ""
            val rating = meta.vote_average?.let { "★ %.1f".format(it) } ?: ""
            val info = listOfNotNull(year.ifEmpty { null }, rating.ifEmpty { null }, card.quality).joinToString(" · ")
            _uiState.update { s ->
                s.copy(
                    title = title,
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

    private fun searchAndLoadMetadata(query: String, isSerial: Boolean, card: CardMeta) {
        viewModelScope.launch {
            val meta = tmdbRepository.searchAndGet(query, isSerial) ?: return@launch
            val title = meta.title ?: meta.name ?: query
            val year = (meta.release_date ?: meta.first_air_date)?.take(4) ?: ""
            val rating = meta.vote_average?.let { "★ %.1f".format(it) } ?: ""
            val info = listOfNotNull(year.ifEmpty { null }, rating.ifEmpty { null }, card.quality).joinToString(" · ")
            _uiState.update { s ->
                s.copy(
                    title = title,
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
        when {
            merged.tmdbId != null -> loadMetadata(merged.tmdbId, merged.isSerial, merged)
            !merged.originalTitle.isNullOrBlank() ->
                searchAndLoadMetadata(merged.originalTitle, merged.isSerial, merged)
        }
    }

    fun toggleMetadata() = _uiState.update { it.copy(showMetadata = !it.showMetadata) }

    // ─── Playback controls ─────────────────────────────────────────
    fun onProgress(currentMs: Long, durationMs: Long) {
        val cur = currentMs / 1000.0; val dur = durationMs / 1000.0
        val showSkip = settings.skipIntro && introSkipManager.shouldShowSkipButton(cur, _uiState.value.introEnd.takeIf { it > 0 })
        _uiState.update { it.copy(showSkipIntro = showSkip) }
        // Авто-переход только для конечного контента (фильм/серия), НЕ для live/IPTV.
        // У live-потока длительность = окну буфера, из-за чего обратный отсчёт срабатывал через ~15с.
        if (settings.autonext && !isLiveStream() && !_uiState.value.panelVisible) {
            autoNextManager.checkAndStart(dur, cur, settings.autonextDelay, viewModelScope,
                onTick = { t -> _uiState.update { it.copy(autoNextCountdown = t) } },
                onNext = { viewModelScope.launch { navigateNext() } })
        }
    }

    private fun isLiveStream(): Boolean =
        currentCard?.isIptv == true ||
        player.isCurrentMediaItemLive ||
        player.duration == C.TIME_UNSET

    private fun navigateNext() {
        val state = _uiState.value
        val nextIdx = state.currentEpisodeIndex + 1
        when {
            // Есть следующая серия — проигрываем с начала, без вопроса о возобновлении
            state.episodes.isNotEmpty() && nextIdx < state.episodes.size ->
                selectEpisode(state.episodes[nextIdx], askResume = false)
            // Сериал, но список серий пуст/закончился — НЕ выходим молча в Lampa,
            // показываем OSD и сообщение (раньше это выглядело как «просто выходит»).
            currentCard?.isSerial == true -> {
                showOsd()
                viewModelScope.launch {
                    _message.emit(
                        if (state.episodes.isEmpty()) "Список серий не получен из Lampa"
                        else "Это последняя серия"
                    )
                }
            }
            // Одиночный фильм досмотрен — выходим с результатом «просмотрено»
            else -> viewModelScope.launch { _navigateToNext.emit(Unit) }
        }
    }

    fun skipIntro() {
        val end = _uiState.value.introEnd
        if (end > 0) { player.seekTo((end * 1000).toLong()); _uiState.update { it.copy(showSkipIntro = false) } }
    }

    /**
     * Авто-обучение конца интро без отдельной кнопки: если пользователь вручную
     * проскакивает вперёд в начале серии (первые 10 минут) и конец интро ещё не известен,
     * запоминаем точку приземления как конец заставки. В следующих сериях покажется
     * «Пропустить заставку».
     */
    fun learnIntroFromSeek(targetSec: Double) {
        val card = currentCard ?: return
        if (!settings.skipIntro) return
        if (_uiState.value.introEnd > 0) return
        val fromSec = player.currentPosition / 1000.0
        if (targetSec <= fromSec) return                 // только проскок вперёд
        if (targetSec !in 30.0..600.0) return            // только в зоне заставки
        introSkipManager.markIntro(card, targetSec, viewModelScope) { saved ->
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
        if (next != null) selectEpisode(next)
        else viewModelScope.launch {
            _message.emit(if (state.episodes.isEmpty()) "Список серий не получен" else "Это последняя серия")
        }
    }

    fun onPrevEpisode() {
        val state = _uiState.value
        val prev = state.episodes.getOrNull(state.currentEpisodeIndex - 1)
        if (prev != null) selectEpisode(prev)
        else if (player.currentPosition > 5000) player.seekTo(0)
    }

    fun onKeyBack(osdVisible: Boolean, panelVisible: Boolean) {
        when {
            _uiState.value.panelVisible -> hidePanel()
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
            if (player.isPlaying && !_uiState.value.panelVisible)
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
                    if (settings.autonext && !isLiveStream()) viewModelScope.launch { navigateNext() }
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
                if (_uiState.value.panelVisible) refreshTracks()
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
