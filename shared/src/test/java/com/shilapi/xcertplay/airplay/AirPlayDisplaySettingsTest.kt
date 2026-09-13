package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayDisplaySettingsTest {
    @Test
    fun fpsUsesThirtyToSixtyInFiveFrameSteps() {
        assertEquals(30, AirPlayDisplaySettings.sanitizeFps(0))
        assertEquals(35, AirPlayDisplaySettings.sanitizeFps(37))
        assertEquals(40, AirPlayDisplaySettings.sanitizeFps(38))
        assertEquals(60, AirPlayDisplaySettings.sanitizeFps(99))
        assertEquals(6, AirPlayDisplaySettings.fpsProgress(60))
    }

    @Test
    fun physicalWidthUsesOneHundredToFourHundredInFiftyMillimeterSteps() {
        assertEquals(100, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(0))
        assertEquals(100, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(120))
        assertEquals(150, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(125))
        assertEquals(400, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(999))
        assertEquals(2, AirPlayDisplaySettings.widthPhysicalMmProgress(200))
    }
}
