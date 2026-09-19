package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessHotspotManagerTest {
    @Test
    fun convertsWifiP2pFrequencyToIap2Channel() {
        assertEquals(1, wifiFrequencyMhzToChannel(2412))
        assertEquals(6, wifiFrequencyMhzToChannel(2437))
        assertEquals(14, wifiFrequencyMhzToChannel(2484))
        assertEquals(36, wifiFrequencyMhzToChannel(5180))
        assertEquals(149, wifiFrequencyMhzToChannel(5745))
        assertEquals(177, wifiFrequencyMhzToChannel(5885))
        assertEquals(1, wifiFrequencyMhzToChannel(5955))
    }

    @Test
    fun rejectsUnsupportedOrInvalidFrequencies() {
        assertNull(wifiFrequencyMhzToChannel(0))
        assertNull(wifiFrequencyMhzToChannel(2413))
        assertNull(wifiFrequencyMhzToChannel(6001))
    }

    @Test
    fun convertsKnownChannelAndBandToFrequency() {
        assertEquals(2412, wifiChannelToFrequencyMhz(1, band = 1))
        assertEquals(5180, wifiChannelToFrequencyMhz(36, band = 2))
        assertEquals(5955, wifiChannelToFrequencyMhz(1, band = 3))
        assertNull(wifiChannelToFrequencyMhz(0, band = 2))
        assertNull(wifiChannelToFrequencyMhz(36, band = null))
    }

    @Test
    fun doesNotReportDesiredChannelWhenAndroidDoesNotExposeOne() {
        assertEquals(
            0,
            observedManualHotspotChannel(
                apChannel = 0,
                connectionFrequencyMHz = null,
                scanFrequencyMHz = null,
                apFrequencyMHz = null,
            ),
        )
    }

    @Test
    fun reportsOnlyAnObservedActiveChannel() {
        assertEquals(
            36,
            observedManualHotspotChannel(
                apChannel = 36,
                connectionFrequencyMHz = null,
                scanFrequencyMHz = null,
                apFrequencyMHz = null,
            ),
        )
        assertEquals(
            44,
            observedManualHotspotChannel(
                apChannel = 0,
                connectionFrequencyMHz = 5220,
                scanFrequencyMHz = null,
                apFrequencyMHz = null,
            ),
        )
    }

    @Test
    fun normalizesLegacySoftApBands() {
        assertEquals(1, normalizeLegacySoftApBand(0))
        assertEquals(2, normalizeLegacySoftApBand(1))
        assertNull(normalizeLegacySoftApBand(2))
    }

    @Test
    fun parsesFirmwareLegacySoftApConfiguration() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(3)
                data.writeUTF("FirmwareAP")
                data.writeInt(1)
                data.writeInt(0)
                data.writeBoolean(false)
                data.writeInt(4)
                data.writeUTF("secret12")
            }
        }.toByteArray()

        val record = parseLegacySoftApConfig(bytes)!!
        assertEquals("FirmwareAP", record.ssid)
        assertEquals("secret12", record.passphrase)
        assertEquals(ManualHotspotSecurity.WPA2, record.security)
        assertEquals(1, record.legacyBand)
        assertEquals(0, record.channel)
    }
}
