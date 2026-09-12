package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Android USB Host discovery and authorization for a configured CH341 identity.
 *
 * This class intentionally stops before the CH341 I2C stream protocol. [openAsync] executes the
 * potentially blocking open/claim work on the supplied executor; callers should also close the
 * returned session away from the main thread.
 */
class Ch341UsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: Ch341DeviceMatcher,
    private val permissionAction: String = "${context.packageName}.CH341_USB_PERMISSION",
) {
    private val appContext = context.applicationContext
    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class OpenResult {
        data class Connected(val session: Ch341UsbSession) : OpenResult()
        data class Failed(val error: I2cTransportException) : OpenResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    @Throws(I2cTransportException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)

        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(device)
    }

    /** Returns null for unrelated broadcasts, malformed results, or devices outside the matcher. */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            PermissionResult.Granted(device)
        } else {
            PermissionResult.Denied(device)
        }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                parsePermissionResult(intent)?.let(onResult)
            }
        }
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        return Closeable { appContext.unregisterReceiver(receiver) }
    }

    /** Opens and claims bulk endpoints on [executor], never on the caller thread. */
    fun openAsync(device: UsbDevice, executor: Executor, callback: (OpenResult) -> Unit) {
        executor.execute {
            try {
                callback(OpenResult.Connected(open(device)))
            } catch (error: I2cTransportException) {
                callback(OpenResult.Failed(error))
            }
        }
    }

    private fun open(device: UsbDevice): Ch341UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw I2cTransportException.PermissionDenied("USB permission has not been granted")
        }
        val endpoints = findBulkEndpoints(device)
            ?: throw I2cTransportException.DeviceUnavailable("No USB interface has both bulk IN and OUT endpoints")
        val connection = usbManager.openDevice(device)
            ?: throw I2cTransportException.DeviceUnavailable("UsbManager could not open the CH341 device")

        if (!connection.claimInterface(endpoints.usbInterface, true)) {
            connection.close()
            throw I2cTransportException.DeviceUnavailable("Could not claim CH341 USB interface")
        }
        return Ch341UsbSession(connection, endpoints.usbInterface, endpoints.input, endpoints.output)
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw I2cTransportException.DeviceUnavailable("USB device is not a configured CH341 identity")
        }
    }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun findBulkEndpoints(device: UsbDevice): BulkEndpoints? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> input = input ?: endpoint
                    UsbConstants.USB_DIR_OUT -> output = output ?: endpoint
                }
            }
            if (input != null && output != null) return BulkEndpoints(usbInterface, input, output)
        }
        return null
    }

    private data class BulkEndpoints(
        val usbInterface: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )
}

/** Claimed CH341 USB resources. All transfer methods are blocking and must run off the main thread. */
class Ch341UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    val inputEndpoint: UsbEndpoint,
    val outputEndpoint: UsbEndpoint,
) : Closeable {
    private var closed = false

    @Synchronized
    internal fun bulkWrite(data: ByteArray, timeoutMillis: Int) {
        transfer(outputEndpoint, data, timeoutMillis, "write")
    }

    @Synchronized
    internal fun bulkRead(length: Int, timeoutMillis: Int): ByteArray {
        if (length <= 0) {
            throw I2cTransportException.InvalidRequest("Bulk read length must be positive")
        }
        val data = ByteArray(length)
        transfer(inputEndpoint, data, timeoutMillis, "read")
        return data
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    private fun transfer(endpoint: UsbEndpoint, data: ByteArray, timeoutMillis: Int, operation: String) {
        if (closed) throw I2cTransportException.DeviceUnavailable("CH341 USB session is closed")
        if (data.isEmpty()) throw I2cTransportException.InvalidRequest("Bulk $operation data must not be empty")
        if (timeoutMillis <= 0) {
            throw I2cTransportException.InvalidRequest("Bulk transfer timeout must be positive")
        }

        val transferred = try {
            connection.bulkTransfer(endpoint, data, data.size, timeoutMillis)
        } catch (error: SecurityException) {
            throw I2cTransportException.PermissionDenied("USB permission was denied during bulk $operation")
        } catch (error: RuntimeException) {
            throw I2cTransportException.DeviceUnavailable("CH341 bulk $operation failed", error)
        }
        if (transferred < 0) {
            // Android's bulkTransfer result does not identify whether the device timed out, NAKed,
            // or reported another USB failure. Do not manufacture a CH341 NAK classification.
            throw I2cTransportException.DeviceUnavailable("CH341 bulk $operation failed or timed out")
        }
        if (transferred != data.size) {
            throw I2cTransportException.Protocol(
                "CH341 bulk $operation transferred $transferred of ${data.size} bytes",
            )
        }
    }
}
