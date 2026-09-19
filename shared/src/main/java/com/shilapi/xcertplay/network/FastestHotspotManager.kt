package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Uses the head unit's firmware-managed 5 GHz SoftAP when possible and otherwise creates a
 * verified 5 GHz Wi-Fi Direct group. Selection happens before iAP2 sends Wi-Fi credentials, so
 * the iPhone sees only one network and the AirPlay session is never migrated underneath itself.
 */
class FastestHotspotManager(context: Context) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")

    @Volatile private var closed = false
    private var activeManager: WirelessHotspotManager? = null
    private var startedSystemSoftAp = false
    private var startedSystemSoftApWithRoot = false
    private var startedConfiguration: WifiConfiguration? = null

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "FastestHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadlineNanos = deadlineAfter(timeoutMillis)

        try {
            val info = startSystemSoftAp(remainingMillis(deadlineNanos))
            Log.i(TAG, "Selected firmware-managed 5 GHz SoftAP: $info")
            return info
        } catch (failure: Exception) {
            Log.w(TAG, "System 5 GHz SoftAP unavailable; falling back to 5 GHz P2P", failure)
            closeActiveManager()
            stopSystemSoftApIfOwned()
        }

        ensureOpen()
        val fallback = WifiP2pGroupManager(appContext)
        activeManager = fallback
        return fallback.start(remainingMillis(deadlineNanos))
    }

    override fun close() {
        if (closed) return
        closed = true
        closeActiveManager()
        stopSystemSoftApIfOwned()
    }

    private fun startSystemSoftAp(timeoutMillis: Long): WirelessHotspotInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            throw IOException("Automatic system SoftAP start is currently supported on Android 10")
        }
        val configuration = readLegacyConfiguration() ?: readRootLegacyConfiguration()
            ?: throw IOException("Firmware did not expose its configured SoftAP")
        validateFiveGhz(configuration)

        if (!isSoftApEnabled()) {
            val startedWithLegacyApi = configuration.raw?.let {
                setSoftApEnabled(it, enabled = true)
            } == true
            if (!startedWithLegacyApi && !setRootSystemTethering(enabled = true)) {
                throw IOException("Android rejected the root system tethering start request")
            }
            startedSystemSoftAp = true
            startedSystemSoftApWithRoot = !startedWithLegacyApi
            startedConfiguration = configuration.raw
            awaitSoftApEnabled(timeoutMillis.coerceAtMost(SOFT_AP_START_TIMEOUT_MILLIS))
        }

        ensureOpen()
        val manager = ManualHotspotManager(
            context = appContext,
            ssid = configuration.ssid,
            passphrase = configuration.passphrase,
            band = ManualHotspotBand.GHZ_5,
            channel = configuration.channel,
            security = configuration.security,
            backend = WirelessHotspotBackend.SYSTEM_SOFT_AP,
        )
        activeManager = manager
        return manager.start(timeoutMillis.coerceAtLeast(1L))
    }

    @SuppressLint("PrivateApi")
    private fun readLegacyConfiguration(): LegacySoftApConfiguration? = try {
        val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
        val raw = method.invoke(wifiManager) as? WifiConfiguration ?: return null
        val ssid = unquote(raw.SSID)?.takeIf { it.isNotBlank() } ?: return null
        val security = mapSecurity(raw)
        val passphrase = if (security == ManualHotspotSecurity.OPEN) {
            ""
        } else {
            unquote(raw.preSharedKey).orEmpty()
        }
        if (security != ManualHotspotSecurity.OPEN && passphrase.length !in 8..63) {
            throw IOException("System SoftAP password is unavailable")
        }
        val rawBand = readIntField(raw, "apBand")
        val band = rawBand?.let(::normalizeLegacySoftApBand)
        val channel = readIntField(raw, "apChannel")?.coerceAtLeast(0) ?: 0
        LegacySoftApConfiguration(raw, ssid, passphrase, security, band, channel)
    } catch (failure: IOException) {
        throw failure
    } catch (_: Throwable) {
        null
    }

    /**
     * This Android 10 firmware stores the active hotspot in the platform's legacy binary file,
     * but denies getWifiApConfiguration() to ordinary applications. The head-unit installation
     * is already rooted for direct MFi access, so use that same narrowly scoped root channel as a
     * read-only fallback. No SSID or password is compiled into the APK.
     */
    private fun readRootLegacyConfiguration(): LegacySoftApConfiguration? {
        val record = parseLegacySoftApConfig(
            runRootCommand("cat $LEGACY_SOFT_AP_CONFIG_PATH") ?: return null,
        ) ?: return null
        val frequencyMHz = readRootReportedFrequencyMhz()
        val observedChannel = frequencyMHz?.let(::wifiFrequencyMhzToChannel)
        return LegacySoftApConfiguration(
            raw = null,
            ssid = record.ssid,
            passphrase = record.passphrase,
            security = record.security,
            band = normalizeLegacySoftApBand(record.legacyBand),
            channel = record.channel.takeIf { it > 0 } ?: observedChannel ?: 0,
        )
    }

    private fun readRootReportedFrequencyMhz(): Int? {
        val output = runRootCommand("dumpsys wifi") ?: return null
        val text = output.toString(StandardCharsets.UTF_8)
        return REPORTED_FREQUENCY.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    private fun runRootCommand(command: String): ByteArray? = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.use { it.readBytes() }
        if (!process.waitFor(ROOT_COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() == 0) {
            output
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private fun validateFiveGhz(configuration: LegacySoftApConfiguration) {
        val frequency = wifiChannelToFrequencyMhz(configuration.channel, configuration.band)
        val verified = configuration.band == 2 ||
            (frequency != null && frequency in 5_150..5_895) ||
            (configuration.band == null && configuration.channel in 32..177)
        if (!verified) {
            throw IOException(
                "System SoftAP is not verifiably 5 GHz " +
                    "(band=${configuration.band}, channel=${configuration.channel})",
            )
        }
    }

    @SuppressLint("PrivateApi")
    private fun isSoftApEnabled(): Boolean {
        val reflectedState = try {
            val method = WifiManager::class.java.getMethod("getWifiApState")
            (method.invoke(wifiManager) as? Number)?.toInt()
        } catch (_: Throwable) {
            null
        }
        if (reflectedState == WIFI_AP_STATE_ENABLED) return true
        val dump = runRootCommand("dumpsys wifi")?.toString(StandardCharsets.UTF_8)
        return dump?.contains("current StateMachine mode: StartedState") == true
    }

    @SuppressLint("PrivateApi")
    private fun setSoftApEnabled(configuration: WifiConfiguration?, enabled: Boolean): Boolean =
        try {
            val method = WifiManager::class.java.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                java.lang.Boolean.TYPE,
            )
            method.invoke(wifiManager, configuration, enabled) as? Boolean == true
        } catch (failure: Throwable) {
            Log.w(TAG, "Legacy system SoftAP request failed", failure)
            false
        }

    private fun awaitSoftApEnabled(timeoutMillis: Long) {
        val deadline = deadlineAfter(timeoutMillis)
        while (!isSoftApEnabled()) {
            ensureOpen()
            if (System.nanoTime() >= deadline) {
                throw IOException("Timed out waiting for the firmware-managed SoftAP")
            }
            try {
                Thread.sleep(SOFT_AP_POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while starting the firmware-managed SoftAP", interrupted)
            }
        }
    }

    private fun setRootSystemTethering(enabled: Boolean): Boolean {
        val operation = if (enabled) "start" else "stop"
        val apkPath = appContext.applicationInfo.sourceDir ?: return false
        val command = "CLASSPATH=${shellQuote(apkPath)} app_process /system/bin " +
            "$ROOT_TETHERING_STARTER $operation"
        val output = runRootCommand(command)?.toString(StandardCharsets.UTF_8).orEmpty()
        val accepted = output.lineSequence().any { it.trim() == "OK" }
        if (!accepted) Log.w(TAG, "Root system tethering $operation request failed")
        return accepted
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun stopSystemSoftApIfOwned() {
        if (!startedSystemSoftAp) return
        if (startedSystemSoftApWithRoot) {
            setRootSystemTethering(enabled = false)
        } else {
            setSoftApEnabled(startedConfiguration, enabled = false)
        }
        startedSystemSoftAp = false
        startedSystemSoftApWithRoot = false
        startedConfiguration = null
    }

    private fun closeActiveManager() {
        try {
            activeManager?.close()
        } catch (_: Exception) {
            // Continue cleanup and fallback.
        } finally {
            activeManager = null
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("FastestHotspotManager is closed")
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) throw IOException("Timed out selecting a wireless hotspot")
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L)
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun readIntField(configuration: WifiConfiguration, name: String): Int? = try {
        WifiConfiguration::class.java.getField(name).getInt(configuration)
    } catch (_: ReflectiveOperationException) {
        null
    }

    private fun mapSecurity(configuration: WifiConfiguration): ManualHotspotSecurity {
        val keys = configuration.allowedKeyManagement ?: return ManualHotspotSecurity.OPEN
        val open = keys.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keys.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keys.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> ManualHotspotSecurity.OPEN
            wpa2 && sae -> ManualHotspotSecurity.WPA3_TRANSITION
            sae -> ManualHotspotSecurity.WPA3
            else -> ManualHotspotSecurity.WPA2
        }
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private data class LegacySoftApConfiguration(
        val raw: WifiConfiguration?,
        val ssid: String,
        val passphrase: String,
        val security: ManualHotspotSecurity,
        val band: Int?,
        val channel: Int,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val WIFI_AP_STATE_ENABLED = 13
        const val SOFT_AP_START_TIMEOUT_MILLIS = 8_000L
        const val SOFT_AP_POLL_MILLIS = 100L
        const val ROOT_COMMAND_TIMEOUT_MILLIS = 3_000L
        const val LEGACY_SOFT_AP_CONFIG_PATH = "/data/misc/wifi/softap.conf"
        const val ROOT_TETHERING_STARTER =
            "com.shilapi.xcertplay.network.RootTetheringStarter"
        val REPORTED_FREQUENCY = Regex("mReportedFrequency:\\s*(\\d+)")
    }
}

internal data class LegacySoftApRecord(
    val ssid: String,
    val passphrase: String,
    val security: ManualHotspotSecurity,
    val legacyBand: Int,
    val channel: Int,
)

/** Parses Android's WifiApConfigStore v1-v3 binary format. */
internal fun parseLegacySoftApConfig(bytes: ByteArray): LegacySoftApRecord? = try {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt()
        if (version !in 1..3) return null
        val ssid = input.readUTF().takeIf { it.isNotBlank() } ?: return null
        val legacyBand = input.readInt()
        val channel = input.readInt().coerceAtLeast(0)
        if (version >= 3) input.readBoolean() // hidden SSID
        val authType = input.readInt()
        val security = when (authType) {
            WifiConfiguration.KeyMgmt.NONE -> ManualHotspotSecurity.OPEN
            WifiConfiguration.KeyMgmt.SAE -> ManualHotspotSecurity.WPA3
            else -> ManualHotspotSecurity.WPA2
        }
        val passphrase = if (security == ManualHotspotSecurity.OPEN) "" else input.readUTF()
        if (security != ManualHotspotSecurity.OPEN && passphrase.length !in 8..63) return null
        LegacySoftApRecord(ssid, passphrase, security, legacyBand, channel)
    }
} catch (_: Throwable) {
    null
}
