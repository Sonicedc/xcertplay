package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream

/**
 * Blocking CH341 implementation of [I2cTransport]. One transport owns one USB session and
 * serializes its configure/write/read sequence so stream packets cannot interleave.
 */
class Ch341I2cTransport(
    private val session: Ch341UsbSession,
    private val speed: Ch341I2cSpeed = Ch341I2cSpeed.KHZ_100,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : I2cTransport {
    private val lock = Any()

    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
        val stream = Ch341I2cStreamEncoder.transaction(address7Bit, writeData, readLength)
        val transactionTimeoutMillis = maxOf(timeoutMillis, minimumTransferTimeoutMillis(writeData.size, readLength))
        return synchronized(lock) {
            configure()
            val response = ByteArrayOutputStream(readLength)
            var offset = 0
            while (offset < stream.size) {
                val end = minOf(offset + Ch341I2cStreamEncoder.MAX_STREAM_PACKET_BYTES, stream.size)
                val segment = stream.copyOfRange(offset, end)
                session.bulkWrite(segment, transactionTimeoutMillis)
                val segmentReadLength = responseLength(segment)
                if (segmentReadLength > 0) {
                    response.write(session.bulkRead(segmentReadLength, transactionTimeoutMillis))
                }
                offset = end
            }
            val bytes = response.toByteArray()
            if (bytes.size != readLength) {
                throw I2cTransportException.Protocol(
                    "CH341 transaction returned ${bytes.size} bytes; expected $readLength",
                )
            }
            bytes
        }
    }

    /**
     * Pulses one D0..D5 output low, then releases it to input/high impedance. The entire low pulse
     * is executed inside one CH341 UIO stream, so USB scheduling cannot stretch it past the MFi
     * address-selection window. The caller must provide an external pull-up to the target's own
     * supply; this method deliberately never drives the GPIO high.
     */
    fun pulseActiveLowReset(gpio: Int, lowMicros: Int = MFI_RESET_LOW_MICROS) {
        require(gpio in 0..5) { "CH341 output GPIO must be D0..D5" }
        require(lowMicros in 10..UIO_DELAY_MASK) { "reset pulse must be 10..$UIO_DELAY_MASK microseconds" }
        val pinMask = 1 shl gpio
        val stream = byteArrayOf(
            UIO_STREAM.toByte(),
            UIO_OUT.toByte(), // preload every output latch low while pins are inputs
            (UIO_DIR or pinMask).toByte(),
            (UIO_DELAY_US or lowMicros).toByte(),
            UIO_DIR.toByte(), // release D0..D5 to input/high impedance
            UIO_END.toByte(),
        )
        synchronized(lock) {
            session.bulkWrite(stream, timeoutMillis)
            try {
                Thread.sleep(MFI_RESET_STARTUP_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw I2cTransportException.DeviceUnavailable("Interrupted after MFi reset pulse", interrupted)
            }
        }
    }

    /** Counts bytes produced by the read commands in one CH341 stream packet. */
    private fun responseLength(segment: ByteArray): Int {
        var index = 1 // 0xAA stream marker
        var result = 0
        while (index < segment.size) {
            val command = segment[index].toInt() and 0xff
            index += 1
            when {
                command == STREAM_END -> return result
                command == START || command == STOP -> Unit
                command in WRITE_MIN..WRITE_MAX -> index += command and LENGTH_MASK
                command in READ_MIN..READ_MAX -> {
                    val encodedLength = command and LENGTH_MASK
                    result += if (encodedLength == 0) 1 else encodedLength
                }
                command in SET_MIN..SET_MAX -> Unit
                else -> throw I2cTransportException.Protocol(
                    "Unexpected CH341 I2C stream command 0x${command.toString(16)}",
                )
            }
            if (index > segment.size) {
                throw I2cTransportException.Protocol("Truncated CH341 I2C stream command")
            }
        }
        return result
    }

    private fun configure() {
        session.bulkWrite(Ch341I2cStreamEncoder.configuration(speed), timeoutMillis)
    }

    /**
     * A simple lower bound for a complete I2C transaction at the selected clock, plus fixed USB
     * scheduling margin. The caller's configured timeout can still be longer. This is not a
     * hardware-performance claim; it only avoids rejecting a legal maximum-sized transfer under
     * the old one-second default before its nominal wire time has elapsed.
     */
    private fun minimumTransferTimeoutMillis(writeLength: Int, readLength: Int): Int {
        val i2cBytes = writeLength.toLong() + readLength + I2C_TRANSACTION_OVERHEAD_BYTES
        val wireMillis = (i2cBytes * BITS_PER_I2C_BYTE * MILLIS_PER_SECOND + speed.bitsPerSecond - 1) /
            speed.bitsPerSecond
        return (wireMillis + TRANSFER_MARGIN_MILLIS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 1_000
        const val I2C_TRANSACTION_OVERHEAD_BYTES = 8L
        const val BITS_PER_I2C_BYTE = 9L
        const val MILLIS_PER_SECOND = 1_000L
        const val TRANSFER_MARGIN_MILLIS = 1_000L
        const val STREAM_END = 0x00
        const val START = 0x74
        const val STOP = 0x75
        const val SET_MIN = 0x60
        const val SET_MAX = 0x63
        const val WRITE_MIN = 0x80
        const val WRITE_MAX = 0xbf
        const val READ_MIN = 0xc0
        const val READ_MAX = 0xff
        const val LENGTH_MASK = 0x3f
        const val UIO_STREAM = 0xab
        const val UIO_OUT = 0x80
        const val UIO_DIR = 0x40
        const val UIO_DELAY_US = 0xc0
        const val UIO_DELAY_MASK = 0x3f
        const val UIO_END = 0x20
        const val MFI_RESET_LOW_MICROS = 20
        const val MFI_RESET_STARTUP_MILLIS = 11L
    }
}
