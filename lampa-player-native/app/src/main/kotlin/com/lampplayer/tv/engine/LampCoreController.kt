package com.lampplayer.tv.engine

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Experimental progressive-file player. Own demux/decode loops, audio clock and scheduling;
 * no ExoPlayer/libVLC inside. Platform extractors/codecs determine format support.
 * HTTP reads happen on a separate, bounded demux thread, not the rendering thread.
 */
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

    fun setMedia(url: String, headers: Map<String, String>, startMs: Long) {
        if (released) return
        demux?.close()
        val token = generation.incrementAndGet()
        val next = Request(url, headers.toMap(), startMs.coerceAtLeast(0))
        request = next
        seekRequest.set(null); audioRequest.set(-1)
        positionMs = next.startMs; durationMs = -1; queuedUntilUs = next.startMs * 1000
        tracks = emptyList(); rendered = 0; dropped = 0; decoderName = "—"; underruns = 0
        requestedPlay = true; isPlaying = false
        acquireFocus()
        worker.execute { runMedia(next, token) }
    }

    fun retry() { request?.let { setMedia(it.url, it.headers, positionMs) } }
    fun stop() { generation.incrementAndGet(); demux?.close(); isPlaying = false }
    fun setVolume(percent: Int) { volume = percent.coerceIn(0, 100) / 100f }
    fun diagnostics(): String = "LampCore · $decoderName\n" +
        "Кадры $rendered · пропущено $dropped · сбои звука $underruns\n" +
        "Очередь %.1fс · HTTP %.1f МБ".format(
            ((bufferedMs - positionMs).coerceAtLeast(0)) / 1000.0,
            (demux?.source?.downloadedBytes ?: 0) / 1048576.0,
        )

    override fun play() { if (!released) { acquireFocus(); requestedPlay = true } }
    override fun pause() { pausedByFocus = false; requestedPlay = false }
    override fun seekTo(ms: Long) { seekRequest.set(ms.coerceAtLeast(0)) }
    override fun setRate(rate: Float) { this.rate = rate.coerceIn(0.5f, 2f) }
    override fun audioTracks(): List<EngineTrack> = tracks
    override fun subtitleTracks(): List<EngineTrack> = listOf(EngineTrack(-1, "Выкл · LampCore"))
    override fun selectAudio(id: Int) { if (tracks.any { it.id == id }) { audioRequest.set(id); seekRequest.set(positionMs) } }
    override fun selectSubtitle(id: Int) { /* v1 deliberately exposes only disabled subtitles */ }
    override fun setAspectRatio(ratio: String?) { /* Activity sizes SurfaceView */ }
    override fun setScale(scale: Float) { /* Activity sizes SurfaceView */ }
    override fun setSubtitleDelayMs(delayMs: Long) { /* subtitles not supported in v1 */ }

    override fun release() {
        released = true
        stop()
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
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            event(token) { listener.onError("LampCore v1: только прямые файлы; для HLS/DASH выберите ExoPlayer") }; return
        }
        var startMs = media.startMs
        try {
            while (alive(token)) {
                seekRequest.getAndSet(null)?.let { startMs = it }
                while (alive(token) && surface?.isValid != true) Thread.sleep(20)
                if (!alive(token)) break
                startMs = playSession(media, token, startMs, surface!!)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // Do not expose signed media URLs/tokens through platform exception text.
            event(token) {
                isPlaying = false
                val message = if (e is CoreException) e.message else "${e.javaClass.simpleName}: поток не поддержан или соединение прервано"
                listener.onError("LampCore: $message. Можно выбрать ExoPlayer/libVLC.")
            }
        }
    }

    private class CoreException(message: String) : IOException(message)

    /** Resources are owned and released exclusively on the rendering worker. */
    private fun playSession(media: Request, token: Int, startMs: Long, output: Surface): Long {
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
            while (alive(token) && input.description == null && input.error == null) Thread.sleep(5)
            input.error?.let { throw it }
            if (!alive(token)) return positionMs
            val desc = input.description ?: throw CoreException("Не удалось прочитать контейнер")
            val name = hardwareDecoder(desc.video) ?: throw CoreException("Нет аппаратного декодера для ${desc.video.getString(MediaFormat.KEY_MIME)}")
            video = MediaCodec.createByCodecName(name).also { it.configure(desc.video, output, null, 0); it.start() }
            decoderName = name
            audio = desc.audio?.let { fmt -> MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                .also { it.configure(fmt, null, null, 0); it.start() } }
            durationMs = formatDuration(desc.video) / 1000
            videoFps = runCatching { desc.video.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(0f)
            videoAspect = desc.video.getInteger(MediaFormat.KEY_WIDTH).toFloat() / desc.video.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(1)
            tracks = input.audioTracks
            event(token) { listener.onTracksChanged() }

            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            val head = AudioFrameCounter()
            var sampleRate = 0
            var frameBytes = 0
            var submittedFrames = 0L
            var audioBaseUs = Long.MIN_VALUE
            var wallBaseUs = startMs * 1000
            var wallBaseNs = System.nanoTime()
            var wallStarted = false
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
                        sink?.play(); sinkStarted = sink != null
                        wallBaseUs = positionMs * 1000; wallBaseNs = System.nanoTime()
                        event(token) { listener.onPlaying() }
                    } else { sink?.pause(); event(token) { listener.onPaused() } }
                    isPlaying = playing
                }
                if (!playing) { Thread.sleep(10); continue }
                if (sink != null && appliedRate != rate) {
                    sink.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
                    appliedRate = rate
                }
                sink?.setVolume(volume)

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
                                val fmt = audio.outputFormat
                                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                if (channels !in 1..2) throw CoreException("В v1 поддерживается только моно/стереозвук")
                                val encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                if (encoding != AudioFormat.ENCODING_PCM_16BIT) throw CoreException("В v1 нужен PCM 16 бит")
                                frameBytes = channels * 2
                                val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
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
                                pendingAudioBuffer = buffer
                                // Seeking lands on an earlier keyframe. Trim PCM to requested time.
                                if (sampleRate > 0 && audioInfo.presentationTimeUs < startMs * 1000) {
                                    val skipFrames = (startMs * 1000 - audioInfo.presentationTimeUs) * sampleRate / 1_000_000
                                    buffer.position(buffer.position() + minOf(buffer.remaining().toLong(), skipFrames * frameBytes).toInt())
                                }
                                if (audioBaseUs == Long.MIN_VALUE && buffer.hasRemaining()) {
                                    audioBaseUs = audioInfo.presentationTimeUs + (buffer.position() - audioInfo.offset) / frameBytes * 1_000_000L / sampleRate
                                }
                            }
                        }
                    }
                    if (pendingAudio >= 0) {
                        val buffer = pendingAudioBuffer ?: throw CoreException("Нет сохранённого аудиобуфера")
                        if (buffer.hasRemaining()) {
                            val target = sink ?: throw CoreException("Аудиодекодер не сообщил выходной формат")
                            if (!sinkStarted) { target.play(); sinkStarted = true }
                            val written = target.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                            if (written < 0) throw CoreException("Ошибка аудиовыхода ($written)")
                            submittedFrames += written / frameBytes
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
                val audioWaiting = audio != null && !audioEnd && (audioBaseUs == Long.MIN_VALUE || frames >= submittedFrames)
                if (audio != null && audioEnd && frames >= submittedFrames && !audioTailClock) {
                    wallBaseUs = if (audioBaseUs != Long.MIN_VALUE) audioBaseUs + frames * 1_000_000 / sampleRate.coerceAtLeast(1) else startMs * 1000
                    wallBaseNs = now; wallStarted = true; audioTailClock = true
                }
                val clockUs = if (audio != null && !audioTailClock && audioBaseUs != Long.MIN_VALUE)
                    audioBaseUs + frames * 1_000_000 / sampleRate.coerceAtLeast(1)
                else if ((audio == null || audioTailClock) && wallStarted) wallBaseUs + ((now - wallBaseNs) / 1000 * rate).toLong()
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
                        if (audio == null && !wallStarted) { wallBaseUs = pts; wallBaseNs = now; wallStarted = true }
                        val action = LampCoreTiming.frameAction(pts, if (audio == null && !wallStarted) pts else clockUs)
                        if (action <= 0) {
                            video.releaseOutputBuffer(pendingVideo, action == 0)
                            if (action < 0) dropped++ else { rendered++; videoLastUs = pts }
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
            runCatching { sink?.pause(); sink?.flush(); sink?.release() }
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
        @Volatile var description: Description? = null
        @Volatile var error: Exception? = null
        @Volatile var ended = false
        @Volatile var queuedUntilUs = startMs * 1000
        private var videoUntilUs = startMs * 1000
        private var audioUntilUs = startMs * 1000
        val thread = Thread({ read() }, "LampCore-demux")

        fun remove(queue: ArrayBlockingQueue<Sample>) { queue.poll()?.let { bytes.addAndGet(-it.bytes.size.toLong()) } }
        fun close() { closed = true; source?.close(); thread.interrupt() }
        private fun running() = !closed && alive(token)

        private fun read() {
            val extractor = MediaExtractor()
            try {
                val uri = Uri.parse(media.url)
                if (uri.scheme == "http" || uri.scheme == "https") {
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
                    val queue = if (id == desc.videoId) videoSamples else audioSamples
                    while (running() && (bytes.get() + size > 4 * 1024 * 1024 || queue.remainingCapacity() == 0)) Thread.sleep(5)
                    if (!running()) break
                    val data = ByteArray(size)
                    scratch.position(0); scratch.get(data)
                    val sample = Sample(id, data, extractor.sampleTime, extractor.sampleFlags)
                    bytes.addAndGet(size.toLong())
                    queue.put(sample)
                    if (id == desc.videoId) videoUntilUs = maxOf(videoUntilUs, sample.ptsUs)
                    else audioUntilUs = maxOf(audioUntilUs, sample.ptsUs)
                    queuedUntilUs = if (desc.audio == null) videoUntilUs else minOf(videoUntilUs, audioUntilUs)
                    extractor.advance()
                }
            } catch (e: Exception) { if (running()) error = source?.lastError?.let { CoreException(it) } ?: e }
            finally { runCatching { extractor.release() }; source?.close() }
        }
    }
}
