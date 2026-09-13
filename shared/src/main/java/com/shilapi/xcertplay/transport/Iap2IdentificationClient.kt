package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.min

/** A single length-prefixed CSM parameter, without message-specific interpretation. */
class Iap2CsmParameter(id: Int, payload: ByteArray) {
    val id: Int
    private val bytes: ByteArray

    init {
        require(id in 0..0xffff) { "CSM parameter id must fit in u16" }
        require(payload.size <= Iap2CsmFramer.MAX_PARAM_BYTES - 4) {
            "CSM parameter payload exceeds ${Iap2CsmFramer.MAX_PARAM_BYTES - 4} bytes"
        }
        this.id = id
        bytes = payload.copyOf()
    }

    val payload: ByteArray get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Iap2CsmParameter && id == other.id && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * id + bytes.contentHashCode()
}

/** Strict, reusable CSM `u16 length | u16 id | payload` encoding and parsing. */
object Iap2CsmParameters {
    fun encode(parameters: Iterable<Iap2CsmParameter>): ByteArray {
        val encoded = ByteArrayOutputStream()
        for (parameter in parameters) {
            val next = Iap2CsmFramer.encodeParam(parameter.id, parameter.payload)
            require(next.size <= MAX_CSM_PAYLOAD_BYTES - encoded.size()) {
                "Encoded CSM parameters exceed the $MAX_CSM_PAYLOAD_BYTES-byte message payload limit"
            }
            encoded.write(next)
        }
        return encoded.toByteArray()
    }

