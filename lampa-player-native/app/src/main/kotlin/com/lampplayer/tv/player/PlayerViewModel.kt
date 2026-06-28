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
import com.lampplayer.tv.data.epg.EpgProgramme
import com.lampplayer.tv.data.epg.EpgRepository
import com.lampplayer.tv.data.tmdb.TmdbRepository
import com.lampplayer.tv.domain.model.*
import com.lampplayer.tv.engine.EngineListener
import com.lampplayer.tv.engine.EngineTrack
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

/** Video sizing mode. ExoPlayer maps these to PlayerView resizeMode; libVLC supports FIT/FILL.
 *  AUTO = fit, but auto-zoom to fill when black bars are thin (small aspect mismatch). */
enum class VideoScaleMode { AUTO, FIT, FILL, ZOOM }

data class PlayerUiState(
    val title: String = "",
    val quality: String = "",
    val translator: String = "",
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String = "",
    // Network dropped: auto-reconnecting in the background (no fatal screen).
    val reconnecting: Boolean = false,
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
    val volumeBoost: Int = 100,
    val scaleMode: VideoScaleMode = VideoScaleMode.AUTO,
    val videoAspect: Float = 0f,
    // Content frame rate (for AFR); 0 = unknown.
    val videoFps: Float = 0f,
    // IPTV electronic programme guide for the current channel (formatted now/next).
    val epgText: String = "",
    // Rich episode list fetched from TMDB (series only).
    val episodeRows: List<EpisodeRow> = emptyList(),
) {
    data class MetadataDisplay(
        val title: String,
        val info: String,
        val overview: String,
        val cast: String,
        val posterUrl: String?,
        // Extended block revealed on ↓: Режиссёр / Сценарий / Жанры.
        val details: String = "",
        // Cast with photos for the actor carousel.
        val castMembers: List<CastMember> = emptyList(),
    )

    data class CastMember(val name: String, val character: String, val photoUrl: String?)

    data class EpisodeRow(
        val number: Int,
        val title: String,
        val overview: String,
        val stillUrl: String?,
        val current: Boolean,
        val url: String? = null,   // playable stream URL (from the playlist window), if available
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

    /** Emitted once when playback resumes from a saved position (ms) — shows the resume card. */
    private val _resumedFromMs = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val resumedFromMs: SharedFlow<Long> = _resumedFromMs.asSharedFlow()

    /** Emitted once per session when the stream keeps rebuffering — show a friendly hint. */
    private val _weakStreamHint = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val weakStreamHint: SharedFlow<Unit> = _weakStreamHint.asSharedFlow()
    private var everReady = false
    private var weakHintShown = false
    private val rebufferTimes = ArrayDeque<Long>()

    private fun onRebuffer() {
        val now = System.currentTimeMillis()
        rebufferTimes.addLast(now)
        while (rebufferTimes.isNotEmpty() && now - rebufferTimes.first() > 90_000) rebufferTimes.removeFirst()
        if (rebufferTimes.size >= 3 && !weakHintShown) {
            weakHintShown = true
            _weakStreamHint.tryEmit(Unit)
        }
    }

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
    private var retryJob: Job? = null
    private var metaJob: Job? = null
    private var episodesJob: Job? = null
    private var epgJob: Job? = null
    private val epgRepository = EpgRepository()
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
        saveJob?.cancel(); diagJob?.cancel(); vlcPollJob?.cancel(); osdHideJob?.cancel(); metaJob?.cancel(); episodesJob?.cancel(); epgJob?.cancel(); retryJob?.cancel()
        releaseLoudness()
        runCatching { nightModeCtl.release() }
        if (::player.isInitialized) runCatching { player.release() }
        runCatching { vlc?.release() }; vlc = null
        usingVlc = false
        didAutoFallback = false
        everReady = false; weakHintShown = false; rebufferTimes.clear()
        vlcRetries = 0; lastVlcPositionMs = 0L
        errorRecovery.reset()
        currentRate = 1f
        currentUrl = ""
        currentCard = null
        _uiState.value = PlayerUiState(settings = settings)
    }

    // ─── Network monitor: auto-resume when connectivity returns ────────────
    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun startNetworkMonitor() {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        connectivityManager = cm
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                viewModelScope.launch { onNetworkBack() }
            }
        }
        networkCallback = cb
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                val req = android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                cm.registerNetworkCallback(req, cb)
            }
        }
    }

    private fun stopNetworkMonitor() {
        runCatching { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    /** Connectivity returned — if we're stalled/errored, reconnect immediately. */
    private fun onNetworkBack() {
        val s = _uiState.value
        if (!s.reconnecting && !s.hasError) return     // healthy → nothing to do
        retryJob?.cancel()
        if (usingVlc) retryVlc()
        else if (::player.isInitialized) runCatching { player.prepare(); player.playWhenReady = true }
    }

    private var lastNightMode: Boolean? = null

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { s ->
                settings = s; _uiState.update { it.copy(settings = s) }
                restartSleepTimer(s.sleepTimerMin)
                // Apply night mode live when the user toggles it.
                if (lastNightMode != null && lastNightMode != s.nightMode) applyNightMode(s.nightMode)
                lastNightMode = s.nightMode
            }
        }
    }

    // ─── MediaSession: voice ("стоп/перемотай/дальше") + remote/BT media keys ──
    private var mediaSession: android.support.v4.media.session.MediaSessionCompat? = null

    private fun setupMediaSession() {
        if (mediaSession != null) return
        runCatching {
            val s = android.support.v4.media.session.MediaSessionCompat(appContext, "LampPlayer")
            s.setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                override fun onPlay() { resumePlayback(); updateMediaSession() }
                override fun onPause() { pausePlayback(); updateMediaSession() }
                override fun onStop() { pausePlayback(); updateMediaSession() }
                override fun onSeekTo(pos: Long) { engSeekTo(pos); updateMediaSession() }
                override fun onFastForward() { engSeekTo(engPositionMs() + 30_000); updateMediaSession() }
                override fun onRewind() { engSeekTo((engPositionMs() - 10_000).coerceAtLeast(0)); updateMediaSession() }
                override fun onSkipToNext() { onNextEpisode() }
                override fun onSkipToPrevious() { onPrevEpisode() }
            })
            s.isActive = true
            mediaSession = s
        }
    }

    private fun updateMediaSession() {
        val s = mediaSession ?: return
        runCatching {
            val playing = engIsPlaying()
            val actions = android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_SEEK_TO or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_FAST_FORWARD or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_REWIND or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            val state = if (playing) android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                        else android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
            s.setPlaybackState(
                android.support.v4.media.session.PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, engPositionMs(), if (playing) currentRate else 0f)
                    .build()
            )
            val dur = engDurationMs()
            s.setMetadata(
                android.support.v4.media.MediaMetadataCompat.Builder()
                    .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, _uiState.value.title)
                    .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, if (dur > 0) dur else 0L)
                    .build()
            )
        }
    }

    private fun releaseMediaSession() {
        runCatching { mediaSession?.isActive = false; mediaSession?.release() }
        mediaSession = null
    }

    fun initPlayer(context: Context, url: String, card: CardMeta) {
        currentUrl = url
        currentCard = card
        startNetworkMonitor()
        setupMediaSession()
        _uiState.update {
            it.copy(
                title = buildTitle(card), quality = card.quality ?: "",
                translator = card.translator ?: "", card = card,
                episodes = card.episodes, currentEpisodeIndex = card.currentEpisodeIndex,
            )
        }

        // Restore remembered playback prefs: volume boost + scale mode survive restarts.
        currentBoost = settings.volumeBoost.coerceIn(100, 200)
        val savedScale = runCatching { VideoScaleMode.valueOf(settings.scaleMode) }.getOrDefault(VideoScaleMode.AUTO)
        _uiState.update { it.copy(volumeBoost = currentBoost, scaleMode = savedScale) }

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
        loadEpisodes(card)
        loadEpg(card)
        updateMediaSession()
        fetchIntroFromDb(card)
        detectIntroFromSubs(card)
    }

    /** Fetch a crowdsourced intro timecode (Firebase) for this episode, if any. */
    private fun fetchIntroFromDb(card: CardMeta) {
        val tmdb = card.tmdbId ?: return
        if (card.iptv || !IntroDb.enabled) return
        viewModelScope.launch(Dispatchers.IO) {
            val ms = runCatching { IntroDb.fetchIntroEndMs(tmdb, card.seasonNumber, card.episodeNumber) }.getOrDefault(0L)
            if (ms > 0 && _uiState.value.introEnd <= 0.0) _uiState.update { it.copy(introEnd = ms / 1000.0) }
        }
    }

    /** Auto-detect the intro from an external subtitle (Phase 1: subs we already have). */
    private fun detectIntroFromSubs(card: CardMeta) {
        if (!settings.skipIntro || card.iptv) return
        val sub = card.subtitles.firstOrNull { it.uri.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ms = runCatching { SubtitleIntroDetector.detectIntroEndMs(appContext, sub.uri) }.getOrDefault(0L)
            if (ms in 5_000L..330_000L && _uiState.value.introEnd <= 0.0) {
                _uiState.update { it.copy(introEnd = ms / 1000.0) }
            }
        }
    }

    /**
     * Build the episode list for the panel. TMDB gives names/stills/synopsis;
     * the playlist window (card.episodes) gives the playable URLs — matched by
     * episode number. Falls back to the playlist alone when TMDB has no data.
     */
    private fun loadEpisodes(card: CardMeta) {
        // Playable URLs from the title-envelope playlist window, keyed by episode number.
        val urlByEpisode = card.episodes.mapNotNull { ep -> ep.episode?.let { it to ep.url } }.toMap()

        // Immediate fallback list from the playlist so something shows even before TMDB.
        if (card.episodes.size > 1) {
            _uiState.update {
                it.copy(episodeRows = card.episodes.map { ep ->
                    PlayerUiState.EpisodeRow(
                        number = ep.episode ?: (ep.index + 1),
                        title = ep.title,
                        overview = "",
                        stillUrl = null,
                        current = ep.index == card.currentEpisodeIndex,
                        url = ep.url,
                    )
                })
            }
        }

        val tmdb = card.tmdbId ?: return
        val season = card.seasonNumber ?: return   // movies have no season
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            val eps = tmdbRepository.getSeason(tmdb, season)
            if (eps.isEmpty()) return@launch
            val rows = eps.map { e ->
                PlayerUiState.EpisodeRow(
                    number = e.episode_number,
                    title = e.name?.takeIf { it.isNotBlank() } ?: "Серия ${e.episode_number}",
                    overview = e.overview.orEmpty(),
                    stillUrl = TmdbRepository.stillUrl(e.still_path),
                    current = e.episode_number == card.episodeNumber,
                    url = urlByEpisode[e.episode_number],
                )
            }
            _uiState.update { it.copy(episodeRows = rows) }
        }
    }

    /** IPTV EPG: load the playlist's XMLTV (once) and format now/next for the current channel. */
    private fun loadEpg(card: CardMeta) {
        if (!card.iptv) { _uiState.update { it.copy(epgText = "") }; return }
        // Prefer the programme Lampa already rendered (scraped by the plugin) — instant, no XMLTV.
        card.iptvEpg?.takeIf { it.isNotBlank() }?.let {
            _uiState.update { s -> s.copy(epgText = it) }
            return
        }
        // Channel switched in-player: look it up in the all-channels map scraped at launch.
        lookupEpgMap(card)?.let {
            _uiState.update { s -> s.copy(epgText = it) }
            return
        }
        // No scraped guide at all. Show "Нет программы" right away — never block the UI — and
        // only try the XMLTV guide in the background if the source hasn't already been ruled
        // out as too big (which is what used to hang on every switch).
        _uiState.update { it.copy(epgText = epgNoneText(card)) }
        val src = card.iptvSource?.takeIf { it.isNotBlank() } ?: return
        if (epgRepository.isDead(src)) return
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            epgRepository.ensureLoaded(appContext, src)
            refreshEpgText()   // upgrades to the guide if it loaded; stays "Нет программы" otherwise
        }
    }

    private fun refreshEpgText() {
        val card = currentCard ?: return
        if (!card.iptv) return
        val text = formatEpg(epgRepository.programmes(card.title, currentUrl))
        val out = when {
            text.isNotEmpty() -> text
            settings.diag -> epgNoneText(card) + "\nXMLTV: ${epgRepository.lastStatus}\n${epgRepository.matchDebug(card.title, currentUrl)}"
            else -> "Нет программы"
        }
        _uiState.update { it.copy(epgText = out) }
    }

    /** "Нет программы", plus — with Diagnostics on — why the launch-time map didn't match. */
    private fun epgNoneText(card: CardMeta): String {
        if (!settings.diag) return "Нет программы"
        val age = if (card.iptvEpgTs > 0) " возраст=${(System.currentTimeMillis() - card.iptvEpgTs) / 60_000}м" else ""
        val sample = card.iptvEpgMap.keys.take(3).joinToString(", ")
        return "Нет программы · карта=${card.iptvEpgMap.size}$age ключ='${normChannel(card.title)}'" +
            (if (sample.isNotEmpty()) "\nключи карты: $sample" else "") +
            "\nsrc=${card.iptvSource?.take(60) ?: "—"}"
    }

    /**
     * Programme for [card]'s channel from the launch-time map (scraped from Lampa's
     * channel list). Entries describe "now" at scrape time, so the whole map expires
     * after 2h. Matched by normalized name: exact, else containment (closest length).
     */
    private fun lookupEpgMap(card: CardMeta): String? {
        if (card.iptvEpgMap.isEmpty()) return null
        if (card.iptvEpgTs > 0 && System.currentTimeMillis() - card.iptvEpgTs > 2 * 3600_000L) return null
        val key = normChannel(card.title)
        if (key.isBlank()) return null
        card.iptvEpgMap[key]?.let { return it }
        return card.iptvEpgMap.entries
            .filter { it.key.contains(key) || key.contains(it.key) }
            .minByOrNull { kotlin.math.abs(it.key.length - key.length) }
            ?.value
    }

    /** Must mirror the plugin's norm(): lowercase, strip everything but [a-zа-я0-9]. */
    private fun normChannel(s: String): String =
        s.lowercase().replace(Regex("[^a-zа-я0-9]"), "")

    private fun formatEpg(progs: List<EpgProgramme>): String {
        if (progs.isEmpty()) return ""
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val cur = progs.firstOrNull { now in it.start until it.stop }
        val sb = StringBuilder()
        cur?.let {
            sb.append("Сейчас  ").append(fmt.format(it.start)).append("–").append(fmt.format(it.stop))
                .append("  ").append(it.title)
            if (it.desc.isNotBlank()) sb.append("\n").append(it.desc.take(160))
        }
        val upcoming = progs.filter { it.start >= (cur?.stop ?: now) }.take(5)
        if (upcoming.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\nДалее:")
            upcoming.forEach { sb.append("\n  ").append(fmt.format(it.start)).append("  ").append(it.title) }
        }
        return sb.toString()
    }

    /** Play an episode chosen from the list (only when it has a playable URL). */
    fun playEpisodeRow(row: PlayerUiState.EpisodeRow) {
        val url = row.url ?: return
        val card = currentCard ?: return
        val item = card.episodes.firstOrNull { it.url == url }
            ?: EpisodeItem(index = card.currentEpisodeIndex, title = row.title, url = url, season = null, episode = row.number)
        selectEpisode(item)
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
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
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
            // handleAudioFocus=true: pause/duck when another app needs audio.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
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
                nightMode = settings.nightMode,
            )
            reapplyRate()
            startVlcPoll()
            if (!card.iptv && startMs > 60_000) _resumedFromMs.tryEmit(startMs)   // "Продолжаем с …"
        }
    }

    private val vlcListener = object : EngineListener {
        override fun onPlaying() {
            vlcRetries = 0
            _uiState.update { it.copy(isPlaying = true, isLoading = false, reconnecting = false, hasError = false, videoFps = vlc?.videoFps() ?: 0f) }
            updateMediaSession()
        }
        override fun onPaused() { _uiState.update { it.copy(isPlaying = false) }; updateMediaSession() }
        override fun onBuffering(percent: Float) {
            _uiState.update { it.copy(isLoading = percent < 100f) }
        }
        override fun onEnded() {
            viewModelScope.launch { saveCurrentPosition(ended = true) }
            // Advance only if there's a real in-player next; otherwise stay (don't
            // surprise-exit to Lampa) and show controls.
            if (hasInPlayerNext()) viewModelScope.launch { navigateNext() }
            else _uiState.update { it.copy(osdVisible = true) }
        }
        override fun onError(message: String) {
            // Don't go straight to a fatal screen — try to reconnect (network drops).
            scheduleVlcRetry()
        }
        override fun onTracksChanged() { if (_uiState.value.tracksOverlayVisible) refreshTracks() }
    }

    private fun startVlcPoll() {
        vlcPollJob?.cancel()
        vlcPollJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val v = vlc ?: break
                if (v.isPlaying && v.positionMs > 0 && v.durationMs > 0) {
                    saveCurrentPosition()
                    lastVlcPositionMs = v.positionMs           // for reconnect after a drop
                    if (vlcRetries != 0) vlcRetries = 0        // healthy → reset retry counter
                    if (_uiState.value.reconnecting) _uiState.update { it.copy(reconnecting = false) }
                }
                if (settings.diag) {
                    _uiState.update { it.copy(diagText = "libVLC  %ds / %ds".format(v.positionMs / 1000, v.durationMs / 1000)) }
                }
            }
        }
    }

    // libVLC has no built-in retry: on error, re-open the media at the last position
    // with backoff (network drops should reconnect, not show a fatal screen).
    private var lastVlcPositionMs = 0L
    private var vlcRetries = 0
    private val maxVlcRetries = 6

    private fun scheduleVlcRetry() {
        if (!usingVlc) return
        if (vlcRetries >= maxVlcRetries) {
            _uiState.update { it.copy(hasError = true, reconnecting = false, errorMessage = "Не удалось восстановить поток") }
            return
        }
        vlcRetries++
        _uiState.update { it.copy(isLoading = true, reconnecting = true, hasError = false) }
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay((1000L shl vlcRetries.coerceIn(1, 4)))      // 2,4,8,16s
            retryVlc()
        }
    }

    private fun retryVlc() {
        val card = currentCard ?: return
        val v = vlc ?: return
        runCatching {
            v.setMedia(appContext, currentUrl, card.headers, lastVlcPositionMs.coerceAtLeast(0),
                emptyList(), hardwareDecode = true, nightMode = settings.nightMode)
            reapplyRate()
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
        // Flip the engine flag BEFORE releasing ExoPlayer so concurrent position/seek
        // accessors never route to the released instance.
        usingVlc = true
        releaseLoudness()   // Exo effect is bound to the Exo session we're releasing
        runCatching { nightModeCtl.release() }
        runCatching { player.release() }
        saveJob?.cancel(); diagJob?.cancel(); retryJob?.cancel()
        _uiState.update { it.copy(hasError = false, isLoading = true) }
        val profile = BufferProfile.fromType(settings.buffer)
        val controller = VlcController(appContext, profile.maxBufferLengthMs.toInt().coerceIn(1500, 60_000), vlcListener)
        vlc = controller
        controller.setMedia(appContext, currentUrl, card.headers, resumeMs.coerceAtLeast(0), card.subtitles, hardwareDecode = true, nightMode = settings.nightMode)
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
            if (!card.iptv && startMs > 60_000) _resumedFromMs.tryEmit(startMs)   // "Продолжаем с …"

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
    fun seekToMs(ms: Long) { engSeekTo(ms); updateMediaSession() }
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
        if (currentBoost != 100) applyBoost(currentBoost)
        if (usingVlc && _uiState.value.scaleMode == VideoScaleMode.FILL) applyScaleMode(_uiState.value.scaleMode)
    }

    fun cyclePlaybackSpeed() {
        val idx = SPEED_STEPS.indexOfFirst { it >= currentRate - 0.001f }.takeIf { it >= 0 } ?: 2
        val next = SPEED_STEPS[(idx + 1) % SPEED_STEPS.size]
        applyRate(next)
        _uiState.update { it.copy(playbackSpeed = next) }
    }

    // ─── Volume boost (>100% for quiet content on weak TV speakers) ────────
    private var currentBoost = 100
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private val boostSteps = listOf(100, 125, 150, 200)
    private val nightModeCtl = NightModeController()

    fun cycleVolumeBoost() {
        val idx = boostSteps.indexOf(currentBoost).coerceAtLeast(0)
        val next = boostSteps[(idx + 1) % boostSteps.size]
        applyBoost(next)
        _uiState.update { it.copy(volumeBoost = next) }
        viewModelScope.launch { settingsDataStore.setVolumeBoost(next) }   // survives restarts
    }

    private fun applyBoost(percent: Int) {
        currentBoost = percent
        if (usingVlc) { vlc?.setVolume(percent); return }
        if (!::player.isInitialized) return
        if (percent <= 100) {
            runCatching { loudness?.enabled = false }
            return
        }
        // LoudnessEnhancer gain is in millibels: 20·log10(ratio) dB → ×100.
        val gainMb = (2000.0 * kotlin.math.log10(percent / 100.0)).toInt()
        runCatching {
            val sid = player.audioSessionId
            if (sid == 0) return@runCatching
            if (loudness == null) loudness = android.media.audiofx.LoudnessEnhancer(sid)
            loudness?.setTargetGain(gainMb)
            loudness?.enabled = true
        }
    }

    private fun releaseLoudness() {
        runCatching { loudness?.release() }
        loudness = null
    }

    /** Apply night mode to the active engine (ExoPlayer: live; libVLC: re-open at position). */
    private fun applyNightMode(enabled: Boolean) {
        if (usingVlc) {
            retryVlc()   // libVLC needs the media re-opened to add/remove the compressor filter
        } else if (::player.isInitialized) {
            runCatching { nightModeCtl.apply(player.audioSessionId, enabled) }
        }
    }

    /** OSD toggle: flip night mode, persist it, and apply to the running engine. */
    fun toggleNightMode() {
        val next = !settings.nightMode
        lastNightMode = next            // we apply it here — stop the settings collector re-applying
        applyNightMode(next)
        viewModelScope.launch { settingsDataStore.setNightMode(next) }
    }

    // ─── Video sizing (aspect / zoom) ──────────────────────────────
    // Screen ratio "W:H" used for libVLC FILL (stretch). Set by the Activity.
    private var displayAspect: String? = null
    fun setDisplayAspect(ratio: String) { displayAspect = ratio }

    fun cycleScaleMode() {
        // libVLC has no reliable keep-aspect crop without video-track dims, so it
        // cycles AUTO↔FILL only; ExoPlayer offers AUTO→FIT→FILL→ZOOM.
        val order = if (usingVlc) listOf(VideoScaleMode.AUTO, VideoScaleMode.FILL)
                    else listOf(VideoScaleMode.AUTO, VideoScaleMode.FIT, VideoScaleMode.FILL, VideoScaleMode.ZOOM)
        val idx = order.indexOf(_uiState.value.scaleMode).coerceAtLeast(0)
        val next = order[(idx + 1) % order.size]
        _uiState.update { it.copy(scaleMode = next) }
        applyScaleMode(next)   // ExoPlayer sizing is applied by the Activity via resizeMode
        viewModelScope.launch { settingsDataStore.setScaleMode(next.name) }   // survives restarts
    }

    private fun applyScaleMode(mode: VideoScaleMode) {
        if (!usingVlc) return
        when (mode) {
            VideoScaleMode.FILL -> { vlc?.setAspectRatio(displayAspect); vlc?.setScale(0f) }
            else -> { vlc?.setAspectRatio(null); vlc?.setScale(0f) }   // AUTO/FIT
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

    // Snapshot of the VLC track lists currently shown, so a later selection maps to
    // the exact same id the user picked (libVLC can reorder/refetch differently).
    private var vlcAudio: List<EngineTrack> = emptyList()
    private var vlcSubs: List<EngineTrack> = emptyList()

    private fun refreshTracks() {
        if (usingVlc) {
            val v = vlc ?: return
            vlcAudio = v.audioTracks()
            vlcSubs = v.subtitleTracks()
            _uiState.update {
                it.copy(
                    audioTracks = vlcAudio.mapIndexed { i, t -> "$i: ${t.name}" },
                    subtitleTracks = vlcSubs.mapIndexed { i, t -> "$i: ${t.name}" },
                )
            }
            return
        }
        val tracks = player.currentTracks
        val audioTracks = trackMemoryManager.getAudioTracks(tracks)
        // ExoPlayer subtitles are off by default and have no native "off" entry — add one.
        val subTracks = listOf("Выкл") + trackMemoryManager.getSubtitleTracks(tracks)
        _uiState.update {
            it.copy(
                audioTracks = audioTracks,
                subtitleTracks = subTracks,
                selectedSubtitleIndex = if (it.selectedSubtitleIndex < 0) 0 else it.selectedSubtitleIndex,
            )
        }
    }

    fun selectEpisode(episode: EpisodeItem) {
        autoNextManager.cancel()
        _uiState.update { it.copy(autoNextCountdown = -1) }
        viewModelScope.launch {
            saveCurrentPosition()
            // Remember the channel we're leaving so "previous channel" can flip back.
            if (currentCard?.iptv == true && episode.index != _uiState.value.currentEpisodeIndex)
                prevChannelIndex = _uiState.value.currentEpisodeIndex
            currentUrl = episode.url
            // Update the card's season/episode so the title + badge reflect the new episode.
            val wasIptv = currentCard?.iptv == true
            val updated = currentCard?.copy(
                // IPTV: the item title IS the channel name; series keep the show title + S/E.
                title = if (wasIptv) episode.title else (currentCard?.title ?: ""),
                seasonNumber = if (wasIptv) null else (episode.season ?: currentCard?.seasonNumber),
                episodeNumber = if (wasIptv) null else (episode.episode ?: currentCard?.episodeNumber),
                // scraped EPG was for the launched channel only — clear on switch.
                iptvEpg = if (wasIptv) null else currentCard?.iptvEpg,
            )
            if (updated != null) currentCard = updated
            _uiState.update { s ->
                s.copy(
                    currentEpisodeIndex = episode.index,
                    infoOverlayVisible = false,
                    title = updated?.let { buildTitle(it) } ?: s.title,
                    card = updated ?: s.card,
                    episodeRows = s.episodeRows.map { it.copy(current = it.number == episode.episode) },
                )
            }
            if (wasIptv && updated != null) { populateMetadataFromCard(updated); loadEpg(updated) }   // refresh title + EPG for new channel
            if (usingVlc) {
                val card = currentCard ?: return@launch
                vlc?.setMedia(appContext, episode.url, card.headers, 0L, emptyList(), hardwareDecode = true, nightMode = settings.nightMode)
                reapplyRate()
            } else {
                player.stop()
                loadUrl(episode.url, currentCard!!)
            }
            updateMediaSession()
        }
    }

    fun selectAudio(index: Int) {
        val card = currentCard ?: return
        if (usingVlc) {
            val v = vlc; val track = vlcAudio.getOrNull(index)
            if (v != null && track != null) {
                // libVLC builds disagree on whether setAudioTrack takes the track id or
                // the list position — try the id, verify, and fall back to the index.
                v.selectAudio(track.id)
                if (v.currentAudioTrackId() != track.id) v.selectAudio(index)
                if (settings.diag) _uiState.update {
                    it.copy(diagText = "audio→ ${track.name} id=${track.id} pos=$index now=${v.currentAudioTrackId()}")
                }
            }
        } else {
            trackMemoryManager.onAudioSelected(player, card, index, viewModelScope)
        }
        _uiState.update { it.copy(selectedAudioIndex = index) }
    }

    fun selectSubtitle(index: Int) {
        val card = currentCard ?: return
        if (usingVlc) {
            val v = vlc; val track = vlcSubs.getOrNull(index)
            if (v != null && track != null) {
                v.selectSubtitle(track.id)
                if (v.currentSpuTrackId() != track.id) v.selectSubtitle(index)
                if (settings.diag) _uiState.update {
                    it.copy(diagText = "sub→ ${track.name} id=${track.id} pos=$index now=${v.currentSpuTrackId()}")
                }
            }
        } else {
            // Exo list: position 0 = "Выкл", position k = subtitle group (k-1).
            trackMemoryManager.onSubtitleSelected(player, card, index - 1, viewModelScope)
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
            val runtime = meta.runtime?.takeIf { it > 0 }?.let { "$it мин" } ?: ""
            val info = listOfNotNull(year.ifEmpty { null }, rating.ifEmpty { null }, runtime.ifEmpty { null }, card.quality).joinToString(" · ")
            val details = buildDetails(meta)
            val castLine = meta.credits?.cast?.take(10)?.mapNotNull { it.name }?.joinToString(", ").orEmpty()
            val castMembers = meta.credits?.cast.orEmpty().take(20).mapNotNull { p ->
                val n = p.name ?: return@mapNotNull null
                PlayerUiState.CastMember(
                    name = n,
                    character = p.character.orEmpty(),
                    photoUrl = p.profile_path?.let { "${TmdbRepository.IMAGE_BASE}w185$it" },
                )
            }
            // Enrich the metadata used by the info overlay. No auto-splash — the
            // card is shown only on ↓ (user request).
            _uiState.update { s ->
                s.copy(
                    metadata = PlayerUiState.MetadataDisplay(
                        title = title, info = info,
                        overview = meta.overview ?: s.metadata?.overview ?: "",
                        cast = castLine,
                        posterUrl = TmdbRepository.posterUrl(meta.poster_path) ?: card.posterUrl,
                        details = details,
                        castMembers = castMembers,
                    ),
                )
            }
        }
    }

    /** Build the "Режиссёр / Сценарий / Жанры" text block (cast is shown as a photo row). */
    private fun buildDetails(meta: com.lampplayer.tv.data.tmdb.TmdbMetadata): String {
        val sb = StringBuilder()
        val crew = meta.credits?.crew.orEmpty()
        val directors = crew.filter { it.job == "Director" }.mapNotNull { it.name }.distinct()
        if (directors.isNotEmpty()) {
            sb.append("Режиссёр: ").append(directors.joinToString(", "))
        }
        val writers = crew.filter { it.job in setOf("Writer", "Screenplay", "Story") || it.department == "Writing" }
            .mapNotNull { it.name }.distinct().take(4)
        if (writers.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Сценарий: ").append(writers.joinToString(", "))
        }
        val genres = meta.genres.mapNotNull { it.name }
        if (genres.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("Жанры: ").append(genres.joinToString(", "))
        }
        return sb.toString()
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
        loadEpisodes(merged)
    }

    fun toggleMetadata() = _uiState.update { it.copy(showMetadata = !it.showMetadata) }

    // ─── Playback controls ─────────────────────────────────────────
    fun onProgress(currentMs: Long, durationMs: Long) {
        val cur = currentMs / 1000.0; val dur = durationMs / 1000.0
        val showSkip = settings.skipIntro && introSkipManager.shouldShowSkipButton(cur, _uiState.value.introEnd.takeIf { it > 0 })
        _uiState.update { it.copy(showSkipIntro = showSkip) }
        // Only offer auto-next when the player actually has a playable next episode
        // (balancer stream URLs aren't delivered, so the in-player playlist is empty
        // for externally-launched series — don't promise an auto-start we can't keep).
        if (settings.autonext && hasInPlayerNext() && !_uiState.value.infoOverlayVisible) {
            autoNextManager.checkAndStart(dur, cur, settings.autonextDelay, viewModelScope,
                onTick = { t -> _uiState.update { it.copy(autoNextCountdown = t) } },
                onNext = { viewModelScope.launch { navigateNext() } })
        }
    }

    /** True only when a next episode with a playable URL exists inside the player. */
    private fun hasInPlayerNext(): Boolean {
        val st = _uiState.value
        // Never auto-advance IPTV: channels are live, not an ordered episode list.
        if (st.card?.iptv == true) return false
        val next = nextPlayableEpisode() ?: return false
        return next.url.isNotBlank()
    }

    /** The episode after the one currently playing — located by URL so it's robust to
     *  index/filtering gaps between currentEpisodeIndex and the playlist list. */
    private fun nextPlayableEpisode(): EpisodeItem? {
        val eps = _uiState.value.episodes
        if (eps.isEmpty()) return null
        val pos = eps.indexOfFirst { it.url == currentUrl }.takeIf { it >= 0 }
            ?: _uiState.value.currentEpisodeIndex
        return eps.getOrNull(pos + 1)
    }

    private fun navigateNext() {
        // Clear the "next episode in N s" overlay — the countdown just fired.
        autoNextManager.cancel()
        _uiState.update { it.copy(autoNextCountdown = -1) }
        val next = nextPlayableEpisode()
        if (next != null && next.url.isNotBlank()) {
            selectEpisode(next)
        } else {
            // No resolved next inside the player → hand back to Lampa (it resolves+plays).
            viewModelScope.launch { _navigateToNext.emit(Unit) }
        }
    }

    fun skipIntro() {
        val end = _uiState.value.introEnd
        if (end > 0) { engSeekTo((end * 1000).toLong()); _uiState.update { it.copy(showSkipIntro = false) } }
    }

    fun markIntro() = markIntroAt(engPositionMs())

    /** Mark "intro ends at [ms]" (long-press OK); persists per show + shares to the DB. */
    fun markIntroAt(ms: Long) {
        val card = currentCard ?: return
        introSkipManager.markIntro(card, ms / 1000.0, viewModelScope) { saved ->
            _uiState.update { it.copy(introEnd = saved) }
        }
        card.tmdbId?.let { tmdb ->
            if (!card.iptv && IntroDb.enabled) viewModelScope.launch(Dispatchers.IO) {
                runCatching { IntroDb.submitIntroEndMs(tmdb, card.seasonNumber, card.episodeNumber, ms) }
            }
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

    // Last IPTV channel watched (for the "previous channel" toggle, TV-style).
    private var prevChannelIndex: Int? = null

    /** IPTV: jump straight to channel number N (1-based) typed on the remote. */
    fun zapToChannel(number: Int) {
        if (_uiState.value.card?.iptv != true) return
        _uiState.value.episodes.getOrNull(number - 1)?.let { selectEpisode(it) }
    }

    /** IPTV: flip back to the channel watched before the current one. */
    fun switchPreviousChannel() {
        val idx = prevChannelIndex ?: return
        _uiState.value.episodes.getOrNull(idx)?.let { selectEpisode(it) }
    }

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
        retryJob?.cancel()
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
                if (state == Player.STATE_READY) {
                    everReady = true
                    errorRecovery.reset()
                    if (_uiState.value.reconnecting || _uiState.value.hasError)
                        _uiState.update { it.copy(reconnecting = false, hasError = false) }
                    // Audio session exists only now — (re)attach the loudness boost + night mode.
                    if (currentBoost > 100) applyBoost(currentBoost)
                    runCatching { nightModeCtl.apply(player.audioSessionId, settings.nightMode) }
                }
                if (state == Player.STATE_BUFFERING && everReady) onRebuffer()
                if (state == Player.STATE_ENDED) {
                    viewModelScope.launch { saveCurrentPosition(ended = true) }
                    if (hasInPlayerNext()) viewModelScope.launch { navigateNext() }
                    else _uiState.update { it.copy(osdVisible = true) }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                updateMediaSession()
            }
            override fun onPlayerError(error: PlaybackException) {
                // Live edge fell behind (common after a stall on HLS/IPTV) → jump to live.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    runCatching { p.seekToDefaultPosition(); p.prepare() }
                    return
                }
                val action = errorRecovery.onError(error, p)
                if (action == ErrorRecoveryManager.Action.SHOW_FATAL) {
                    // AUTO mode: a decode/container failure is exactly what libVLC may handle.
                    if (settings.engine == EngineType.AUTO && !didAutoFallback && isDecodeError(error)) {
                        fallbackToVlc()
                    } else {
                        _uiState.update { it.copy(hasError = true, reconnecting = false, errorMessage = buildErrorMessage(error)) }
                    }
                } else {
                    // Retry with backoff. Network errors retry indefinitely (reconnecting),
                    // so a dropped mobile connection recovers on its own.
                    _uiState.update { it.copy(isLoading = true, reconnecting = errorRecovery.lastWasNetwork, hasError = false) }
                    retryJob?.cancel()
                    retryJob = viewModelScope.launch {
                        delay(errorRecovery.retryDelayMs)
                        if (!usingVlc) runCatching { p.prepare() }
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
        val parts = mutableListOf<String>()
        if (vfmt != null) {
            resolutionLabel(vfmt.width, vfmt.height)?.let { parts.add(it) }
            if (vfmt.frameRate > 0) parts.add("%.0f fps".format(vfmt.frameRate))
            codecLabel(vfmt.codecs)?.let { parts.add(it) }
            if (vfmt.bitrate > 0) parts.add("%.1f Мбит/с".format(vfmt.bitrate / 1_000_000f))
        }
        if (afmt != null) {
            val a = buildString {
                codecLabel(afmt.codecs)?.let { append(it) }
                if (afmt.channelCount == 1) append(" Моно")
                else if (afmt.channelCount == 2) append(" Стерео")
                else if (afmt.channelCount > 2) append(" ${afmt.channelCount}.0")
            }.trim()
            if (a.isNotBlank()) parts.add(a)
        }
        val info = parts.joinToString("  ·  ")
        val aspect = if (vfmt != null && vfmt.width > 0 && vfmt.height > 0) {
            val par = if (vfmt.pixelWidthHeightRatio > 0f) vfmt.pixelWidthHeightRatio else 1f
            (vfmt.width * par) / vfmt.height
        } else 0f
        val fps = vfmt?.frameRate?.takeIf { it > 0f } ?: 0f
        _uiState.update { it.copy(videoInfo = info, videoAspect = aspect, videoFps = fps) }
    }

    /** Map a height to a familiar resolution label (2160p / 1080p / 720p …). */
    private fun resolutionLabel(w: Int, h: Int): String? = when {
        h <= 0 || w <= 0 -> null
        h >= 2000 -> "4K"
        h >= 1400 -> "1440p"
        h >= 1000 -> "1080p"
        h >= 700 -> "720p"
        h >= 540 -> "576p"
        else -> "${h}p"
    }

    /** Turn a raw codec string (avc1.640028, hev1…, mp4a.40.2) into a friendly name. */
    private fun codecLabel(codecs: String?): String? {
        val c = codecs?.lowercase()?.trim().orEmpty()
        return when {
            c.isBlank() -> null
            c.startsWith("avc") || c.contains("h264") -> "H.264"
            c.startsWith("hev") || c.startsWith("hvc") || c.contains("h265") -> "HEVC"
            c.startsWith("av01") -> "AV1"
            c.startsWith("vp9") -> "VP9"
            c.startsWith("vp09") -> "VP9"
            c.startsWith("mp4a") || c.contains("aac") -> "AAC"
            c.startsWith("ec-3") || c.contains("eac3") -> "E-AC3"
            c.startsWith("ac-3") || c.contains("ac3") -> "AC3"
            c.contains("dts") -> "DTS"
            c.contains("opus") -> "Opus"
            c.contains("mp3") -> "MP3"
            else -> c.substringBefore('.').uppercase()
        }
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
                if (usingVlc || !::player.isInitialized) continue
                if (player.isPlaying && player.currentPosition > 0 && player.duration != C.TIME_UNSET)
                    saveCurrentPosition()
            }
        }
    }

    private fun startDiag() {
        diagJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (usingVlc || !::player.isInitialized) continue
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
        saveJob?.cancel(); diagJob?.cancel(); osdHideJob?.cancel(); vlcPollJob?.cancel(); sleepJob?.cancel(); metaJob?.cancel(); episodesJob?.cancel(); epgJob?.cancel(); retryJob?.cancel()
        releaseLoudness()
        runCatching { nightModeCtl.release() }
        stopNetworkMonitor()
        releaseMediaSession()
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
