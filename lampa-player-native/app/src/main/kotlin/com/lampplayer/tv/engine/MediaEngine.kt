package com.lampplayer.tv.engine

/**
 * Minimal abstraction over a playback engine so the player UI can drive either
 * ExoPlayer (default) or libVLC (fallback) through the same controls.
 *
 * Only the generic operations the OSD needs are exposed here; engine-specific
 * concerns (ExoPlayer track-memory, VLC media options) stay inside each impl.
 */
interface MediaEngine {
    val isPlaying: Boolean
    /** Current position in milliseconds. */
    val positionMs: Long
    /** Total duration in milliseconds, or -1 when unknown/live. */
    val durationMs: Long
    /** Buffered position in milliseconds (best effort). */
    val bufferedMs: Long

    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setRate(rate: Float)

    fun audioTracks(): List<EngineTrack>
    fun subtitleTracks(): List<EngineTrack>
    fun selectAudio(id: Int)
    fun selectSubtitle(id: Int)

    /** e.g. "16:9", "4:3", null for auto. */
    fun setAspectRatio(ratio: String?)
    fun setScale(scale: Float)
    /** Positive delays subtitles, negative advances them (milliseconds). */
    fun setSubtitleDelayMs(delayMs: Long)

    fun release()
}

/** A selectable audio or subtitle track. id == -1 conventionally means "disabled". */
data class EngineTrack(val id: Int, val name: String)

/** Persisted player-engine choice (stored in [com.lampplayer.tv.data.datastore.AppSettings.engine]). */
object EngineType {
    /** ExoPlayer first, automatically retry with libVLC on a fatal decode/container error. */
    const val AUTO = "auto"
    const val EXOPLAYER = "exoplayer"
    const val VLC = "vlc"

    val ALL = listOf(AUTO, EXOPLAYER, VLC)
    fun normalize(value: String?): String = if (value in ALL) value!! else AUTO
}

/** Playback lifecycle callbacks delivered on the engine's event thread. */
interface EngineListener {
    fun onPlaying() {}
    fun onPaused() {}
    fun onBuffering(percent: Float) {}
    fun onEnded() {}
    fun onError(message: String) {}
    fun onTracksChanged() {}
}
