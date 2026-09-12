package com.shilapi.xcertplay.transport

/** CH341 I2C clock selections documented by the project's CH341 interface baseline. */
enum class Ch341I2cSpeed(internal val command: Int, internal val bitsPerSecond: Int) {
    KHZ_20(0x60, 20_000),
    KHZ_100(0x61, 100_000),
    KHZ_400(0x62, 400_000),
    KHZ_750(0x63, 750_000),
}

/**
 * Encodes a complete CH341 I2C transaction.
 *
 * A transaction may contain several 32-byte CH341 stream segments. Intermediate segments end
 * with `00` and padding, but deliberately omit STOP so the I2C transaction continues in the next
 * segment. The final segment contains STOP followed by `00`.
 */
object Ch341I2cStreamEncoder {
    const val MAX_STREAM_PACKET_BYTES = 32
    /** Matches the bounded I2C message size accepted by [LinuxI2cTransport]. */
    const val MAX_TRANSACTION_DATA_BYTES = 0xffff
    const val MAX_READ_BYTES = MAX_TRANSACTION_DATA_BYTES

    fun configuration(speed: Ch341I2cSpeed): ByteArray = byteArrayOf(
        STREAM_START.toByte(),
        speed.command.toByte(),
        STREAM_END.toByte(),
    )

    fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
        validateRequest(address7Bit, writeData.size, readLength)
        legacyTransaction(address7Bit, writeData, readLength)?.let { return it }

        val stream = SegmentedStream()
        if (writeData.isNotEmpty()) {
            stream.startAndWrite(addressByte(address7Bit, read = false), writeData)
        }
        if (readLength > 0) {
            stream.startAndWrite(addressByte(address7Bit, read = true), byteArrayOf())
            stream.read(readLength)
        }
        return stream.finish()
    }

    private fun legacyTransaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray? {
        if (readLength > MAX_READ_BLOCK_BYTES || writeData.size + 1 > MAX_WRITE_COMMAND_BYTES) return null

        val bytes = ArrayList<Byte>(MAX_STREAM_PACKET_BYTES)
        fun add(value: Int) {
            bytes += value.toByte()
        }
        fun write(address: Byte, data: ByteArray) {
            add(WRITE + data.size + 1)
            add(address.toInt() and 0xff)
            data.forEach { add(it.toInt() and 0xff) }
        }

        add(STREAM_START)
        if (writeData.isNotEmpty()) {
            add(START)
            write(addressByte(address7Bit, read = false), writeData)
        }
        if (readLength > 0) {
            add(START)
            write(addressByte(address7Bit, read = true), byteArrayOf())
            if (readLength > 1) add(READ + readLength - 1)
            add(READ)
        }
        add(STOP)
        add(STREAM_END)
        return bytes.takeIf { it.size <= MAX_STREAM_PACKET_BYTES }?.toByteArray()
    }

    private fun validateRequest(address7Bit: Int, writeLength: Int, readLength: Int) {
        if (address7Bit !in 0..0x7f) {
            throw I2cTransportException.InvalidRequest("I2C address must be a 7-bit value")
        }
        if (readLength < 0) {
            throw I2cTransportException.InvalidRequest("Read length must not be negative")
        }
        if (writeLength == 0 && readLength == 0) {
            throw I2cTransportException.InvalidRequest("I2C transaction must read or write data")
        }
        if (writeLength > MAX_TRANSACTION_DATA_BYTES || readLength > MAX_TRANSACTION_DATA_BYTES) {
            throw I2cTransportException.InvalidRequest(
                "CH341 transactions support at most $MAX_TRANSACTION_DATA_BYTES bytes per read or write",
            )
        }
    }

    private fun addressByte(address7Bit: Int, read: Boolean): Byte =
        ((address7Bit shl 1) or if (read) 1 else 0).toByte()

    private class SegmentedStream {
        private val result = ArrayList<Byte>()
        private var segment = ArrayList<Byte>(MAX_STREAM_PACKET_BYTES)

        init {
            segment += STREAM_START.toByte()
        }

        fun startAndWrite(address: Byte, data: ByteArray) {
            if (segment.size + START_AND_ADDRESS_BYTES + STREAM_END_BYTES > MAX_STREAM_PACKET_BYTES) {
                finishIntermediate()
            }
            segment += START.toByte()
            write(address, data)
        }

        fun write(address: Byte, data: ByteArray) {
            var offset = 0
            var includeAddress = true
            while (includeAddress || offset < data.size) {
                val overhead = if (includeAddress) 2 else 1
                val dataCapacity = MAX_STREAM_PACKET_BYTES - STREAM_END_BYTES - segment.size - overhead
                if (dataCapacity <= 0) {
                    if (includeAddress) {
                        command(bytes(WRITE + 1, address.toInt() and 0xff))
                        includeAddress = false
                        continue
                    }
                    finishIntermediate()
                    continue
                }
                val count = minOf(data.size - offset, dataCapacity, MAX_WRITE_COMMAND_BYTES - if (includeAddress) 1 else 0)
                val commandLength = count + if (includeAddress) 1 else 0
                val bytes = ByteArray(1 + commandLength)
                bytes[0] = (WRITE + commandLength).toByte()
                var index = 1
                if (includeAddress) bytes[index++] = address
                data.copyInto(bytes, index, offset, offset + count)
                command(bytes)
                offset += count
                includeAddress = false
            }
        }

        fun read(length: Int) {
            var remaining = length
            while (remaining > MAX_READ_BLOCK_BYTES) {
                command(byteArrayOf((READ + MAX_READ_BLOCK_BYTES).toByte()))
                finishIntermediate()
                remaining -= MAX_READ_BLOCK_BYTES
            }
            if (remaining > 1) command(byteArrayOf((READ + remaining - 1).toByte()))
            command(byteArrayOf(READ.toByte()))
        }

        fun finish(): ByteArray {
            if (segment.size + STOP_AND_END_BYTES > MAX_STREAM_PACKET_BYTES) finishIntermediate()
            segment += STOP.toByte()
            segment += STREAM_END.toByte()
            result += segment
            return result.toByteArray()
        }

        private fun command(bytes: ByteArray) {
            if (segment.size + bytes.size + STREAM_END_BYTES > MAX_STREAM_PACKET_BYTES) finishIntermediate()
            segment += bytes.toList()
        }

        private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

        private fun finishIntermediate() {
            segment += STREAM_END.toByte()
            while (segment.size < MAX_STREAM_PACKET_BYTES) segment += 0
            result += segment
            segment = arrayListOf(STREAM_START.toByte())
        }
    }

    private const val STREAM_START = 0xaa
    private const val START = 0x74
    private const val WRITE = 0x80
    private const val READ = 0xc0
    private const val STOP = 0x75
    private const val STREAM_END = 0x00
    private const val STREAM_END_BYTES = 1
    private const val STOP_AND_END_BYTES = 2
    private const val START_AND_ADDRESS_BYTES = 3
    private const val MAX_WRITE_COMMAND_BYTES = 0x3f
    private const val MAX_READ_BLOCK_BYTES = 32
}
