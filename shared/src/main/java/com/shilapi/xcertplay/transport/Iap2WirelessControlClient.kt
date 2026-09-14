package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * The wireless LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, the five update subscriptions, then Wi-Fi credentials and the
 * 0x4E0D/0x4E0E Wireless CarPlay transport notifications.
 *
 * The caller retains ownership of [channel]. While [run] is active it is the only receiver and
 * forwards each non-control CSM frame to [onIncoming].
 */
class Iap2WirelessControlClient(
    private val channel: Iap2CsmChannel,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        onReady: () -> Unit = {},
        onIncoming: (CsmFrame) -> Unit = {},
        onProgress: (String) -> Unit = {},
    ): Iap2WirelessControlResult {
        require(identification.wireless != null) {
            "Wireless control requires an Iap2IdentificationConfig with wireless transport"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        val deadlineNanos = if (timeoutMillis == NO_TIMEOUT_MILLIS) {
            Long.MAX_VALUE
        } else {
            deadlineAfter(timeoutMillis)
        }
        Iap2IdentificationClient(channel).identify(identification, requireRemaining(deadlineNanos))
        onProgress("iap2 identification accepted")
        var stage = Iap2WirelessControlStage.IDENTIFIED

        mfi.run(channel, requireRemaining(deadlineNanos), onProgress)
        stage = Iap2WirelessControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")

        for (subscription in Iap2WiredControlClient.subscriptions()) {
            send(subscription, deadlineNanos)
        }
        stage = Iap2WirelessControlStage.SUBSCRIBED
        onProgress("iap2 subscriptions sent")
        onReady()

        var forwardedFrames = 0
        var wifiConfigurationsSent = 0
        var carPlayStartSessionsSent = 0
        var preTransportWiFiConfigurationsSent = 0
        var postTransportWiFiConfigurationsSent = 0
        var transportNotificationSeen = false
        var wirelessCarPlayConnectingSeen = false
        var locationActive = false
        var locationSentLogged = false
        while (true) {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining == 0L) {
                    return Iap2WirelessControlResult(
                        Iap2WirelessControlTerminal.TIMED_OUT,
                        stage,
                        forwardedFrames,
                        wifiConfigurationsSent,
                        carPlayStartSessionsSent,
                        transportNotificationSeen,
                        postTransportWiFiConfigurationsSent,
                        wirelessCarPlayConnectingSeen,
                    )
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
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayConnectingSeen,
                        )
                    }
                    if (remainingMillis(deadlineNanos) == 0L) {
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.TIMED_OUT,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayConnectingSeen,
                        )
                    }
                    continue
                }

                when (incoming.messageId) {
                    REQUEST_ACCESSORY_WIFI_CONFIGURATION -> {
                        onProgress("iap2 rx=0x5702 request-wifi-configuration")
                        val postTransport = transportNotificationSeen
                        val sentCount = if (postTransport) {
                            postTransportWiFiConfigurationsSent
                        } else {
                            preTransportWiFiConfigurationsSent
                        }
                        val limit = if (postTransport) {
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        } else {
                            MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        }
                        if (sentCount >= limit) {
                            onProgress(
                                "iap2 0x5703 ignored: maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                            stage = later(
                                stage,
                                if (postTransport) {
                                    Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT
                                } else {
                                    Iap2WirelessControlStage.WIFI_CONFIG_SENT
                                },
                            )
                            wifiConfigurationsSent++
                            if (postTransport) {
                                postTransportWiFiConfigurationsSent++
                            } else {
                                preTransportWiFiConfigurationsSent++
                            }
                            onProgress("iap2 tx=0x5703 accessory-wifi-configuration")
                        }
                    }

                    CARPLAY_AVAILABILITY -> {
                        onProgress("iap2 rx=0x4300 carplay-availability")
                        send(carPlayStartSession(endpoint), deadlineNanos)
                        stage = later(stage, Iap2WirelessControlStage.CARPLAY_START_SENT)
                        carPlayStartSessionsSent++
                        onProgress("iap2 tx=0x4301 carplay-start-session")
                    }

                    WIRELESS_CARPLAY_UPDATE -> {
                        val status = wirelessCarPlayUpdateStatus(incoming)
                        if (status == 1) wirelessCarPlayConnectingSeen = true
                        onProgress(
                            "iap2 rx=0x4e0d wireless-carplay-update " +
                                "status=${wirelessCarPlayStatusName(status)}",
                        )
                    }

                    DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION -> {
                        transportNotificationSeen = true
                        stage = later(stage, Iap2WirelessControlStage.TRANSPORT_NOTIFIED)
                        onProgress(
                            "iap2 rx=0x4e0e device-transport-identifier; " +
                                "resending 0x5703",
                        )
                        if (
                            postTransportWiFiConfigurationsSent >=
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        ) {
                            onProgress(
                                "iap2 post-transport 0x5703 ignored: " +
                                    "maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                            stage = later(
                                stage,
                                Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT,
                            )
                            wifiConfigurationsSent++
                            postTransportWiFiConfigurationsSent++
                            onProgress(
                                "iap2 tx=0x5703 post-transport accessory-wifi-configuration",
                            )
                        }
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
        private const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702
        private const val ACCESSORY_WIFI_CONFIGURATION = 0x5703
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val WIRELESS_CARPLAY_UPDATE = 0x4e0d
        private const val DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION = 0x4e0e
        private const val LOCATION_POLL_INTERVAL_MILLIS = 1_000L
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_RECV_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS = 5
        private const val MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS = 2
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Exact LIVI 0x5703 flat Wi-Fi credential payload. */
        fun accessoryWiFiConfiguration(endpoint: Iap2WirelessCarPlayEndpoint): CsmFrame =
            frame(
                ACCESSORY_WIFI_CONFIGURATION,
                Iap2CsmParameter(1, nulTerminated(endpoint.ssid)),
                Iap2CsmParameter(2, nulTerminated(endpoint.passphrase)),
                Iap2CsmParameter(3, byteArrayOf(endpoint.security.wireValue.toByte())),
                Iap2CsmParameter(4, byteArrayOf(endpoint.channel.toByte())),
            )

        /** Wireless 0x4301 reply carrying the receiver address, port and pairing identity. */
        fun carPlayStartSession(endpoint: Iap2WirelessCarPlayEndpoint): CsmFrame {
            val wireless = Iap2CsmParameters.encode(
                listOf(
                    Iap2CsmParameter(0, nulTerminated(endpoint.ssid)),
                    Iap2CsmParameter(1, nulTerminated(endpoint.passphrase)),
                    Iap2CsmParameter(2, byteArrayOf(endpoint.channel.toByte())),
                    Iap2CsmParameter(3, nulTerminatedList(endpoint.ipAddresses)),
                    Iap2CsmParameter(4, byteArrayOf(endpoint.security.wireValue.toByte())),
                ),
            )
            return frame(
                CARPLAY_START_SESSION,
                Iap2CsmParameter(1, wireless),
                Iap2CsmParameter(2, u32(endpoint.airPlayPort)),
                Iap2CsmParameter(3, nulTerminated(endpoint.deviceIdentifier)),
                Iap2CsmParameter(4, nulTerminated(endpoint.publicKey)),
                Iap2CsmParameter(5, nulTerminated(endpoint.sourceVersion)),
            )
        }

        internal fun wirelessCarPlayUpdateStatus(frame: CsmFrame): Int? {
            if (frame.messageId != WIRELESS_CARPLAY_UPDATE) return null
            return try {
                Iap2CsmParameters.parse(frame.payload)
                    .firstOrNull { it.id == 0 }
                    ?.payload
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xff)
            } catch (_: IphoneUsbException.Protocol) {
                null
            }
        }

        internal fun wirelessCarPlayStatusName(status: Int?): String = when (status) {
            0 -> "idle"
            1 -> "connecting"
            2 -> "connected"
            3 -> "error"
            4 -> "disconnecting"
            5 -> "disconnected"
            null -> "unavailable"
            else -> "unknown($status)"
        }

        private fun frame(messageId: Int, vararg parameters: Iap2CsmParameter): CsmFrame =
            CsmFrame(messageId, Iap2CsmParameters.encode(parameters.asList()))

        private fun nulTerminated(value: String): ByteArray = value.encodeToByteArray() + byteArrayOf(0)

        private fun nulTerminatedList(values: List<String>): ByteArray {
            val output = ByteArrayOutputStream()
            for (value in values) output.write(nulTerminated(value))
            return output.toByteArray()
        }

        private fun u32(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

        private fun later(
            current: Iap2WirelessControlStage,
            next: Iap2WirelessControlStage,
        ): Iap2WirelessControlStage = if (current.ordinal >= next.ordinal) current else next

        private fun deadlineAfter(timeoutMillis: Long): Long {
            val now = System.nanoTime()
            val delta = timeoutMillis * NANOS_PER_MILLISECOND
            return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
        }

        private fun requireRemaining(deadlineNanos: Long): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wireless iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return 0L
            return min(
                MAX_RECV_TIMEOUT_MILLIS,
                (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            )
        }
    }
}

