package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.math.min

/**
 * The wired LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, power announcement, five update subscriptions, then CarPlay availability.
 *
 * The caller retains ownership of [channel].  While [run] is active it is the only receiver and
 * forwards each non-availability CSM frame to [onIncoming]; it neither opens NCM nor implements
 * an AirPlay receiver.
 */
class Iap2WiredControlClient(
    private val channel: Iap2CsmChannel,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WiredCarPlayEndpoint,
        availableCurrentMilliAmps: Int,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        onIncoming: (CsmFrame) -> Unit = {},
        onProgress: (String) -> Unit = {},
    ): Iap2WiredControlResult {
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS"
        }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        Iap2IdentificationClient(channel).identify(identification, requireRemaining(deadlineNanos))
        onProgress("iap2 identification accepted")
        var stage = Iap2WiredControlStage.IDENTIFIED
        mfi.run(channel, requireRemaining(deadlineNanos), onProgress)
        stage = Iap2WiredControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")

        send(powerSourceUpdate(availableCurrentMilliAmps), deadlineNanos)
        for (subscription in subscriptions()) send(subscription, deadlineNanos)
        stage = Iap2WiredControlStage.SUBSCRIBED
        onProgress("iap2 power/subscriptions sent")

        var forwardedFrames = 0
        var carPlayStartSessions = 0
        var locationActive = false
        var locationSentLogged = false
        try {
            while (true) {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining == 0L) {
                    return Iap2WiredControlResult(Iap2WiredControlTerminal.TIMED_OUT, stage, forwardedFrames, carPlayStartSessions)
                }
                if (locationActive && sendLatestLocation(locationProvider, deadlineNanos) && !locationSentLogged) {
                    locationSentLogged = true
                    onProgress("iap2 tx=0xfffb location-information")
                }
                val pollTimeout = if (locationActive) {
                    min(remaining, LOCATION_POLL_INTERVAL_MILLIS)
                } else {
                    remaining
                }
                val incoming = channel.recv(pollTimeout)
                if (incoming == null) {
                    if (channel.isClosed) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    if (remainingMillis(deadlineNanos) == 0L) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.TIMED_OUT,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    continue
                }

                when (incoming.messageId) {
                    CARPLAY_AVAILABILITY -> {
                        onProgress("iap2 rx=0x4300 carplay-availability")
                        onProgress(carPlayAvailabilitySummary(incoming.payload))
                        // LIVI sends its wired answer on every availability notification; do not gate it on
                        // the phone's advertised availability boolean.
                        send(carPlayStartSession(endpoint), deadlineNanos)
                        stage = Iap2WiredControlStage.CARPLAY_START_SENT
                        carPlayStartSessions++
                        onProgress("iap2 tx=0x4301 carplay-start-session")
                    }

                    Iap2LocationMessages.START_LOCATION_INFORMATION -> {
                        onProgress("iap2 rx=0xfffa start-location-information")
                        locationActive = startLocationUpdates(locationProvider, onProgress)
                        locationSentLogged = false
                        if (locationActive && sendLatestLocation(locationProvider, deadlineNanos)) {
                            locationSentLogged = true
                            onProgress("iap2 tx=0xfffb location-information")
                        }
                    }

                    Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
                        onProgress("iap2 rx=0xfffc stop-location-information")
                        locationActive = false
                        locationSentLogged = false
                        locationProvider?.stop()
                    }

                    else -> {
                        onProgress("iap2 rx=0x${incoming.messageId.toString(16).padStart(4, '0')}")
                        onIncoming(incoming)
                        forwardedFrames++
                    }
                }
            }
        } finally {
            locationProvider?.stop()
        }
    }

    private fun send(frame: CsmFrame, deadlineNanos: Long) {
        channel.send(frame, requireRemaining(deadlineNanos))
    }

    private fun sendLatestLocation(
        provider: Iap2LocationProvider?,
        deadlineNanos: Long,
    ): Boolean {
        val sentence = provider?.latestNmea() ?: return false
        channel.send(
            Iap2LocationMessages.locationInformation(sentence),
            requireRemaining(deadlineNanos),
        )
        return true
    }

    private fun startLocationUpdates(
        provider: Iap2LocationProvider?,
        onProgress: (String) -> Unit,
    ): Boolean {
        if (provider == null) return false
        return try {
            provider.start().also { started ->
                if (!started) onProgress("iap2 location provider did not start")
            }
        } catch (error: Exception) {
            onProgress("iap2 location provider start failed: ${error.message}")
            false
        }
    }

    companion object {
        private const val POWER_SOURCE_UPDATE = 0xae03
        private const val START_NOW_PLAYING_UPDATES = 0x5000
        private const val START_ROUTE_GUIDANCE_UPDATES = 0x5200
        private const val START_POWER_UPDATES = 0xae00
        private const val START_COMMUNICATIONS_UPDATES = 0x4157
        private const val START_CALL_STATE_UPDATES = 0x4154
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val LOCATION_POLL_INTERVAL_MILLIS = 1_000L
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_RECV_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Exact LIVI wired PowerSourceUpdate encoding: current and the charge-if-powered flag. */
        fun powerSourceUpdate(availableCurrentMilliAmps: Int): CsmFrame {
            require(availableCurrentMilliAmps in 0..0xffff) {
                "availableCurrentMilliAmps must be in 0..65535"
            }
            return frame(
                POWER_SOURCE_UPDATE,
                Iap2CsmParameter(0, u16(availableCurrentMilliAmps)),
                Iap2CsmParameter(1, byteArrayOf(1)),
            )
        }

        /** Exact five subscription requests emitted by LIVI's wired bring-up. */
        fun subscriptions(): List<CsmFrame> = listOf(
            frame(
                START_NOW_PLAYING_UPDATES,
                Iap2CsmParameter(0, flags(1, 4, 6, 12, 26)),
                Iap2CsmParameter(1, flags(0, 1, 7)),
            ),
            frame(START_ROUTE_GUIDANCE_UPDATES),
            frame(START_POWER_UPDATES, Iap2CsmParameter(4, EMPTY), Iap2CsmParameter(5, EMPTY), Iap2CsmParameter(6, EMPTY)),
            frame(START_COMMUNICATIONS_UPDATES, Iap2CsmParameter(0, EMPTY), Iap2CsmParameter(4, EMPTY), Iap2CsmParameter(5, EMPTY)),
            frame(START_CALL_STATE_UPDATES, Iap2CsmParameter(0, EMPTY), Iap2CsmParameter(1, EMPTY), Iap2CsmParameter(2, EMPTY), Iap2CsmParameter(3, EMPTY), Iap2CsmParameter(4, EMPTY), Iap2CsmParameter(11, EMPTY)),
        )

        /** Builds the wired-only CarPlayStartSession message; no NCM or AirPlay socket is opened. */
        fun carPlayStartSession(endpoint: Iap2WiredCarPlayEndpoint): CsmFrame {
            // Parameter 0 is a wired-attributes group. Each address is itself a nested parameter
            // 0 inside that group; placing the raw string directly in the outer parameter makes
            // an otherwise valid 0x4301 undecodable by the phone.
            val wired = Iap2CsmParameters.encode(
                endpoint.ipv6Addresses.map { address ->
                    Iap2CsmParameter(0, nulTerminated(address))
                },
            )
            val parameters = ArrayList<Iap2CsmParameter>(5)
            parameters += Iap2CsmParameter(0, wired)
            parameters += Iap2CsmParameter(2, u32(endpoint.airPlayPort))
            endpoint.deviceIdentifier?.let { parameters += Iap2CsmParameter(3, nulTerminated(it)) }
            parameters += Iap2CsmParameter(4, nulTerminated(endpoint.publicKey))
            parameters += Iap2CsmParameter(5, nulTerminated(endpoint.sourceVersion))
            return CsmFrame(CARPLAY_START_SESSION, Iap2CsmParameters.encode(parameters))
        }

        fun carPlayAvailabilitySummary(payload: ByteArray): String {
            return try {
                val outer = Iap2CsmParameters.parse(payload)
                val wired = outer.firstOrNull { it.id == 0 }?.payload
                    ?.let(Iap2CsmParameters::parse)
                    .orEmpty()
                val available = wired.firstOrNull { it.id == 0 }?.payload?.firstOrNull()
                    ?.let { it.toInt() and 0xff }
                val transport = wired.firstOrNull { it.id == 1 }?.payload
                    ?.decodeToString()
                    ?.trimEnd('\u0000')
                "iap2 4300 wiredAvailable=$available usbTransport=${transport ?: "none"}"
            } catch (error: RuntimeException) {
                "iap2 4300 decode failed: ${error.message}"
            }
        }

        private fun frame(messageId: Int, vararg parameters: Iap2CsmParameter): CsmFrame =
            CsmFrame(messageId, Iap2CsmParameters.encode(parameters.asList()))

        private fun flags(vararg ids: Int): ByteArray =
            Iap2CsmParameters.encode(ids.map { Iap2CsmParameter(it, EMPTY) })

        private fun u16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

        private fun u32(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
        )

        private fun nulTerminated(value: String): ByteArray = value.encodeToByteArray() + byteArrayOf(0)

        private fun deadlineAfter(timeoutMillis: Long): Long {
            val now = System.nanoTime()
            val delta = timeoutMillis * NANOS_PER_MILLISECOND
            return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
        }

        private fun requireRemaining(deadlineNanos: Long): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wired iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return 0L
            return ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                .coerceAtMost(MAX_RECV_TIMEOUT_MILLIS)
        }

        private val EMPTY = ByteArray(0)
    }
}

