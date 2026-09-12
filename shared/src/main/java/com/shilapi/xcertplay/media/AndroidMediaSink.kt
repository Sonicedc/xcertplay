package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(surface: Surface? = null) : MediaSink {
    private val defaultSurface = surface
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
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

    override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        audioRenderer(type, format).start()
    }

    override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(type, format).submit(rtp)
    }

    fun close() {
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) { VideoDecoder(surfaces[type] ?: defaultSurface) }

    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer =
        audioRenderers.computeIfAbsent(type) { AudioRenderer(format) }
}

private sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray) : VideoJob
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(private val surface: Surface?) : Closeable {
    private val queue = LinkedBlockingQueue<VideoJob>()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        queue.offer(VideoJob.Frame(nalus))
    }

    override fun close() {
        running = false
        thread.interrupt()
        releaseDecoder()
    }

    private fun run() {
        try {
            while (running) {
                when (val job = queue.take()) {
                    is VideoJob.Config -> configureDecoder(job.codec, job.codecData)
                    is VideoJob.Frame -> feed(job.nalus)
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    private fun configureDecoder(codec: VideoCodec, codecData: ByteArray) {
        releaseDecoder()
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        if (codec == VideoCodec.H265) {
            if (codecData.isNotEmpty()) format.setByteBuffer("csd-0", ByteBuffer.wrap(codecData))
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            if (sps.isNotEmpty()) format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            if (pps.isNotEmpty()) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }
        val next = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(format, surface, null, 0)
                it.start()
            }
        } catch (_: Exception) {
            null
        }
        decoder = next
    }

    private fun feed(nalus: ByteArray) {
        val codec = decoder ?: return
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, System.nanoTime() / 1000, 0)
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
        }
        drainOutput(codec)
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    codec.releaseOutputBuffer(index, surface != null && info.size > 0)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
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
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
    }
}

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(private val format: AudioFormat) : Closeable {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray) {
        if (started) queue.offer(rtp)
    }

    override fun close() {
        running = false
        thread.interrupt()
        release()
    }

    private fun run() {
        try {
            if (format.codec == AudioCodecKind.AAC_LC) configureCodec()
            createTrack()
            while (running) handle(queue.take())
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            release()
        }
    }

    private fun configureCodec() {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            setInteger(MediaFormat.KEY_IS_ADTS, 1)
        }
        codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(mediaFormat, null, null, 0)
                it.start()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    private fun handle(rtp: ByteArray) {
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = MediaCodecSupport.aacAccessUnit(rtp.copyOfRange(12, rtp.size))
                if (accessUnit.isNotEmpty()) {
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                    )
                }
            }
            AudioCodecKind.OPUS -> Unit
        }
    }

    private fun feedCodec(payload: ByteArray) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, 0, 0)
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
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
        var written = 0
        while (written < length && running) {
            val count = track?.write(data, offset + written, length - written, AudioTrack.WRITE_BLOCKING)
                ?: -1
            if (count <= 0) break
            written += count
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
                track.stop()
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
        const val INPUT_TIMEOUT_US = 10_000L
    }
}
