package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

data class MediaPerformanceStats(
    val streamType: Int,
    val decoderName: String,
    val receivedFrames: Long,
    val renderedFrames: Long,
    val renderedFps: Double,
    val queueDepth: Int,
    val decoderWaits: Long,
    val queueLatencyMs: Double,
    val decoderLatencyMs: Double,
)

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val videoLowLatencyEnabled: Boolean = true,
    private val stableVideoTimestampsEnabled: Boolean = true,
    private val preserveVideoFramesEnabled: Boolean = true,
    private val audioLowLatencyEnabled: Boolean = true,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val onPerformanceStats: ((MediaPerformanceStats) -> Unit)? = null,
) : MediaSink {
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<Int, MicrophoneUplink>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        screenStreamActiveChanged = listener
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoDecoder(type).configure(codec, codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        screenStreamActiveChanged?.invoke(type, active)
    }

    override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        audioRenderer(type, format).start(firstSample)
    }

    override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(type, format).submit(rtp, sample)
    }

    override fun onAudioStopped(type: Int) {
        audioRenderers.remove(type)?.close()
    }

    override fun audioPlaybackSampleTime(type: Int): Long? =
        audioRenderers[type]?.playbackSampleTime()

    override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
        val uplink = microphoneUplinks.computeIfAbsent(type) { MicrophoneUplink(config) }
        if (!uplink.start()) microphoneUplinks.remove(type, uplink)
    }

    override fun onMicrophoneStopped(type: Int) {
        microphoneUplinks.remove(type)?.close()
    }

    fun close() {
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) {
            VideoDecoder(
                type,
                surfaces[type] ?: defaultSurface,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                videoLowLatencyEnabled,
                stableVideoTimestampsEnabled,
                preserveVideoFramesEnabled,
                onPerformanceStats,
            )
        }

    @Synchronized
    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[type]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioLowLatencyEnabled,
        ).also { audioRenderers[type] = it }
    }
}

private sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val enqueuedAtNs: Long) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    private val streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val lowLatencyEnabled: Boolean,
    private val stableTimestampsEnabled: Boolean,
    private val preserveFramesEnabled: Boolean,
    private val onPerformanceStats: ((MediaPerformanceStats) -> Unit)?,
) : Closeable {
    private val queue = LinkedBlockingQueue<VideoJob>()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var nextPresentationTimeUs = 0L
    @Volatile private var receivedFrames = 0L
    private var renderedFrames = 0L
    private var renderedFramesAtWindowStart = 0L
    private var decoderWaits = 0L
    private var statsWindowStartNs = System.nanoTime()
    private var decoderName = "not-configured"
    private var queueLatencyMs = 0.0
    private var decoderLatencyMs = 0.0
    private val decoderSubmissionTimes = ArrayDeque<Long>()
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        receivedFrames++
        queue.offer(VideoJob.Frame(nalus, System.nanoTime()))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        try {
            while (running) {
                val job = queue.take()
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> feed(job)
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                    }
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job.javaClass.simpleName}", error)
                    releaseDecoder()
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            if (lowLatencyEnabled) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_VIDEO_FRAME_RATE)
                // Android 10 does not expose MediaFormat.KEY_LOW_LATENCY, although Qualcomm's
                // decoder on this head unit accepts the underlying platform key.
                setInteger(LOW_LATENCY_KEY, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setFloat(MediaFormat.KEY_OPERATING_RATE, TARGET_VIDEO_FRAME_RATE.toFloat())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
        }
        if (codec == VideoCodec.H265) {
            val csd = MediaCodecSupport.hevcCodecSpecificData(codecData)
            if (csd.isNotEmpty()) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            if (sps.isNotEmpty()) format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
            if (pps.isNotEmpty()) format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
        }
        val next = try {
            createDecoder(mime).also {
                if (lowLatencyEnabled && it.name.contains("qcom", ignoreCase = true)) {
                    format.setInteger(QTI_LOW_LATENCY_KEY, 1)
                }
                it.configure(format, surface, null, 0)
                it.start()
            }
        } catch (error: Exception) {
            Log.e(TAG, "video decoder configure failed mime=$mime size=${width}x$height", error)
            null
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        nextPresentationTimeUs = 0L
        receivedFrames = 0L
        renderedFrames = 0L
        renderedFramesAtWindowStart = 0L
        decoderWaits = 0L
        queueLatencyMs = 0.0
        decoderLatencyMs = 0.0
        decoderSubmissionTimes.clear()
        statsWindowStartNs = System.nanoTime()
        if (next != null) {
            decoderName = next.name
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(frame: VideoJob.Frame) {
        val codec = decoder ?: return
        updateQueueLatency((System.nanoTime() - frame.enqueuedAtNs) / 1_000_000.0)
        val annexB = MediaCodecSupport.toAnnexB(frame.nalus)
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${frame.nalus.size} annexB=${annexB.size} " +
                    "head=${annexB.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
            )
        }
        if (annexB.isEmpty()) return

        // HEVC frames are predictive. Silently dropping a frame because the decoder was busy
        // corrupts every later frame that references it, producing stale macroblock mosaics until
        // the next IDR. Drain completed output and wait for backpressure instead; the screen stream
        // is TCP, so each complete encrypted frame has already arrived intact.
        var index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (preserveFramesEnabled) {
            while (running && index < 0) {
                decoderWaits++
                drainOutput(codec)
                index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            }
        } else if (index < 0) {
            decoderWaits++
            emitPerformanceStats()
            return
        }
        if (!running) return
        val input = codec.getInputBuffer(index)
            ?: throw IOException("Video decoder returned an input slot without a buffer")
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            val presentationTimeUs = if (stableTimestampsEnabled) {
                nextPresentationTimeUs.also { nextPresentationTimeUs += VIDEO_FRAME_DURATION_US }
            } else {
                System.nanoTime() / 1000
            }
            codec.queueInputBuffer(index, 0, annexB.size, presentationTimeUs, 0)
            decoderSubmissionTimes.addLast(System.nanoTime())
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            throw IOException(
                "Video access unit ${annexB.size} exceeds decoder input capacity ${input.capacity()}",
            )
        }
        drainOutput(codec)
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null
                    codec.releaseOutputBuffer(index, render)
                    if (render) {
                        decoderSubmissionTimes.pollFirst()?.let { submittedAt ->
                            updateDecoderLatency((System.nanoTime() - submittedAt) / 1_000_000.0)
                        }
                        renderedFrames++
                        emitPerformanceStats()
                        if (!renderedFrameLogged) {
                            renderedFrameLogged = true
                            Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun emitPerformanceStats() {
        val listener = onPerformanceStats ?: return
        val now = System.nanoTime()
        val elapsedNs = now - statsWindowStartNs
        if (elapsedNs < STATS_INTERVAL_NS) return
        val windowFrames = renderedFrames - renderedFramesAtWindowStart
        listener(
            MediaPerformanceStats(
                streamType = streamType,
                decoderName = decoderName,
                receivedFrames = receivedFrames,
                renderedFrames = renderedFrames,
                renderedFps = windowFrames * 1_000_000_000.0 / elapsedNs,
                queueDepth = queue.size,
                decoderWaits = decoderWaits,
                queueLatencyMs = queueLatencyMs,
                decoderLatencyMs = decoderLatencyMs,
            ),
        )
        renderedFramesAtWindowStart = renderedFrames
        statsWindowStartNs = now
    }

    private fun updateQueueLatency(sampleMs: Double) {
        queueLatencyMs = if (queueLatencyMs == 0.0) sampleMs else queueLatencyMs * 0.8 + sampleMs * 0.2
    }

    private fun updateDecoderLatency(sampleMs: Double) {
        decoderLatencyMs = if (decoderLatencyMs == 0.0) sampleMs else decoderLatencyMs * 0.8 + sampleMs * 0.2
    }

    private fun logOutputFormat(format: MediaFormat) {
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        nextPresentationTimeUs = 0L
        decoderSubmissionTimes.clear()
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val TARGET_VIDEO_FRAME_RATE = 60
        const val VIDEO_FRAME_DURATION_US = 1_000_000L / TARGET_VIDEO_FRAME_RATE
        const val STATS_INTERVAL_NS = 1_000_000_000L
        const val LOW_LATENCY_KEY = "low-latency"
        const val QTI_LOW_LATENCY_KEY = "vendor.qti-ext-dec-low-latency.enable"
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val lowLatencyEnabled: Boolean,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var firstSampleTime: Long? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start(firstSample: Int) {
        if (started) return
        firstSampleTime = firstSample.toLong() and 0xffff_ffffL
        started = true
        thread.start()
    }

    fun playbackSampleTime(): Long? {
        val first = firstSampleTime ?: return null
        val activeTrack = track ?: return null
        if (!playbackStarted) return first
        val playedFrames = activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
        return (first + playedFrames) and 0xffff_ffffL
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            while (running) handle(queue.take())
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) Log.e(TAG, "audio renderer worker failed", error)
        } finally {
            release()
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(mediaFormat, null, null, 0)
                it.start()
                Log.i(TAG, "audio decoder configured mime=$mime name=${it.name}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val bufferBytes = if (lowLatencyEnabled) {
            maxOf(minBuffer * 2, LOW_LATENCY_TRACK_BUFFER_BYTES)
        } else {
            maxOf(minBuffer * 4, MIN_TRACK_BUFFER_BYTES)
        }
        startThresholdBytes = if (lowLatencyEnabled) {
            maxOf(minBuffer / 2, LOW_LATENCY_START_BUFFER_BYTES)
        } else {
            maxOf(minBuffer, MIN_START_BUFFER_BYTES)
        }
        val builder = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes())
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (lowLatencyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        track = builder.build()
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels}",
        )
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun audioAttributes(): AudioAttributes {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        val usage = usageFor(selection.channel)
        val contentType = contentTypeFor(selection.contentType)
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
            .also {
                Log.i(
                    TAG,
                    "audio route type=${format.payloadType} audioType=${format.audioType} " +
                        "mode=$mode channel=${selection.channel} " +
                        "usage=$usage contentType=$contentType",
                )
            }
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) break
            written += count
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    track.play()
                    playbackStarted = true
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_QUEUED_PACKETS = 64
        const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
        const val MIN_START_BUFFER_BYTES = 4 * 1024
        const val LOW_LATENCY_TRACK_BUFFER_BYTES = 8 * 1024
        const val LOW_LATENCY_START_BUFFER_BYTES = 2 * 1024
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}
