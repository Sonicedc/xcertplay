package com.shilapi.xcertplay.transport

/**
 * Untagged Ethernet II framing around IPv6 payloads, the shape NCM datagrams carry.
 *
 * The NCM data path moves raw Ethernet frames. This codec is the seam the later Android VPN layer
 * uses to exchange IPv6 packets with the phone; it does not create or own a network interface.
 */
object EthernetIpv6Codec {
    const val ETHERTYPE_IPV6 = 0x86dd
    const val MAC_BYTES = 6

    private const val HEADER_BYTES = 14

    data class Ipv6Frame(
        val sourceMac: ByteArray,
        val destinationMac: ByteArray,
        val ipv6: ByteArray,
    )

    /** Returns null for tagged frames, non-IPv6 frames, or truncated input. */
    fun parseIpv6(frame: ByteArray): Ipv6Frame? {
        if (frame.size < HEADER_BYTES || readU16(frame, 12) != ETHERTYPE_IPV6) return null
        return Ipv6Frame(
            sourceMac = frame.copyOfRange(6, 12),
            destinationMac = frame.copyOfRange(0, 6),
            ipv6 = frame.copyOfRange(HEADER_BYTES, frame.size),
        )
    }

    fun build(sourceMac: ByteArray, destinationMac: ByteArray, ipv6: ByteArray): ByteArray {
        require(sourceMac.size == MAC_BYTES) { "sourceMac must be $MAC_BYTES bytes" }
        require(destinationMac.size == MAC_BYTES) { "destinationMac must be $MAC_BYTES bytes" }
        require(ipv6.isNotEmpty()) { "ipv6 payload must not be empty" }

        val frame = ByteArray(HEADER_BYTES + ipv6.size)
        destinationMac.copyInto(frame, 0)
        sourceMac.copyInto(frame, 6)
        putU16(frame, 12, ETHERTYPE_IPV6)
        ipv6.copyInto(frame, HEADER_BYTES)
        return frame
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)
}
