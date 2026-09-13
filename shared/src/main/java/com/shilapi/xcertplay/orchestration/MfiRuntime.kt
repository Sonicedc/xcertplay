package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiDeviceScanner
import com.shilapi.xcertplay.transport.I2cTransport
import java.io.Closeable
import java.io.IOException

/** Signals the normal, retryable state where the MFi address probe found no chip. */
internal class MfiCoprocessorNotFoundException : IOException(
    "No MFi authentication coprocessor responded to the device probe",
)

/** An opened MFi coprocessor client plus the handle that releases its backing transport. */
class MfiSession(
    val client: MfiAuthenticationClient,
    private val closeable: Closeable?,
) : Closeable {
    override fun close() {
        closeable?.close()
    }
}

/** Runs the documented address probe and wraps the first responding MFi coprocessor. */
object MfiRuntime {
    fun scan(transport: I2cTransport): MfiAuthenticationClient {
        val chip = MfiDeviceScanner(transport).scan().chip
            ?: throw MfiCoprocessorNotFoundException()
        return MfiAuthenticationClient(transport, chip.address7Bit)
    }
}
