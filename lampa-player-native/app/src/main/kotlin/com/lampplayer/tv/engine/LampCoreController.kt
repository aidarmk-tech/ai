package com.lampplayer.tv.engine

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import com.lampplayer.tv.domain.model.ExternalSubtitle
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Own loading/decode loops, audio clock and scheduling; no ExoPlayer/libVLC playback.
 * Android extracts files; Media3 container extractors demux HLS packets only.
 * HTTP reads happen on a separate, bounded demux thread, not the rendering thread.
 */
@UnstableApi
class LampCoreController(context: Context, private val listener: EngineListener) : MediaEngine {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "LampCore-render") }
    private val generation = AtomicInteger()
    private val seekRequest = AtomicReference<Long?>(null)
    private val audioRequest = AtomicInteger(-1)
    @Volatile private var surface: Surface? = null
    @Volatile private var requestedPlay = true
    @Volatile private var released = false
    @Volatile private var request: Request? = null
    @Volatile private var demux: Demux? = null
    @Volatile private var rate = 1f
    @Volatile private var volume = 1f
    @Volatile private var tracks = emptyList<EngineTrack>()
    @Volatile private var decoderName = "—"
    @Volatile private var queuedUntilUs = 0L
    @Volatile private var dropped = 0L
    @Volatile private var rendered = 0L
    @Volatile private var underruns = 0
    @Volatile var videoFps = 0f
        private set
    @Volatile var videoAspect = 16f / 9f
        private set
    @Volatile override var isPlaying = false
        private set
    @Volatile override var positionMs = 0L
        private set
    @Volatile override var durationMs = -1L
        private set
    override val bufferedMs: Long get() = maxOf(positionMs, queuedUntilUs / 1000)

    private data class Request(val url: String, val headers: Map<String, String>, val startMs: Long)
    private data class Sample(val track: Int, val bytes: ByteArray, val ptsUs: Long, val flags: Int)
    private data class Description(val videoId: Int, val video: MediaFormat, val audioId: Int, val audio: MediaFormat?)

    @Volatile private var control = newControl()
    @Volatile var currentAudioTrackId = -1
        private set
    val currentSubtitleTrackId: Int get() = subtitles.selectedId
    val subtitleText: String get() = subtitles.text()
    private val subtitles = LampCoreSubtitles(this.context, { control }, { positionMs * 1000 },
        { event(generation.get()) { listener.onTracksChanged() } },
        { event(generation.get()) { listener.onNotice("Не удалось загрузить субтитры") } })
    private fun newControl() = HlsControl().also {
        it.positionUs = { positionMs * 1000 }
        it.bufferedUs = { (bufferedMs - positionMs).coerceAtLeast(0) * 1000 }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val wasPlaying = requestedPlay
                pause()
                pausedByFocus = wasPlaying
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; play() }
        }
    }

    fun setSurface(value: Surface?) { surface = value }

    fun setMedia(url: String, headers: Map<String, String>, startMs: Long, subtitleSources: List<ExternalSubtitle> = emptyList()) {
        if (released) return
        demux?.close()
        val token = generation.incrementAndGet()
        val next = Request(url, headers.toMap(), startMs.coerceAtLeast(0))
        request = next
        seekRequest.set(null); audioRequest.set(-1); currentAudioTrackId = -1
        control = newControl(); subtitles.configure(subtitleSources, headers)
        positionMs = next.startMs; durationMs = -1; queuedUntilUs = next.startMs * 1000
        tracks = emptyList(); rendered = 0; dropped = 0; decoderName = "—"; underruns = 0
        requestedPlay = true; isPlaying = false
        acquireFocus()
        worker.execute { runMedia(next, token) }
    }

    fun retry() { requestedPlay = true; seekRequest.set(positionMs); request?.let { if (!isPlaying) { demux?.close(); val token = generation.incrementAndGet(); worker.execute { runMedia(it.copy(startMs = positionMs), token) } } } }
    fun stop() { generation.incrementAndGet(); demux?.close(); isPlaying = false }
    fun setVolume(percent: Int) { volume = percent.coerceIn(0, 100) / 100f }
    fun diagnostics(): String = "LampCore · ${demux?.mode ?: "файл"} · $decoderName\n" +
        "Кадры $rendered · пропущено $dropped · сбои звука $underruns\n" +
        "ABR ${control.abr.estimate() / 1000} кбит/с · смен ${control.switches} · восстановлений ${control.recoveries + (demux?.networkRetries ?: 0)}\n" +
        "Очередь %.1fс · HTTP %.1f МБ".format(
            ((bufferedMs - positionMs).coerceAtLeast(0)) / 1000.0,
            (demux?.downloadedBytes ?: 0) / 1048576.0,
        )

    override fun play() { if (!released) { acquireFocus(); requestedPlay = true } }
    override fun pause() { pausedByFocus = false; requestedPlay = false }
    override fun seekTo(ms: Long) { seekRequest.set(if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)) }
    override fun setRate(rate: Float) { this.rate = rate.coerceIn(0.5f, 2f) }
    override fun audioTracks(): List<EngineTrack> = tracks
    override fun subtitleTracks(): List<EngineTrack> = subtitles.tracks()
    override fun selectAudio(id: Int) { if (tracks.any { it.id == id }) { audioRequest.set(id); control.audioId = id; seekRequest.set(positionMs) } }
    override fun selectSubtitle(id: Int) { subtitles.select(id) }
    override fun setAspectRatio(ratio: String?) { /* Activity sizes SurfaceView */ }
    override fun setScale(scale: Float) { /* Activity sizes SurfaceView */ }
    override fun setSubtitleDelayMs(delayMs: Long) { subtitles.delayMs = delayMs.coerceIn(-60_000, 60_000) }

    override fun release() {
        released = true
        stop()
        subtitles.close()
        worker.shutdownNow()
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
    }

    private fun acquireFocus() {
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(focusListener, main).build().also { focusRequest = it }
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) requestedPlay = false
    }

    private fun alive(token: Int): Boolean = !released && token == generation.get()
    private fun event(token: Int, action: () -> Unit) { main.post { if (alive(token)) action() } }

    private fun runMedia(media: Request, token: Int) {
        if (Build.VERSION.SDK_INT < 23) {
            event(token) { listener.onError("LampCore требует Android 6.0 или новее") }; return
        }
        val path = Uri.parse(media.url).path.orEmpty().lowercase()
        if (path.endsWith(".mpd")) {
            event(token) { listener.onError("LampCore: DASH пока не поддерживается; выберите ExoPlayer") }; return
        }
        var startMs = media.startMs
        try {
            while (alive(token)) {
                seekRequest.getAndSet(null)?.let { startMs = it }
                while (alive(token) && surface?.isValid != true) Thread.sleep(20)
                if (!alive(token)) break
                val target = surface ?: continue
                if (!target.isValid) continue
                startMs = playSession(media, token, startMs, target)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // Do not expose signed media URLs/tokens through platform exception text.
            event(token) {
                isPlaying = false
                val message = if (e is CoreException || e is HlsException) e.message else "${e.javaClass.simpleName}: поток не поддержан или соединение прервано"
                listener.onError("LampCore: $message. Можно выбрать ExoPlayer/libVLC.")
            }
        }
    }

    private class CoreException(message: String) : IOException(message)

    /** Resources are owned and released exclusively on the rendering worker. */
    private fun playSession(media: Request, token: Int, startMs: Long, output: Surface): Long {
        control.ready = false; control.adaptive = false; control.timestampOffsets.clear()
        subtitles.refreshHls()
        val input = Demux(media, token, startMs)
        demux = input
        var video: MediaCodec? = null
        var audio: MediaCodec? = null
        var sink: AudioTrack? = null
        var pendingVideo = -1
        var pendingAudio = -1
        var pendingAudioBuffer: ByteBuffer? = null
        try {
            event(token) { listener.onBuffering(0f) }
            input.thread.start()
            var startupNs = System.nanoTime()
            while (alive(token) && input.description == null && input.error == null) {
                if (input.recovering) startupNs = System.nanoTime()
                if (seekRequest.get() != null) return positionMs
                if (System.nanoTime() - startupNs > 90_000_000_000L) throw CoreException("Не удалось получить дорожки за 90 секунд")
                Thread.sleep(5)
            }
            input.error?.let { throw it }
            if (!alive(token)) return positionMs
            val desc = input.description ?: throw CoreException("Не удалось прочитать контейнер")
            val name = hardwareDecoder(desc.video) ?: throw CoreException("Нет аппаратного декодера для ${desc.video.getString(MediaFormat.KEY_MIME)}")
            val mime = desc.video.getString(MediaFormat.KEY_MIME)!!
            val caps = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.first { it.name == name }.getCapabilitiesForType(mime)
            control.videoMime = mime
            control.supportedSize = { v -> v.width > 0 && v.height > 0 && v.width <= 1920 && v.height <= 1080 &&
                runCatching { caps.videoCapabilities.isSizeSupported(v.width, v.height) }.getOrDefault(false) }
            // Reconfigure at a segment boundary on codecs without adaptive playback.
            control.adaptive = mime in listOf("video/avc", "video/hevc") && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
            if (control.adaptive) {
                val maxWidth = maxOf(desc.video.getInteger(MediaFormat.KEY_WIDTH), 1920)
                val maxHeight = maxOf(desc.video.getInteger(MediaFormat.KEY_HEIGHT), 1080)
                desc.video.setInteger(MediaFormat.KEY_MAX_WIDTH, maxWidth)
                desc.video.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxHeight)
            }
            var configuredVideo = MediaCodec.createByCodecName(name)
            try { configuredVideo.configure(desc.video, output, null, 0) }
            catch (e: Exception) {
                configuredVideo.release()
                if (!control.adaptive) throw e
                control.adaptive = false
                desc.video.setInteger(MediaFormat.KEY_MAX_WIDTH, desc.video.getInteger(MediaFormat.KEY_WIDTH))
                desc.video.setInteger(MediaFormat.KEY_MAX_HEIGHT, desc.video.getInteger(MediaFormat.KEY_HEIGHT))
                configuredVideo = MediaCodec.createByCodecName(name)
                try { configuredVideo.configure(desc.video, output, null, 0) }
                catch (fallback: Exception) { configuredVideo.release(); throw fallback }
            }
            video = configuredVideo
            video.start()
            control.ready = true
            decoderName = name
            audio = desc.audio?.let { fmt -> MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                .also { it.configure(fmt, null, null, 0); it.start() } }
            durationMs = input.durationOverrideUs?.let { if (it < 0) -1 else it / 1000 } ?: (formatDuration(desc.video) / 1000)
            videoFps = runCatching { desc.video.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(0f)
            videoAspect = desc.video.getInteger(MediaFormat.KEY_WIDTH).toFloat() / desc.video.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(1)
            tracks = input.audioTracks
            event(token) { listener.onTracksChanged() }

            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            val head = AudioFrameCounter()
            val videoTiming = LampCoreVideoTiming(videoFps)
            var sampleRate = 0
            var frameBytes = 0
            var submittedFrames = 0L
            var submittedBytes = 0L
            var pcm: LampCorePcm? = null
            var audioBaseUs = Long.MIN_VALUE
            var wallBaseUs = startMs * 1000
            var wallBaseNs = System.nanoTime()
            var wallStarted = false
            var wallRate = rate
            var videoInputEnd = false
            var audioInputEnd = audio == null
            var videoEnd = false
            var audioEnd = audio == null
            var playing = false
            var buffering = true
            var sinkStarted = false
            var appliedRate = -1f
            var lastFrames = 0L
            var lastProgressNs = System.nanoTime()
            var videoLastUs = startMs * 1000
            var lastOutputNs = System.nanoTime()
            var audioTailClock = false

            fun signalBuffering(value: Boolean) {
                if (value != buffering) { buffering = value; event(token) { listener.onBuffering(if (value) 0f else 100f) } }
            }

            while (alive(token)) {
                input.error?.let { throw it }
                if (seekRequest.get() != null || surface !== output || !output.isValid) return positionMs
                if (playing != requestedPlay) {
                    playing = requestedPlay
                    if (playing) {
                        if (sinkStarted) sink?.play()
                        wallBaseUs = positionMs * 1000; wallBaseNs = System.nanoTime()
                        event(token) { listener.onPlaying() }
                    } else { sink?.pause(); event(token) { listener.onPaused() } }
                    isPlaying = playing
                }
                if (!playing) { Thread.sleep(10); continue }
                if (sink != null && appliedRate != rate) {
                    sink.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
                    // setPlaybackParams can start a paused track. Keep the refill gate closed.
                    if (!sinkStarted) sink.pause()
                    appliedRate = rate
                }
                sink?.setVolume(volume)
                val drainedFrames = sink?.let { head.update(it.playbackHeadPosition) } ?: 0L
                if (sinkStarted && !audioEnd && submittedFrames > 0 && drainedFrames >= submittedFrames) {
                    sink?.pause(); sinkStarted = false
                }
                if (wallRate != rate) {
                    val changeNs = System.nanoTime()
                    if (wallStarted) wallBaseUs += ((changeNs - wallBaseNs) / 1000 * wallRate).toLong()
                    wallBaseNs = changeNs; wallRate = rate
                }

                // Separate queues prevent a full video decoder from starving audio.
                fun feed(codec: MediaCodec, queue: ArrayBlockingQueue<Sample>) {
                    repeat(4) {
                        val sample = queue.peek() ?: return
                        val index = codec.dequeueInputBuffer(0)
                        if (index < 0) return
                        val buffer = codec.getInputBuffer(index) ?: throw CoreException("Нет входного буфера декодера")
                        buffer.clear()
                        if (sample.bytes.size > buffer.remaining()) throw CoreException("Кадр превышает буфер аппаратного декодера")
                        buffer.put(sample.bytes)
                        // Extractor SAMPLE_FLAG_* are not MediaCodec BUFFER_FLAG_*.
                        val flags = if (sample.flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        codec.queueInputBuffer(index, 0, sample.bytes.size, sample.ptsUs, flags)
                        input.remove(queue)
                    }
                }
                feed(video, input.videoSamples)
                audio?.let { feed(it, input.audioSamples) }
                if (input.ended && input.samplesEmpty) {
                    if (!videoInputEnd) {
                        val index = video.dequeueInputBuffer(0)
                        if (index >= 0) { video.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); videoInputEnd = true }
                    }
                    if (!audioInputEnd && audio != null) {
                        val index = audio.dequeueInputBuffer(0)
                        if (index >= 0) { audio.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); audioInputEnd = true }
                    }
                }

                if (audio != null && !audioEnd) {
                    if (pendingAudio < 0) {
                        val index = audio.dequeueOutputBuffer(audioInfo, 0)
                        when {
                            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (sink != null) { seekRequest.compareAndSet(null, positionMs); return positionMs }
                                val fmt = audio.outputFormat
                                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                if (channels !in 1..8) throw CoreException("Не поддержано число аудиоканалов: $channels")
                                val sourceEncoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                if (sourceEncoding !in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) throw CoreException("Не поддержан формат PCM")
                                pcm = LampCorePcm(channels, sourceEncoding == AudioFormat.ENCODING_PCM_FLOAT,
                                    if (fmt.containsKey("channel-mask")) fmt.getInteger("channel-mask") else 0)
                                frameBytes = pcm!!.outputFrameBytes
                                val encoding = AudioFormat.ENCODING_PCM_16BIT
                                val mask = if (pcm!!.outputChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                                val min = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
                                if (min <= 0) throw CoreException("Аудиоформат не поддерживается устройством")
                                sink = AudioTrack.Builder()
                                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask).setEncoding(encoding).build())
                                    .setBufferSizeInBytes(maxOf(min, sampleRate * frameBytes / 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
                                if (sink.state != AudioTrack.STATE_INITIALIZED) throw CoreException("Не удалось открыть аудиовыход")
                                appliedRate = -1f
                            }
                            index >= 0 -> {
                                pendingAudio = index
                                val buffer = audio.getOutputBuffer(index) ?: throw CoreException("Нет аудиобуфера")
                                buffer.position(audioInfo.offset); buffer.limit(audioInfo.offset + audioInfo.size)
                                // Seeking lands on an earlier keyframe. Trim PCM to requested time.
                                if (sampleRate > 0 && audioInfo.presentationTimeUs < startMs * 1000) {
                                    val skipFrames = (startMs * 1000 - audioInfo.presentationTimeUs) * sampleRate / 1_000_000
                                    buffer.position(buffer.position() + minOf(buffer.remaining().toLong(), skipFrames * (pcm?.inputFrameBytes ?: frameBytes)).toInt())
                                }
                                if (audioBaseUs == Long.MIN_VALUE && buffer.hasRemaining()) {
                                    audioBaseUs = audioInfo.presentationTimeUs + (buffer.position() - audioInfo.offset) / (pcm?.inputFrameBytes ?: frameBytes) * 1_000_000L / sampleRate
                                }
                                pendingAudioBuffer = pcm?.convert(buffer) ?: buffer
                            }
                        }
                    }
                    if (pendingAudio >= 0) {
                        val buffer = pendingAudioBuffer ?: throw CoreException("Нет сохранённого аудиобуфера")
                        if (buffer.hasRemaining()) {
                            val target = sink ?: throw CoreException("Аудиодекодер не сообщил выходной формат")
                            val written = target.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                            if (written < 0) throw CoreException("Ошибка аудиовыхода ($written)")
                            submittedBytes += written
                            submittedFrames = submittedBytes / frameBytes
                        }
                        if (!buffer.hasRemaining()) {
                            audioEnd = audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            audio.releaseOutputBuffer(pendingAudio, false); pendingAudio = -1; pendingAudioBuffer = null
                        }
                    }
                }

                val now = System.nanoTime()
                val frames = sink?.let { head.update(it.playbackHeadPosition) } ?: 0L
                if (frames != lastFrames) { lastProgressNs = now; lastFrames = frames }
                val target = sink
                if (target != null && !sinkStarted && LampCoreTiming.audioReady(
                        submittedFrames - frames, sampleRate, target.bufferSizeInFrames, rate, audioEnd)) {
                    target.play(); sinkStarted = true
                }
                val audioWaiting = audio != null && !audioEnd && (!sinkStarted || audioBaseUs == Long.MIN_VALUE || frames >= submittedFrames)
                if (audio != null && audioEnd && frames >= submittedFrames && !audioTailClock) {
                    wallBaseUs = if (audioBaseUs != Long.MIN_VALUE) audioBaseUs + frames * 1_000_000 / sampleRate.coerceAtLeast(1) else startMs * 1000
                    wallBaseNs = now; wallStarted = true; audioTailClock = true
                }
                var clockUs = if (audio != null && !audioTailClock && audioBaseUs != Long.MIN_VALUE)
                    audioBaseUs + frames * 1_000_000 / sampleRate.coerceAtLeast(1)
                else if ((audio == null || audioTailClock) && wallStarted) wallBaseUs + ((now - wallBaseNs) / 1000 * wallRate).toLong()
                else startMs * 1000
                positionMs = (clockUs / 1000).coerceAtLeast(startMs)
                if (Build.VERSION.SDK_INT >= 24) underruns = sink?.underrunCount ?: 0
                queuedUntilUs = maxOf(clockUs, input.queuedUntilUs)

                if (pendingVideo < 0 && !videoEnd) {
                    val index = video.dequeueOutputBuffer(videoInfo, 0)
                    if (index >= 0) pendingVideo = index
                    else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val fmt = video.outputFormat
                        val width = if (fmt.containsKey("crop-right")) fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1 else fmt.getInteger(MediaFormat.KEY_WIDTH)
                        val height = if (fmt.containsKey("crop-bottom")) fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1 else fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        videoAspect = width.toFloat() / height.coerceAtLeast(1)
                        event(token) { listener.onTracksChanged() }
                    }
                }
                if (pendingVideo >= 0) {
                    val pts = videoInfo.presentationTimeUs
                    val eos = videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos && videoInfo.size == 0) {
                        video.releaseOutputBuffer(pendingVideo, false); pendingVideo = -1; videoEnd = true
                    } else if (pts < startMs * 1000) {
                        video.releaseOutputBuffer(pendingVideo, false); pendingVideo = -1
                    } else if (!audioWaiting) {
                        if (audio == null && !wallStarted) {
                            wallBaseUs = pts; wallBaseNs = now; wallStarted = true
                            clockUs = pts; positionMs = pts / 1000
                        }
                        val release = videoTiming.plan(pts, clockUs, now, rate)
                        if (release.action <= 0) {
                            if (release.action < 0) { video.releaseOutputBuffer(pendingVideo, false); dropped++ }
                            else {
                                video.releaseOutputBuffer(pendingVideo, release.timeNs)
                                videoTiming.rendered(pts, release.timeNs)
                                rendered++; videoLastUs = pts
                            }
                            pendingVideo = -1; videoEnd = eos; lastOutputNs = now
                            signalBuffering(false)
                        }
                    }
                }
                if (audioWaiting && now - lastProgressNs > 250_000_000L) signalBuffering(true)
                else if (audio != null && !audioWaiting) signalBuffering(false)
                // With no audio, freeze the wall clock when the video queue runs dry.
                if (audio == null && !videoEnd && pendingVideo < 0 && input.samplesEmpty && now - lastOutputNs > 250_000_000L) {
                    wallStarted = false; positionMs = videoLastUs / 1000; signalBuffering(true)
                }
                if (videoEnd && audioEnd && (sink == null || frames >= submittedFrames)) {
                    isPlaying = false; requestedPlay = false
                    positionMs = if (durationMs > 0) durationMs else clockUs / 1000
                    event(token) { listener.onBuffering(100f); listener.onEnded() }
                    // Retain the final image and wait for a seek, new media, or explicit replay.
                    while (alive(token) && seekRequest.get() == null && !requestedPlay && surface === output) Thread.sleep(20)
                    if (requestedPlay && seekRequest.get() == null) seekRequest.set(0)
                    return positionMs
                }
                Thread.sleep(2)
            }
            return positionMs
        } finally {
            input.close()
            runCatching { sink?.pause() }; runCatching { sink?.flush() }; runCatching { sink?.release() }
            runCatching { audio?.stop() }; runCatching { audio?.release() }
            runCatching { video?.stop() }; runCatching { video?.release() }
            if (demux === input) demux = null
            // A cancelled HTTP request wakes the reader; don't block the UI on a join.
            runCatching { input.thread.join(1000) }
        }
    }

    private fun formatDuration(format: MediaFormat): Long =
        runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1000L)

    private fun hardwareDecoder(format: MediaFormat): String? = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .firstOrNull { info ->
            if (info.isEncoder) false else {
                val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else {
                    val name = info.name.lowercase()
                    !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.")
                }
                hardware && runCatching { info.getCapabilitiesForType(format.getString(MediaFormat.KEY_MIME)!!).isFormatSupported(format) }.getOrDefault(false)
            }
        }?.name

    /** The only owner of MediaExtractor. Queue memory is capped at 4 MiB + one sample. */
    private inner class Demux(private val media: Request, private val token: Int, private val startMs: Long) {
        val videoSamples = ArrayBlockingQueue<Sample>(48)
        val audioSamples = ArrayBlockingQueue<Sample>(96)
        val samplesEmpty: Boolean get() = videoSamples.isEmpty() && audioSamples.isEmpty()
        @Volatile var audioTracks = emptyList<EngineTrack>()
        private val bytes = AtomicLong()
        @Volatile private var closed = false
        @Volatile var source: LampCoreHttpSource? = null
        @Volatile private var hlsHttp: LampCoreHlsHttp? = null
        @Volatile private var hls: LampCoreHlsLoader? = null
        @Volatile var mode = "файл"
        @Volatile var durationOverrideUs: Long? = null
        val recovering: Boolean get() = hlsHttp?.isRecovering == true
        val networkRetries: Long get() = hlsHttp?.recoveries?.get() ?: 0
        val downloadedBytes: Long get() = hlsHttp?.downloadedBytes ?: source?.downloadedBytes ?: 0
        @Volatile var description: Description? = null
        @Volatile var error: Exception? = null
        @Volatile var ended = false
        @Volatile var queuedUntilUs = startMs * 1000
        private var videoUntilUs = startMs * 1000
        private var audioUntilUs = startMs * 1000
        val thread = Thread({ read() }, "LampCore-demux")

        fun remove(queue: ArrayBlockingQueue<Sample>) { queue.poll()?.let { bytes.addAndGet(-it.bytes.size.toLong()) } }
        fun close() { closed = true; source?.close(); hlsHttp?.close(); hls?.cancel(); thread.interrupt() }
        private fun running() = !closed && alive(token) && error == null && seekRequest.get() == null

        private fun enqueue(id: Int, data: ByteArray, timeUs: Long, flags: Int) {
            if (data.size > 4 * 1024 * 1024) throw CoreException("Слишком большой кадр")
            val queue = if (id == 0 && hls != null || hls == null && id == description?.videoId) videoSamples else audioSamples
            while (running()) {
                val current = bytes.get()
                if (queue.remainingCapacity() > 0 && current + data.size <= 4 * 1024 * 1024 && bytes.compareAndSet(current, current + data.size)) {
                    try { queue.put(Sample(id, data, timeUs, flags)) }
                    catch (e: Exception) { bytes.addAndGet(-data.size.toLong()); throw e }
                    synchronized(this) {
                        if (queue === videoSamples) videoUntilUs = maxOf(videoUntilUs, timeUs) else audioUntilUs = maxOf(audioUntilUs, timeUs)
                        queuedUntilUs = if (description?.audio == null) videoUntilUs else minOf(videoUntilUs, audioUntilUs)
                    }
                    return
                }
                Thread.sleep(5)
            }
        }

        private fun readHls(http: LampCoreHlsHttp, playlist: HlsPlaylist) {
            val formats = mutableMapOf<Int, Format>()
            var expectedAudio: Boolean? = null
            var externalAudio = false
            var selected = -1
            val transition = HlsVideoTransition()
            fun tracksChanged() {
                if (!externalAudio) audioTracks = formats.filterKeys { it != 0 }.map { (id, fmt) ->
                    EngineTrack(id, "${fmt.language ?: "und"} · ${fmt.sampleMimeType} · ${fmt.channelCount.coerceAtLeast(1)} кан.")
                }
                tracks = audioTracks
                event(token) { listener.onTracksChanged() }
            }
            fun publish() {
                if (description != null) return
                val video = formats[0] ?: return
                val withAudio = expectedAudio ?: return
                if (withAudio && selected < 0) selected = if (externalAudio) 1 else
                    audioRequest.get().takeIf { it in formats && it != 0 } ?: formats.keys.firstOrNull { it != 0 } ?: return
                val audio = if (withAudio) formats[selected] ?: return else null
                currentAudioTrackId = if (externalAudio) control.audioId else if (audio != null) selected else -1
                description = Description(0, MediaFormatUtil.createMediaFormatFromFormat(video), if (audio == null) -1 else selected,
                    audio?.let { MediaFormatUtil.createMediaFormatFromFormat(it) })
                tracksChanged()
            }
            val output = object : HlsPacketOutput {
                override fun format(id: Int, type: Int, format: Format) {
                    synchronized(formats) {
                        val old = formats[id]
                        val changed = old != null && !HlsVideoTransition.sameFormat(old, format)
                        if (changed && description != null && (id == 0 || id == selected)) {
                            if (id == 0 && control.adaptive && old!!.sampleMimeType == format.sampleMimeType) transition.changed(format)
                            else { seekRequest.compareAndSet(null, positionMs); return }
                        }
                        formats[id] = format; publish()
                        if (id != 0) tracksChanged()
                    }
                }
                override fun packet(id: Int, type: Int, bytes: ByteArray, timeUs: Long, flags: Int) {
                    if (id == 0) {
                        val packet = transition.packet(bytes, flags) ?: return
                        enqueue(id, packet, timeUs, flags)
                    } else if (id == selected || description == null && (externalAudio && id == 1)) enqueue(id, bytes, timeUs, flags)
                }
                override fun endTracks(audioPresent: Boolean) { synchronized(formats) {
                    if (expectedAudio != null && expectedAudio != audioPresent && description != null) {
                        seekRequest.compareAndSet(null, positionMs); return
                    }
                    expectedAudio = audioPresent; publish()
                } }
            }
            val loader = LampCoreHlsLoader(http, playlist, context.cacheDir, startMs * 1000, output,
                { resolved, _ ->
                    durationOverrideUs = if (resolved.video.endList) resolved.video.durationUs else -1; mode = resolved.label
                    externalAudio = resolved.audio != null
                    if (externalAudio) {
                        control.audioId = resolved.selectedAudioId
                        currentAudioTrackId = resolved.selectedAudioId
                        audioTracks = resolved.audios.mapIndexed { i, track -> EngineTrack(10_000 + i, "${track.name} · ${track.language ?: "und"}") }
                        tracksChanged()
                    }
                    subtitles.hlsTracks(resolved)
                },
                { e -> if (running()) error = e }, ::running, control,
                { positionUs -> seekRequest.compareAndSet(null, positionUs / 1000) })
            hls = loader
            if (!running()) { loader.close(); return }
            loader.read()
            if (running()) {
                if (description == null) throw HlsException("В HLS не найдены поддерживаемые видео/аудиодорожки")
                ended = true
            }
        }

        private fun read() {
            val extractor = MediaExtractor()
            try {
                val uri = Uri.parse(media.url)
                if (uri.scheme == "http" || uri.scheme == "https") {
                    val hlsTransport = LampCoreHlsHttp(media.headers)
                    hlsHttp = hlsTransport
                    val playlist = try { hlsTransport.probe(media.url) } catch (e: Exception) {
                        if (uri.path.orEmpty().endsWith(".m3u8", true) || !running()) throw e
                        null // A progressive server may reject the non-range probe.
                    }
                    if (playlist != null) { readHls(hlsTransport, playlist); return }
                    hlsTransport.close(); hlsHttp = null
                    val http = LampCoreHttpSource(media.url, media.headers)
                    source = http
                    if (!running()) { http.close(); return }
                    extractor.setDataSource(http)
                } else extractor.setDataSource(context, uri, media.headers)
                val formats = (0 until extractor.trackCount).associateWith { extractor.getTrackFormat(it) }
                val video = formats.entries.firstOrNull { it.value.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: throw CoreException("Видеодорожка не найдена")
                val audios = formats.filterValues { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                audioTracks = audios.map { (id, fmt) -> EngineTrack(id, "${fmt.getString(MediaFormat.KEY_LANGUAGE) ?: "und"} · ${fmt.getString(MediaFormat.KEY_MIME)}") }
                val selected = audioRequest.get().takeIf { it in audios } ?: audios.keys.firstOrNull() ?: -1
                val audio = audios[selected]
                val desc = Description(video.key, video.value, selected, audio)
                extractor.selectTrack(video.key)
                if (audio != null) extractor.selectTrack(selected)
                if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                if (!running()) return
                currentAudioTrackId = selected
                description = desc
                val scratch = ByteBuffer.allocateDirect(4 * 1024 * 1024)
                while (running()) {
                    val id = extractor.sampleTrackIndex
                    if (id < 0) { ended = true; break }
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw CoreException("DRM не поддерживается в LampCore v1")
                    scratch.clear()
                    val size = extractor.readSampleData(scratch, 0)
                    if (size < 0) { ended = true; break }
                    if (size > scratch.capacity()) throw CoreException("Слишком большой видеокадр")
                    val data = ByteArray(size)
                    scratch.position(0); scratch.get(data)
                    enqueue(id, data, extractor.sampleTime, extractor.sampleFlags)
                    extractor.advance()
                }
            } catch (e: Exception) { if (running()) error = source?.lastError?.let { CoreException(it) } ?: e }
            finally { runCatching { extractor.release() }; source?.close(); hlsHttp?.close() }
        }
    }
}
