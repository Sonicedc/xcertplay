package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.net.Inet6Address
import java.net.InetAddress

enum class CarPlayTransport {
    WIRED,
    WIRELESS,
}

/**
 * Deployment-owned constants for one head unit. There are deliberately no built-in Apple or
 * CH341 product IDs: the physical devices attached to the target must be identified first.
 */
class CarPlayRuntimeConfig(
    val iphoneDevices: List<UsbDeviceId> = emptyList(),
    val ch341Devices: List<UsbDeviceId> = emptyList(),
    val ch341MfiResetGpio: Int? = null,
    val linuxI2cPath: String? = null,
    val hostMac: ByteArray = DEFAULT_HOST_MAC,
    val linkLocal: String = "fe80::2",
    val identification: Iap2IdentificationConfig,
    val availableCurrentMilliAmps: Int = 2400,
    val label: String = "xcertplay",
    val hostName: String = "xcertplay",
    val transport: CarPlayTransport = CarPlayTransport.WIRED,
    val wirelessBluetoothAddress: String? = null,
) {
    init {
        require(iphoneDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
        require(hostMac.size == 6) { "hostMac must be 6 bytes" }
        require(isLinkLocalIpv6(linkLocal)) { "linkLocal must be a link-local IPv6 literal" }
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostName.isNotBlank()) { "hostName must not be blank" }
        require(ch341Devices.isNotEmpty() || linuxI2cPath != null) {
            "Either ch341Devices or linuxI2cPath must be configured for MFi I2C"
        }
        require(ch341MfiResetGpio == null || ch341MfiResetGpio in 0..5) {
            "CH341 MFi reset GPIO must be D0..D5"
        }
        require(wirelessBluetoothAddress == null || BLUETOOTH_ADDRESS.matches(wirelessBluetoothAddress)) {
            "wirelessBluetoothAddress must be six colon-separated hexadecimal bytes"
        }
    }

    companion object {
        const val APPLE_VENDOR_ID = 0x05ac
        val DEFAULT_HOST_MAC = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        private fun isLinkLocalIpv6(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                val address = InetAddress.getByName(value)
                address is Inet6Address && address.isLinkLocalAddress
            } catch (_: Exception) {
                false
            }
        }
    }
}