/** A configured wired AirPlay endpoint; configuration does not claim that either service is live. */
class Iap2WiredCarPlayEndpoint(
    ipv6Addresses: List<String>,
    val airPlayPort: Int,
    val publicKey: String,
    val sourceVersion: String,
    val deviceIdentifier: String? = null,
) {
    /** A stable copy so a caller cannot mutate a validated endpoint before it is encoded. */
    val ipv6Addresses: List<String> = ipv6Addresses.toList()

    init {
        require(ipv6Addresses.isNotEmpty()) { "At least one wired IPv6 address is required" }
        require(ipv6Addresses.all(::isIpv6Literal)) { "Every wired address must be an IPv6 text literal" }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
        deviceIdentifier?.let {
            require(it.isNotEmpty()) { "deviceIdentifier is optional, but must not be empty when provided" }
            require('\u0000' !in it) { "deviceIdentifier must not contain U+0000" }
        }
    }

    private companion object {
        fun isIpv6Literal(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                InetAddress.getByName(value) is Inet6Address
            } catch (_: Exception) {
                false
            }
        }
    }
}

enum class Iap2WiredControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    CARPLAY_START_SENT,
}

enum class Iap2WiredControlTerminal { CHANNEL_CLOSED, TIMED_OUT }

/** End state of the control loop only; it is not evidence of NCM or AirPlay availability. */
data class Iap2WiredControlResult(
    val terminal: Iap2WiredControlTerminal,
    val stage: Iap2WiredControlStage,
    val forwardedFrames: Int,
    val carPlayStartSessionsSent: Int,
)
