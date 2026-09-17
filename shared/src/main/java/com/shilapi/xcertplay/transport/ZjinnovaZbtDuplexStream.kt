package com.shilapi.xcertplay.transport

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Full-duplex RFCOMM byte stream carried by the Zjinnova Bluetooth daemon's local ZBT protocol.
 *
 * Some 6125 head units do not expose Apple's vendor RFCOMM UUID through Android's public
 * BluetoothSocket API. Their preinstalled Bluetooth service instead exposes a loopback TCP
 * endpoint and transports RFCOMM bytes in ZBT message 0x105. This client uses that endpoint
 * directly and does not start or depend on the ZLINK application.
 */
class ZjinnovaZbtDuplexStream private constructor(
    private val socket: Socket,
) : BlockingDuplexByteStream {
    private val lock = Object()
    private val sendLock = Object()
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var peerEnded = false
    private var closed = false
    private var failure: IOException? = null

    private val reader = Thread(::readLoop, "xcertplay-zjinnova-zbt-reader").apply {
        isDaemon = true
    }

    init {
        sendPacket(MESSAGE_INIT_REQUEST, INIT_REQUEST)
        reader.start()
    }

    override fun send(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) {
            failure?.let { throw it }
            if (closed) throw IOException("Zjinnova ZBT stream is closed")
        }
        sendPacket(MESSAGE_RFCOMM_DATA, data)
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        synchronized(lock) {
            while (true) {
                takePendingLocked(maxBytes)?.let { return it }
                failure?.let { throw it }
                if (peerEnded || closed) return EMPTY

                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return null
                try {
                    lock.wait(
                        remainingNanos / NANOS_PER_MILLISECOND,
                        (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    override fun close() {
        val firstClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                lock.notifyAll()
                true
            }
        }
        if (!firstClose) return

        runCatching { sendPacket(MESSAGE_DISCONNECT, EMPTY) }
        runCatching { socket.close() }
        if (Thread.currentThread() !== reader) {
            try {
                reader.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun readLoop() {
        try {
            while (!isClosed()) {
                val header = readExactly(HEADER_BYTES) ?: break
                if (readInt(header, 0) != MAGIC || readInt(header, 4) != VERSION) {
                    throw IOException("Invalid Zjinnova ZBT packet header")
                }
                val messageId = readInt(header, 8)
                val length = readInt(header, 12)
                if (length < 0 || length > MAX_PACKET_BYTES) {
                    throw IOException("Invalid Zjinnova ZBT packet length $length")
                }
                val body = if (length == 0) EMPTY else {
                    readExactly(length) ?: throw EOFException("ZBT packet ended after its header")
                }
                if (messageId == MESSAGE_RFCOMM_DATA && body.isNotEmpty()) {
                    enqueue(body)
                } else {
                    Log.d(TAG, "control message id=0x${messageId.toString(16)} bytes=$length")
                }
            }
            synchronized(lock) {
                peerEnded = true
                lock.notifyAll()
            }
        } catch (io: IOException) {
            if (!isClosed()) fail(io)
        } catch (error: Throwable) {
            if (!isClosed()) fail(IOException("Zjinnova ZBT reader failed", error))
            if (error is Error) throw error
        }
    }

    private fun sendPacket(messageId: Int, body: ByteArray) {
        synchronized(sendLock) {
            val header = ByteArray(HEADER_BYTES)
            writeInt(header, 0, MAGIC)
            writeInt(header, 4, VERSION)
            writeInt(header, 8, messageId)
            writeInt(header, 12, body.size)
            try {
                output.write(header)
                if (body.isNotEmpty()) output.write(body)
                output.flush()
            } catch (io: IOException) {
                if (!isClosed()) fail(io)
                throw io
            }
        }
    }

    private fun readExactly(length: Int): ByteArray? {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(data, offset, length - offset)
            if (count < 0) return if (offset == 0) null else throw EOFException("Short ZBT packet")
            if (count > 0) offset += count
        }
        return data
    }

    private fun enqueue(data: ByteArray) {
        synchronized(lock) {
            if (closed) return
            if (pendingBytes + data.size > MAX_PENDING_BYTES) {
                fail(IOException("Zjinnova ZBT receive queue overflow"))
                return
            }
            pending.addLast(data)
            pendingBytes += data.size
            lock.notifyAll()
        }
    }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val chunk = pending.pollFirst() ?: return null
        pendingBytes -= chunk.size
        if (chunk.size <= maxBytes) return chunk
        val head = chunk.copyOf(maxBytes)
        val tail = chunk.copyOfRange(maxBytes, chunk.size)
        pending.addFirst(tail)
        pendingBytes += tail.size
        return head
    }

    private fun fail(io: IOException) {
        synchronized(lock) {
            if (failure == null) failure = io
            lock.notifyAll()
        }
        runCatching { socket.close() }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    companion object {
        private const val TAG = "XCertPlay-ZBT"
        private const val HOST = "127.0.0.1"
        private const val PORT = 3152
        private const val CONNECT_TIMEOUT_MILLIS = 800
        private const val HEADER_BYTES = 16
        private const val MAGIC = 0x0000ffff
        private const val VERSION = 0x00000101
        private const val MESSAGE_INIT_REQUEST = 0x101
        private const val MESSAGE_RFCOMM_DATA = 0x105
        private const val MESSAGE_DISCONNECT = 0x106
        private const val MAX_PACKET_BYTES = 1_048_576
        private const val MAX_PENDING_BYTES = 524_288
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val EMPTY = ByteArray(0)

        // protobuf RequestInit { id: 0x101, enable_type: CARPLAY }
        private val INIT_REQUEST = byteArrayOf(0x08, 0x81.toByte(), 0x02, 0x10, 0x01)

        /** Returns null when this firmware does not expose the Zjinnova loopback service. */
        fun openIfAvailable(): ZjinnovaZbtDuplexStream? {
            val socket = Socket()
            return try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MILLIS)
                ZjinnovaZbtDuplexStream(socket)
            } catch (_: IOException) {
                runCatching { socket.close() }
                null
            }
        }
    }
}