    /** Rejects truncated headers, invalid lengths, and trailing bytes instead of silently dropping them. */
    fun parse(encoded: ByteArray): List<Iap2CsmParameter> {
        val parameters = ArrayList<Iap2CsmParameter>()
        var offset = 0
        while (offset < encoded.size) {
            if (encoded.size - offset < HEADER_BYTES) {
                throw IphoneUsbException.Protocol("Truncated CSM parameter header")
            }
            val length = u16(encoded, offset)
            if (length < HEADER_BYTES || length > encoded.size - offset) {
                throw IphoneUsbException.Protocol("Invalid CSM parameter length $length")
            }
            parameters += Iap2CsmParameter(
                id = u16(encoded, offset + 2),
                payload = encoded.copyOfRange(offset + HEADER_BYTES, offset + length),
            )
            offset += length
        }
        return parameters
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private const val HEADER_BYTES = 4
    private const val MAX_CSM_PAYLOAD_BYTES = Iap2CsmFramer.MAX_FRAME_BYTES - Iap2CsmFramer.HEADER_BYTES
}

/** The wireless transport identity advertised on the Bluetooth identification session. */
class Iap2WirelessIdentification(
    val bluetoothMac: String,
    val ssid: String,
) {
    private val macBytes: ByteArray

    init {
        require(MAC_ADDRESS.matches(bluetoothMac)) {
            "bluetoothMac must be six colon-separated hexadecimal bytes"
        }
        require(ssid.isNotBlank()) { "Wireless SSID must not be blank" }
        require('\u0000' !in ssid) { "Wireless SSID must not contain U+0000" }
        macBytes = ByteArray(6) { index ->
            bluetoothMac.substring(index * 3, index * 3 + 2).toInt(16).toByte()
        }
    }

    internal fun bluetoothMacBytes(): ByteArray = macBytes.copyOf()

    private companion object {
        private val MAC_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

/** Required identity for the minimal wired or wireless iAP2 identification exchange. */
data class Iap2IdentificationConfig(
    val name: String,
    val modelIdentifier: String,
    val manufacturer: String,
    val serialNumber: String,
    val firmwareVersion: String,
    val hardwareVersion: String,
    /** The iPhone USB interface used by CarPlay, supplied explicitly by the deployment. */
    val carPlayUsbInterfaceNumber: Int,
    val language: String = "en",
    /** This app's own EA protocol identifier; it deliberately does not claim a CarPlay EA flag. */
    val externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    /** Non-null selects the wireless Bluetooth and WirelessCarPlay transport components. */
    val wireless: Iap2WirelessIdentification? = null,
) {
    constructor(
        name: String,
        modelIdentifier: String,
        manufacturer: String,
        serialNumber: String,
        firmwareVersion: String,
        hardwareVersion: String,
        wireless: Iap2WirelessIdentification,
        language: String = "en",
        externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    ) : this(
        name = name,
        modelIdentifier = modelIdentifier,
        manufacturer = manufacturer,
        serialNumber = serialNumber,
        firmwareVersion = firmwareVersion,
        hardwareVersion = hardwareVersion,
        carPlayUsbInterfaceNumber = 0,
        language = language,
        externalAccessoryProtocol = externalAccessoryProtocol,
        wireless = wireless,
    )

    init {
        listOf(name, modelIdentifier, manufacturer, serialNumber, firmwareVersion, hardwareVersion).forEach {
            require(it.isNotBlank()) { "Identification identity strings must not be blank" }
        }
        require(language.isNotBlank()) { "Identification language must not be blank" }
        require(externalAccessoryProtocol.isNotBlank()) { "External accessory protocol must not be blank" }
        listOf(
            name,
            modelIdentifier,
            manufacturer,
            serialNumber,
            firmwareVersion,
            hardwareVersion,
            language,
            externalAccessoryProtocol,
        ).forEach {
            require('\u0000' !in it) { "NUL-terminated identification strings must not contain U+0000" }
        }
        require(carPlayUsbInterfaceNumber in 0..0xff) {
            "carPlayUsbInterfaceNumber must be in 0..255"
        }
    }
}

/** Identification failures distinguished from the underlying iAP2 transport failure. */
sealed class Iap2IdentificationException(message: String) : IOException(message) {
    class Rejected(parameterIds: Set<Int>) : Iap2IdentificationException(
        "iAP2 identification rejected parameters ${parameterIds.sorted().joinToString(prefix = "[", postfix = "]") { "0x${it.toString(16).padStart(4, '0')}" }}; " +
            "this minimal identification profile has no optional components to remove",
    ) {
        val parameterIds: Set<Int> = parameterIds.toSet()
    }

    class UnexpectedMessage(messageId: Int) : Iap2IdentificationException(
        "Unexpected iAP2 identification message 0x${messageId.toString(16).padStart(4, '0')}",
    )
}

/**
 * Synchronous accessory-side wired or wireless identification over an already owned CSM channel.
 *
 * This is intentionally only identification: it neither invokes MFi nor itself starts any
 * CarPlay, subscription, power, media, or UI service.
 */
class Iap2IdentificationClient(private val channel: Iap2CsmChannel) {
    /** Waits for link negotiation, then completes the 1D00/1D01/1D02 exchange. */
    @Throws(IphoneUsbException::class, Iap2IdentificationException::class)
    fun identify(config: Iap2IdentificationConfig, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAXIMUM_TIMEOUT_MILLIS"
        }
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        if (!channel.awaitReady(remainingMillis(deadlineNanos))) {
            throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 control session readiness")
        }

        while (true) {
            val frame = channel.recv(remainingMillis(deadlineNanos))
                ?: throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 identification")
            when (frame.messageId) {
                START_IDENTIFICATION -> channel.send(identificationInformation(config), remainingMillis(deadlineNanos))
                IDENTIFICATION_ACCEPTED -> return
                IDENTIFICATION_REJECTED -> {
                    val rejected = Iap2CsmParameters.parse(frame.payload).mapTo(LinkedHashSet()) { it.id }
                    throw Iap2IdentificationException.Rejected(rejected)
                }
                else -> throw Iap2IdentificationException.UnexpectedMessage(frame.messageId)
            }
        }
    }

    companion object {
        const val START_IDENTIFICATION = 0x1d00
        const val IDENTIFICATION_INFORMATION = 0x1d01
        const val IDENTIFICATION_ACCEPTED = 0x1d02
        const val IDENTIFICATION_REJECTED = 0x1d03

        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Builds the smallest honest LIVI-compatible wired or wireless IdentificationInformation. */
        fun identificationInformation(config: Iap2IdentificationConfig): CsmFrame {
            val externalAccessoryProtocol = Iap2CsmParameters.encode(
                listOf(
                    Iap2CsmParameter(0, byteArrayOf(1)),
                    Iap2CsmParameter(1, nulTerminated(config.externalAccessoryProtocol)),
                    Iap2CsmParameter(2, byteArrayOf(0)),
                ),
            )
            val wireless = config.wireless
            val usbHostTransport = Iap2CsmParameters.encode(
                listOf(
                    Iap2CsmParameter(0, u16(0)),
                    Iap2CsmParameter(1, nulTerminated("USBHostTransport")),
                    Iap2CsmParameter(2, EMPTY),
                    Iap2CsmParameter(3, byteArrayOf(config.carPlayUsbInterfaceNumber.toByte())),
                    Iap2CsmParameter(4, EMPTY),
                ),
            )
            val parameters = mutableListOf(
                Iap2CsmParameter(0, nulTerminated(config.name)),
                Iap2CsmParameter(1, nulTerminated(config.modelIdentifier)),
                Iap2CsmParameter(2, nulTerminated(config.manufacturer)),
                Iap2CsmParameter(3, nulTerminated(config.serialNumber)),
                Iap2CsmParameter(4, nulTerminated(config.firmwareVersion)),
                Iap2CsmParameter(5, nulTerminated(config.hardwareVersion)),
                Iap2CsmParameter(
                    6,
                    u16List(if (wireless == null) MESSAGES_SENT_BY_ACCESSORY else MESSAGES_SENT_BY_WIRELESS_ACCESSORY),
                ),
                Iap2CsmParameter(
                    7,
                    u16List(if (wireless == null) MESSAGES_RECEIVED_FROM_PHONE else MESSAGES_RECEIVED_FROM_WIRELESS_PHONE),
                ),
                Iap2CsmParameter(8, byteArrayOf(if (wireless == null) 2 else 0)),
                Iap2CsmParameter(9, u16(20)),
                Iap2CsmParameter(10, externalAccessoryProtocol),
                Iap2CsmParameter(12, nulTerminated(config.language)),
                Iap2CsmParameter(13, nulTerminated(config.language)),
            )
            if (wireless == null) {
                parameters += Iap2CsmParameter(16, usbHostTransport)
            } else {
                parameters += Iap2CsmParameter(17, bluetoothTransport(wireless))
                parameters += Iap2CsmParameter(24, wirelessCarPlayTransport(wireless))
            }
            val payload = Iap2CsmParameters.encode(parameters)
            check(payload.size + Iap2CsmFramer.HEADER_BYTES <= Iap2CsmFramer.MAX_FRAME_BYTES) {
                "IdentificationInformation exceeds the complete CSM frame limit"
            }
            return CsmFrame(IDENTIFICATION_INFORMATION, payload)
        }

        private fun bluetoothTransport(identity: Iap2WirelessIdentification): ByteArray =
            Iap2CsmParameters.encode(
                listOf(
                    Iap2CsmParameter(0, u16(0)),
                    Iap2CsmParameter(1, nulTerminated("blue")),
                    Iap2CsmParameter(2, EMPTY),
                    Iap2CsmParameter(3, identity.bluetoothMacBytes()),
                    Iap2CsmParameter(4, nulTerminated("blue")),
                    Iap2CsmParameter(5, EMPTY),
                ),
            )

        private fun wirelessCarPlayTransport(identity: Iap2WirelessIdentification): ByteArray =
            Iap2CsmParameters.encode(
                listOf(
                    Iap2CsmParameter(0, u16(1)),
                    Iap2CsmParameter(1, nulTerminated(identity.ssid)),
                    Iap2CsmParameter(2, EMPTY),
                    Iap2CsmParameter(3, u16(1)),
                    Iap2CsmParameter(4, EMPTY),
                    Iap2CsmParameter(5, EMPTY),
                ),
            )

        private fun nulTerminated(value: String): ByteArray = value.encodeToByteArray() + byteArrayOf(0)

        private fun u16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

        private fun u16List(values: IntArray): ByteArray = ByteArray(values.size * 2).also { bytes ->
            values.forEachIndexed { index, value ->
                bytes[index * 2] = (value ushr 8).toByte()
                bytes[index * 2 + 1] = value.toByte()
            }
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw IphoneUsbException.TimedOut("iAP2 identification timed out")
            return min(MAXIMUM_TIMEOUT_MILLIS, (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
        }

        private val EMPTY = ByteArray(0)
        /* Keep this list paired with Iap2WiredControlClient; no stop or AA messages are claimed. */
        private val MESSAGES_SENT_BY_ACCESSORY = intArrayOf(
            0x5000, // StartNowPlayingUpdates
            0x5002, // StopNowPlayingUpdates
            0x5200, // StartRouteGuidanceUpdates
            0x5203, // StopRouteGuidanceUpdates
            0xae00, // StartPowerUpdates
            0xae02, // StopPowerUpdates
            0x4157, // StartCommunicationsUpdates
            0x4159, // StopCommunicationsUpdates
            0x4154, // StartCallStateUpdates
            0x4156, // StopCallStateUpdates
            0xae03, // PowerSourceUpdate
            0x4301, // CarPlayStartSession
        )
        private val MESSAGES_RECEIVED_FROM_PHONE = intArrayOf(
            0xea00, // StartExternalAccessoryProtocolSession
            0xea01, // StopExternalAccessoryProtocolSession
            0x5001, // NowPlayingUpdate
            0x5201, // RouteGuidanceUpdate
            0x5202, // RouteGuidanceManeuverUpdate
            0xae01, // PowerUpdate
            0x4158, // CommunicationsUpdate
            0x4155, // CallStateUpdate
            0x4300, // CarPlayAvailability
        )
        private val MESSAGES_SENT_BY_WIRELESS_ACCESSORY =
            MESSAGES_SENT_BY_ACCESSORY.filterNot { it == 0xae03 }.toIntArray() + 0x5703
        private val MESSAGES_RECEIVED_FROM_WIRELESS_PHONE = MESSAGES_RECEIVED_FROM_PHONE +
            intArrayOf(0x4e0d, 0x4e0e, 0x5702)
    }
}
