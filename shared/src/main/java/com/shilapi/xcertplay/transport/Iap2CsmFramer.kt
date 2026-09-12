package com.shilapi.xcertplay.transport

/**
 * One decoded iAP2 Control Session Message (CSM).
 *
 * [payload] is the message body after the six-byte CSM header. Both input and output byte arrays
 * are copied so a frame remains stable while it waits for the next protocol stage.
 */
class CsmFrame(messageId: Int, payload: ByteArray) {
    val messageId: Int
    private val body: ByteArray

    init {
        require(messageId in 0..0xffff) { "CSM message id must fit in u16" }
        require(payload.size <= Iap2CsmFramer.MAX_FRAME_BYTES - Iap2CsmFramer.HEADER_BYTES) {
            "CSM payload exceeds ${Iap2CsmFramer.MAX_FRAME_BYTES - Iap2CsmFramer.HEADER_BYTES} bytes"
        }
        this.messageId = messageId
        body = payload.copyOf()
    }

    /** A defensive copy of the payload, excluding the CSM header. */
    val payload: ByteArray get() = body.copyOf()

    /** A defensive copy of this frame's complete six-byte-header CSM encoding. */
    fun encodedFrame(): ByteArray = Iap2CsmFramer.encodeFrame(messageId, body)

    override fun equals(other: Any?): Boolean =
        other is CsmFrame && messageId == other.messageId && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * messageId + body.contentHashCode()

    override fun toString(): String = "CsmFrame(messageId=0x${messageId.toString(16).padStart(4, '0')}, payload=${body.size} bytes)"
}

/**
 * Pure CSM framing for iAP2 control-session bytes.
 *
 * CSM is `0x4040 | u16 length | u16 messageId | payload`, big-endian and without a checksum.
 * The retained receive data is bounded to one largest-valid frame. This object deliberately has no
 * threading, I/O, link-engine, or message-specific knowledge.
 */
class Iap2CsmFramer {
    private val receive = ReceiveBuffer(MAX_FRAME_BYTES)

    /**
     * Adds control-session bytes and returns every newly complete CSM frame.
     *
     * It tolerates arbitrary transport chunking, concatenated frames, garbage before `0x4040`, and
     * invalid lengths below [HEADER_BYTES] by advancing one byte and searching again.
     */
    fun offer(chunk: ByteArray): List<CsmFrame> {
        if (chunk.isEmpty()) return emptyList()

        val frames = ArrayList<CsmFrame>()
        var offset = 0
        while (offset < chunk.size) {
            drain(frames)
            val count = minOf(chunk.size - offset, receive.remainingCapacity())
            check(count > 0) { "CSM receive buffer could not make progress" }
            receive.append(chunk, offset, count)
            offset += count
        }
        drain(frames)
        return frames
    }

    private fun drain(frames: MutableList<CsmFrame>) {
        while (true) {
            while (receive.size >= 2 && !receive.hasStart()) receive.discard(1)
            if (receive.size < HEADER_BYTES) return

            val length = receive.u16(2)
            if (length < HEADER_BYTES) {
                receive.discard(1)
                continue
            }
            if (receive.size < length) return

            val messageId = receive.u16(4)
            val payload = receive.copyOfRange(HEADER_BYTES, length)
            receive.discard(length)
            frames += CsmFrame(messageId, payload)
        }
    }

    private class ReceiveBuffer(private val maximumSize: Int) {
        private val bytes = ByteArray(maximumSize)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return maximumSize - tail
        }

        fun append(source: ByteArray, offset: Int, count: Int) {
            require(count in 0..remainingCapacity())
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            require(count in 0..size)
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun hasStart(): Boolean = bytes[head] == START_HIGH && bytes[head + 1] == START_LOW

        fun u16(offset: Int): Int =
            ((bytes[head + offset].toInt() and 0xff) shl 8) or (bytes[head + offset + 1].toInt() and 0xff)

        fun copyOfRange(from: Int, to: Int): ByteArray = bytes.copyOfRange(head + from, head + to)

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val START = 0x4040
        const val HEADER_BYTES = 6
        const val MIN_FRAME_BYTES = HEADER_BYTES
        const val MAX_FRAME_BYTES = 0xffff
        const val MAX_PARAM_BYTES = 0xffff
        const val MAX_LINK_CHUNK_BYTES = 65_525

        private const val START_HIGH: Byte = 0x40
        private const val START_LOW: Byte = 0x40

        /** Encodes one complete CSM frame from a message id and its header-excluded payload. */
        fun encodeFrame(messageId: Int, payload: ByteArray): ByteArray {
            require(messageId in 0..0xffff) { "CSM message id must fit in u16" }
            require(payload.size <= MAX_FRAME_BYTES - HEADER_BYTES) {
                "CSM payload exceeds ${MAX_FRAME_BYTES - HEADER_BYTES} bytes"
            }
            val frame = ByteArray(HEADER_BYTES + payload.size)
            writeU16(frame, 0, START)
            writeU16(frame, 2, frame.size)
            writeU16(frame, 4, messageId)
            payload.copyInto(frame, HEADER_BYTES)
            return frame
        }

        /** Encodes one CSM parameter: `u16 length | u16 parameterId | payload`. */
        fun encodeParam(parameterId: Int, payload: ByteArray): ByteArray {
            require(parameterId in 0..0xffff) { "CSM parameter id must fit in u16" }
            require(payload.size <= MAX_PARAM_BYTES - 4) {
                "CSM parameter payload exceeds ${MAX_PARAM_BYTES - 4} bytes"
            }
            val parameter = ByteArray(4 + payload.size)
            writeU16(parameter, 0, parameter.size)
            writeU16(parameter, 2, parameterId)
            payload.copyInto(parameter, 4)
            return parameter
        }

        /** Splits a semantic frame into link payloads no larger than [linkChunkSize]. */
        fun splitForLink(frame: CsmFrame, linkChunkSize: Int): List<ByteArray> =
            splitForLink(frame.encodedFrame(), linkChunkSize)

        /**
         * Splits exactly one complete CSM frame into link payloads no larger than [linkChunkSize].
         * The complete frame is validated before any defensive-copy chunks are returned.
         */
        fun splitForLink(completeFrame: ByteArray, linkChunkSize: Int): List<ByteArray> {
            require(linkChunkSize in 1..MAX_LINK_CHUNK_BYTES) {
                "iAP2 link chunk size must be in 1..$MAX_LINK_CHUNK_BYTES"
            }
            requireCompleteFrame(completeFrame)
            return List((completeFrame.size + linkChunkSize - 1) / linkChunkSize) { index ->
                val start = index * linkChunkSize
                completeFrame.copyOfRange(start, minOf(start + linkChunkSize, completeFrame.size))
            }
        }

        private fun requireCompleteFrame(frame: ByteArray) {
            require(frame.size in MIN_FRAME_BYTES..MAX_FRAME_BYTES) { "CSM frame size must be 6..65535" }
            require(readU16(frame, 0) == START) { "CSM frame must start with 0x4040" }
            require(readU16(frame, 2) == frame.size) { "CSM frame length must equal its complete size" }
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

        private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }
    }
}
