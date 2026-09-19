package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayPersistenceTest {
    @Test
    fun defaultsToAutomaticOnlyWhenSelectionIsUnsetOrInvalid() {
        assertEquals(WirelessHotspotMode.AUTO_FASTEST, resolveWirelessHotspotMode(null, 29))
        assertEquals(WirelessHotspotMode.AUTO_FASTEST, resolveWirelessHotspotMode("invalid", 29))
    }

    @Test
    fun preservesEveryExplicitAndroid10Selection() {
        WirelessHotspotMode.entries.forEach { selected ->
            assertEquals(selected, resolveWirelessHotspotMode(selected.name, 29))
        }
    }

    @Test
    fun usesSupportedFallbackForAndroid9P2pSelections() {
        assertEquals(
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
            resolveWirelessHotspotMode(WirelessHotspotMode.WIFI_P2P.name, 28),
        )
    }
}
