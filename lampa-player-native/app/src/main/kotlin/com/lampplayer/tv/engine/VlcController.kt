package com.lampplayer.tv.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import com.lampplayer.tv.domain.model.ExternalSubtitle
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC-backed playback engine (fallback for formats ExoPlayer can't handle).
 *
 * API NOTES — targets the libVLC-Android 3.x line (org.videolan.android:libvlc-all:3.7.2):
 *  - Track selection uses the 3.x [MediaPlayer.TrackDescription] / setAudioTrack / setSpuTrack
 *    API. libVLC 4.x replaced this with a different track API, so do NOT bump to a 4.x EAP
 *    without revisiting [audioTracks]/[subtitleTracks]/[selectAudio]/[selectSubtitle].
 *  - [MediaPlayer.setSpuDelay] takes MICROseconds; we convert from ms.
 *  - Hardware decoding (needed for smooth 4K) is requested via [Media.setHWDecoderEnabled];
 *    actual HDR tone-mapping support is device/SoC dependent (see README).
 */
class VlcController(
    context: Context,
    private val networkCachingMs: Int,
    private val listener: EngineListener,
) : MediaEngine {

    private val libVlc: LibVLC
    private val mediaPlayer: MediaPlayer
    private var lastDuration: Long = -1L

    // libVLC doesn't manage audio focus (ExoPlayer does) — request/abandon it ourselves
    // so we pause when another app needs sound instead of playing on top of it.
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying) { pausedByFocusLoss = true; pause() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) { pausedByFocusLoss = false; play() }
            }
        }
    }

    private fun requestAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }
    }

    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        }
    }

    init {
        val options = arrayListOf(
            "--network-caching=$networkCachingMs",
            "--file-caching=$networkCachingMs",
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--avcodec-hw=mediacodec",   // prefer hardware MediaCodec decoding
            "--audio-time-stretch",
            "--subsdec-encoding=UTF-8",
        )
        libVlc = LibVLC(context, options)
        mediaPlayer = MediaPlayer(libVlc).apply {
            setEventListener { event -> dispatch(event) }
        }
    }

    /** Attach the VLC render surface. Call before [setMedia]. */
    fun attachViews(view: VLCVideoLayout, subtitlesEnabled: Boolean = true) {
        mediaPlayer.attachViews(view, null, subtitlesEnabled, false)
    }

    fun detachViews() = mediaPlayer.detachViews()

    /**
     * Load and start playback. Headers map User-Agent / Referer onto VLC's HTTP options
     * (libVLC's http module does not accept arbitrary custom headers).
     */
    fun setMedia(
        context: Context,
        url: String,
        headers: Map<String, String>,
        startMs: Long,
        subtitles: List<ExternalSubtitle>,
        hardwareDecode: Boolean,
    ) {
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(hardwareDecode, false)
            addOption(":network-caching=$networkCachingMs")
            headers.entries.firstOrNull { it.key.equals("User-Agent", true) }
                ?.let { addOption(":http-user-agent=${it.value}") }
            headers.entries.firstOrNull { it.key.equals("Referer", true) || it.key.equals("Referrer", true) }
                ?.let { addOption(":http-referrer=${it.value}") }
            if (startMs > 0) addOption(":start-time=${startMs / 1000}")
        }
        mediaPlayer.media = media
        media.release()

        subtitles.forEach { sub ->
            runCatching {
                mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(sub.uri), sub.enabled)
            }
        }
        requestAudioFocus()
        mediaPlayer.play()
    }

    fun stop() = mediaPlayer.stop()

    private fun dispatch(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> listener.onPlaying()
            MediaPlayer.Event.Paused -> listener.onPaused()
            MediaPlayer.Event.Buffering -> listener.onBuffering(event.buffering)
            MediaPlayer.Event.EndReached -> listener.onEnded()
            MediaPlayer.Event.EncounteredError -> listener.onError("libVLC playback error")
            MediaPlayer.Event.LengthChanged -> lastDuration = event.lengthChanged
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> listener.onTracksChanged()
        }
    }

    // ─── MediaEngine ───────────────────────────────────────────────

    override val isPlaying: Boolean get() = mediaPlayer.isPlaying
    override val positionMs: Long get() = mediaPlayer.time
    override val durationMs: Long
        get() = mediaPlayer.length.takeIf { it > 0 } ?: lastDuration
    override val bufferedMs: Long get() = positionMs // libVLC 3.x exposes no precise buffered edge

    override fun play() { mediaPlayer.play() }
    override fun pause() { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
    override fun seekTo(ms: Long) { mediaPlayer.time = ms.coerceAtLeast(0) }
    override fun setRate(rate: Float) { mediaPlayer.rate = rate }

    /** libVLC supports software gain up to 200% via the audio volume (0..200). */
    fun setVolume(percent: Int) { runCatching { mediaPlayer.setVolume(percent.coerceIn(0, 200)) } }

    /** Content frame rate from the active video track (0 if unknown) — for AFR. */
    fun videoFps(): Float = runCatching {
        val t = mediaPlayer.currentVideoTrack ?: return@runCatching 0f
        if (t.frameRateDen > 0) t.frameRateNum.toFloat() / t.frameRateDen else 0f
    }.getOrDefault(0f)

    override fun audioTracks(): List<EngineTrack> =
        mediaPlayer.audioTracks?.map { EngineTrack(it.id, it.name) } ?: emptyList()

    override fun subtitleTracks(): List<EngineTrack> =
        mediaPlayer.spuTracks?.map { EngineTrack(it.id, it.name) } ?: emptyList()

    override fun selectAudio(id: Int) { runCatching { mediaPlayer.setAudioTrack(id) } }
    override fun selectSubtitle(id: Int) { runCatching { mediaPlayer.setSpuTrack(id) } }

    /** Currently-selected track ids — used to verify a switch actually took effect. */
    fun currentAudioTrackId(): Int = runCatching { mediaPlayer.audioTrack }.getOrDefault(-99)
    fun currentSpuTrackId(): Int = runCatching { mediaPlayer.spuTrack }.getOrDefault(-99)

    override fun setAspectRatio(ratio: String?) { mediaPlayer.setAspectRatio(ratio) }
    override fun setScale(scale: Float) { mediaPlayer.setScale(scale) }
    override fun setSubtitleDelayMs(delayMs: Long) { mediaPlayer.setSpuDelay(delayMs * 1000) }

    override fun release() {
        abandonAudioFocus()
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.detachViews() }
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }
}
