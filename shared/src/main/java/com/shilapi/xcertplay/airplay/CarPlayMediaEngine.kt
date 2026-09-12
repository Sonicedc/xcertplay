package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onIapMessage(bytes: ByteArray) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen and iAP2 DataStream ports, decrypts their
 * payloads, and hands decoded media to a [MediaSink].
 *
 * The LIVI audio receiver lives in a native component absent from the reference checkout, so the
 * audio wire format is intentionally not invented here; audio SETUP is left unhandled until a
 * grounded implementation exists.
 */
class CarPlayMediaEngine(private val sink: MediaSink) : AirPlayMediaHandler {
    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    )

    private val streams = ConcurrentHashMap<Int, Closeable>()
    private val audioMeta = ConcurrentHashMap<Int, AudioMeta>()

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        val key = outputKey(session, stream) ?: return null
        val screen = ScreenStream(key)
        val port = screen.listen(
            object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) = sink.onVideoCodec(type, codec)
                override fun onConfig(codecData: ByteArray) = sink.onVideoConfig(type, codecData)
                override fun onFrame(naluBytes: ByteArray) = sink.onVideoFrame(type, naluBytes)
            },
        )
        streams[type] = screen
        return port
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        val key = outputKey(session, stream) ?: return null
        val format = AudioStreamCodec.fromFormatBits(
            (stream["audioFormat"] as? Number)?.toLong() ?: 0L,
            type,
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)

        val audio = AudioStream(key)
        val (dataPort, controlPort) = audio.listen(
            object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    meta.firstSample = firstSample
                    meta.originNs = System.nanoTime()
                    sink.onAudioStarted(type, format, firstSample)
                }

                override fun onRtp(rtp: ByteArray, sample: Int) =
                    sink.onAudioRtp(type, format, rtp, sample)
            },
        )
        streams[type] = audio
        audioMeta[type] = meta
        return linkedMapOf(
            "type" to type,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "streamConnectionID" to (connectionId ?: 0L),
        )
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = stream["seed"] ?: return null
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(key)
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)
        })
        streams[STREAM_TYPE_DATA] = tunnel
        return linkedMapOf("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        val active = audioMeta.values.toList()
        if (active.isEmpty()) return null
        val streams = active.map { meta ->
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to meta.format.sampleRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * meta.format.sampleRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = meta.connectionId ?: 0L
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
            }
            entry
        }
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        streams.remove(type)?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = stream["streamConnectionID"] ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private companion object {
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
    }
}
