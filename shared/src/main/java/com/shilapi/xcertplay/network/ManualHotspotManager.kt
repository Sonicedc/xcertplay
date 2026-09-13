package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Attaches to a hotspot that is already running on this device.
 *
 * The hotspot remains owned by the system. This manager only locates its interface and reads the
 * channel/security data that the public Android APIs expose. Some vendors hide the current SoftAP
 * configuration, in which case the caller-supplied credentials remain authoritative and the iAP2
 * channel is reported as zero ("auto").
 */
class ManualHotspotManager(
    context: Context,
    ssid: String,
    passphrase: String,
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val expectedSsid = ssid
    private val passphrase = passphrase

    @Volatile
    private var closed = false

    init {
        require(expectedSsid.isNotBlank()) { "ssid must not be blank" }
        require('\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ManualHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        val apConfiguration = readApConfiguration()
        if (apConfiguration != null && apConfiguration.ssid != expectedSsid) {
            throw IOException(
                "Manual hotspot SSID does not match the active local AP configuration: " +
                    "'${apConfiguration.ssid}'",
            )
        }

        var lastReason = "local hotspot interface was not found"
        while (true) {
            check(!closed) { "ManualHotspotManager is closed" }
            val localInterface = findLocalHotspotInterface()
            if (localInterface != null) {
                val connectionFrequency = frequencyFromConnectionInfo()
                val scanFrequency = frequencyFromScanResult(localInterface)
                val frequencyMHz = apConfiguration?.frequencyMHz
                    ?: connectionFrequency
                    ?: scanFrequency
                val channel = apConfiguration?.channel?.takeIf { it > 0 }
                    ?: frequencyMHz?.let(::wifiFrequencyMhzToChannel)
                    ?: 0
                val security = apConfiguration?.security
                    ?: if (passphrase.isEmpty()) {
                        Iap2WirelessSecurity.NONE
                    } else {
                        Iap2WirelessSecurity.WPA_WPA2
                    }
                if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
                    throw IOException("Manual hotspot is secured but no passphrase was provided")
                }

                if (channel == 0) {
                    Log.w(
                        TAG,
                        "Could not read the active hotspot channel from Android public APIs; " +
                            "reporting iAP2 channel 0 (auto)",
                    )
                }
                return WirelessHotspotInfo(
                    ssid = expectedSsid,
                    passphrase = passphrase,
                    security = security,
                    channel = channel,
                    frequencyMHz = frequencyMHz,
                    bssid = localInterface.hardwareAddress,
                    interfaceName = localInterface.name,
                    hostAddress = localInterface.hostAddress,
                    bandLabel = frequencyMHz?.let(::bandLabel) ?: "Unknown band",
                    backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
                )
            }

            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for the manual hotspot: " +
                        lastReason,
                )
            }
            sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
        }
    }

    override fun close() {
        closed = true
    }

    private fun findLocalHotspotInterface(): LocalHotspotInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        val primaryInterface = connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }
        return Collections.list(interfaces)
            .asSequence()
            .filter { isUsableInterface(it, primaryInterface) }
            .mapNotNull { networkInterface ->
                networkInterface.hotspotAddress()?.let { address ->
                    LocalHotspotInterface(
                        name = networkInterface.name,
                        hostAddress = address,
                        hardwareAddress = networkInterface.hardwareAddress?.toMacAddressString(),
                        score = interfaceScore(networkInterface.name, address),
                    )
                }
            }
            .maxByOrNull(LocalHotspotInterface::score)
    }

    private fun isUsableInterface(
        networkInterface: NetworkInterface,
        primaryInterface: String?,
    ): Boolean = try {
        networkInterface.name != primaryInterface &&
            !networkInterface.isLoopback &&
            networkInterface.isUp &&
            EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) }
    } catch (_: SocketException) {
        false
    }

    private fun interfaceScore(name: String, address: InetAddress): Int {
        var score = when {
            name.startsWith("ap") || name.contains("softap", ignoreCase = true) -> 100
            name.startsWith("p2p") -> 80
            name.startsWith("wlan") -> 70
            else -> 0
        }
        if (address is Inet4Address) {
            val bytes = address.address
            when {
                bytes[0] == 192.toByte() && bytes[1] == 168.toByte() -> score += 30
                address.isSiteLocalAddress -> score += 20
            }
        }
        if (address is Inet6Address && address.isLinkLocalAddress) score += 15
        return score
    }

    private fun NetworkInterface.hotspotAddress(): InetAddress? {
        var ipv4: InetAddress? = null
        for (address in Collections.list(inetAddresses)) {
            if (address is Inet6Address && address.isLinkLocalAddress) {
                if (address.scopeId == index) return address
                try {
                    return Inet6Address.getByAddress(null, address.address, this)
                } catch (_: UnknownHostException) {
                    continue
                }
            }
            if (address is Inet4Address && !address.isLoopbackAddress && ipv4 == null) {
                ipv4 = address
            }
        }
        return ipv4
    }

    private fun frequencyFromConnectionInfo(): Int? {
        val connectionInfo = try {
            wifiManager.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (unquote(connectionInfo.ssid) != expectedSsid) return null
        return connectionInfo.frequency.takeIf { it > 0 }
    }

    private fun frequencyFromScanResult(localInterface: LocalHotspotInterface): Int? {
        val localBssid = localInterface.hardwareAddress ?: return null
        val scanResults = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            return null
        }
        return scanResults.firstOrNull { result ->
            result.SSID == expectedSsid &&
                result.BSSID.equals(localBssid, ignoreCase = true) &&
                result.frequency > 0
        }?.frequency
    }

    @SuppressLint("PrivateApi")
    private fun readApConfiguration(): ManualApConfiguration? =
        readSoftApConfiguration() ?: readLegacyApConfiguration()

    @SuppressLint("PrivateApi")
    private fun readSoftApConfiguration(): ManualApConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val method = WifiManager::class.java.getMethod("getSoftApConfiguration")
            val configuration = method.invoke(wifiManager) as? SoftApConfiguration
                ?: return null
            val ssid = configuration.ssid ?: return null
            val bandAndChannel = when {
                Build.VERSION.SDK_INT >= 36 -> {
                    val channels = configuration.channels
                    if (channels.size() == 0) null else channels.keyAt(0) to channels.valueAt(0)
                }
                else -> {
                    val band = (
                        SoftApConfiguration::class.java
                            .getMethod("getBand")
                            .invoke(configuration) as? Number
                        )?.toInt()
                    val channel = SoftApConfiguration::class.java
                        .getMethod("getChannel")
                        .invoke(configuration) as? Number
                    if (band == null || channel == null) null else band to channel.toInt()
                }
            }
            val band = bandAndChannel?.first
            val channel = bandAndChannel?.second ?: 0
            ManualApConfiguration(
                ssid = ssid,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapSoftApSecurity(configuration.securityType),
            )
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    private fun readLegacyApConfiguration(): ManualApConfiguration? {
        return try {
            val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
            val configuration = method.invoke(wifiManager) as? WifiConfiguration
                ?: return null
            val ssid = unquote(configuration.SSID) ?: return null
            val channel = try {
                WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
            } catch (_: ReflectiveOperationException) {
                0
            }
            val band = try {
                WifiConfiguration::class.java.getField("apBand").getInt(configuration)
            } catch (_: ReflectiveOperationException) {
                null
            }
            ManualApConfiguration(
                ssid = ssid,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapWifiConfigurationSecurity(configuration),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        else -> Iap2WirelessSecurity.WPA_WPA2
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return Iap2WirelessSecurity.NONE
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> Iap2WirelessSecurity.WPA_WPA2
        }
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun sleep(nanos: Long) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the manual hotspot", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private class ManualApConfiguration(
        val ssid: String,
        val channel: Int,
        val frequencyMHz: Int?,
        val security: Iap2WirelessSecurity,
    )

    private class LocalHotspotInterface(
        val name: String,
        val hostAddress: InetAddress,
        val hardwareAddress: String?,
        val score: Int,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(250)
        val EXCLUDED_INTERFACE_PREFIXES = listOf(
            "lo",
            "dummy",
            "rmnet",
            "r_rmnet",
            "tun",
            "ppp",
            "sit",
            "ip6",
            "bond",
        )
    }
}
