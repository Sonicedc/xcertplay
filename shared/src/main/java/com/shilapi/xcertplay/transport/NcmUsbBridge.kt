package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val claimedInterfaces: List<UsbInterface>,
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var pendingRead: UsbRequest? = null
    private var sequence = 0
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        val transferred = connection.bulkTransfer(outEndpoint, block, block.size, timeoutMillis)
        if (transferred != block.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "NCM write transferred $transferred of ${block.size} bytes",
            )
        }
    }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunk = readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                    ?: continue
                buffered += chunk
            }
        }
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            pendingRead
        }
        requestToCancel?.cancel()
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        connection.close()
    }

    private fun drainFrames() {
        while (true) {
            if (buffered.size < 12) return
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                throw failSession("NCM read buffer does not begin with an NTB16 header")
            }
            val blockLength = readU16(buffered, 8)
            if (blockLength < 28) throw failSession("Invalid NTB16 block length $blockLength")
            if (buffered.size < blockLength) return
            for (frame in Ntb16Codec.parse(buffered.copyOfRange(0, blockLength))) enqueueFrame(frame)
            var consumed = blockLength
            if (blockLength % USB_PACKET_SIZE == 0 &&
                buffered.size > blockLength &&
                buffered[blockLength].toInt() == 0
            ) {
                consumed++
            }
            buffered = buffered.copyOfRange(consumed, buffered.size)
        }
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): ByteArray? {
        val request = UsbRequest()
        var initialized = false
        try {
            if (!request.initialize(connection, inEndpoint)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not initialize NCM read request")
            }
            initialized = true
            synchronized(stateLock) {
                checkOpenLocked()
                pendingRead = request
            }
            val buffer = ByteBuffer.allocateDirect(READ_CHUNK_BYTES)
            if (!request.queue(buffer)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not queue NCM read request")
            }
            val completed = try {
                connection.requestWait(timeoutMillis)
            } catch (_: TimeoutException) {
                drainCancelledRead(request)
                return null
            }
            if (completed == null) throw failSession("Android returned no NCM read request")
            if (completed !== request) throw failSession("Android completed an unexpected USB request")
            return ByteArray(buffer.position()).also {
                buffer.flip()
                buffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        } finally {
            synchronized(stateLock) {
                if (pendingRead === request) pendingRead = null
            }
            if (initialized) request.cancel()
            request.close()
        }
    }

    private fun drainCancelledRead(request: UsbRequest) {
        if (!request.cancel()) throw failSession("Android could not cancel timed out NCM read request")
        val completed = try {
            connection.requestWait(CANCEL_DRAIN_TIMEOUT_MILLIS)
        } catch (_: TimeoutException) {
            throw failSession("Timed out draining cancelled NCM read request")
        }
        if (completed !== request) throw failSession("Android did not drain the cancelled NCM read request")
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val CANCEL_DRAIN_TIMEOUT_MILLIS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(connection: UsbDeviceConnection, function: NcmFunctionDiscovery.NcmFunction): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = connection.claimInterface(first, true)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${first.alternateSetting} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = connection.claimInterface(function.data, true)
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${function.data.alternateSetting}" +
                            " class=${function.data.interfaceClass} ok=$dataClaimed",
                    )
                    if (!dataClaimed) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                val altSelected = connection.setInterface(function.data)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${function.data.alternateSetting} ok=$altSelected",
                )
                if (!altSelected) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                return NcmUsbBridge(connection, function.bulkOut, function.bulkIn, claimed)
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }
    }
}