/** Wireless 0x5703 security values. */
enum class Iap2WirelessSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}

/** Wireless hotspot and AirPlay endpoint sent in 0x5703 and 0x4301. */
class Iap2WirelessCarPlayEndpoint(
    val ssid: String,
    val passphrase: String,
    val channel: Int,
    val security: Iap2WirelessSecurity,
    ipAddresses: List<String>,
    val airPlayPort: Int,
    val deviceIdentifier: String,
    val publicKey: String,
    val sourceVersion: String,
) {
    val ipAddresses: List<String> = ipAddresses.toList()

    init {
        require(ssid.isNotBlank()) { "ssid is required and must not be blank" }
        require('\u0000' !in ssid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        if (security != Iap2WirelessSecurity.NONE) {
            require(passphrase.isNotEmpty()) { "passphrase is required for secured Wi-Fi" }
        }
        require(channel in 0..0xff) { "channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "At least one wireless IP address is required" }
        require(ipAddresses.all { it.isNotBlank() && '\u0000' !in it }) {
            "Every wireless IP address must be non-blank and must not contain U+0000"
        }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(deviceIdentifier.isNotBlank()) { "deviceIdentifier is required and must not be blank" }
        require('\u0000' !in deviceIdentifier) { "deviceIdentifier must not contain U+0000" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
    }
}

enum class Iap2WirelessControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    WIFI_CONFIG_SENT,
    CARPLAY_START_SENT,
    TRANSPORT_NOTIFIED,
    POST_TRANSPORT_WIFI_CONFIG_SENT,
}

enum class Iap2WirelessControlTerminal { CHANNEL_CLOSED, TIMED_OUT }

/** End state of the control loop only; it is not evidence of a live Wi-Fi or AirPlay session. */
data class Iap2WirelessControlResult(
    val terminal: Iap2WirelessControlTerminal,
    val stage: Iap2WirelessControlStage,
    val forwardedFrames: Int,
    val wifiConfigurationsSent: Int,
    val carPlayStartSessionsSent: Int,
    val transportNotificationSeen: Boolean,
    val postTransportWiFiConfigurationsSent: Int,
    val wirelessCarPlayConnectingSeen: Boolean,
)
