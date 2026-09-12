package com.shilapi.xcertplay.transport

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
            session.bulkWrite(stream, transactionTimeoutMillis)
            if (readLength == 0) ByteArray(0) else session.bulkRead(readLength, transactionTimeoutMillis)
        }
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
    }
}
