package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeAreaTest {
    @Test
    fun defaultEditorUsesQuarterInsetsFromEveryEdge() {
        val rect = AirPlaySafeArea.default(1920, 1080)

        assertEquals(480, rect.left)
        assertEquals(270, rect.top)
        assertEquals(1440, rect.right)
        assertEquals(810, rect.bottom)
    }

    @Test
    fun activityMappingIsScaledIntoNegotiatedDisplaySpace() {
        val mapping = SafeAreaRect(left = 10, top = 20, right = 1910, bottom = 1060)

        val insets = AirPlaySafeArea.toInsets(
            mapping = mapping,
            activityWidthPixels = 1920,
            activityHeightPixels = 1080,
            displayWidthPixels = 960,
            displayHeightPixels = 540,
        )

        assertEquals(5, insets.left)
        assertEquals(10, insets.top)
        assertEquals(5, insets.right)
        assertEquals(10, insets.bottom)
    }

    @Test
    fun missingMappingResolvesToFullDisplay() {
        val insets = AirPlaySafeArea.toInsets(
            mapping = null,
            activityWidthPixels = 1920,
            activityHeightPixels = 1080,
            displayWidthPixels = 576,
            displayHeightPixels = 324,
        )

        assertEquals(AirPlayInsets(), insets)
    }

    @Test
    fun editorCanScaleBetweenActivitySizesWithoutChangingRatios() {
        val activityRect = SafeAreaRect(1900, 1000, 2100, 1200)

        val editorRect = AirPlaySafeArea.scaleRect(
            rect = activityRect,
            sourceWidthPixels = 2400,
            sourceHeightPixels = 1600,
            targetWidthPixels = 1200,
            targetHeightPixels = 800,
        )
        val restored = AirPlaySafeArea.scaleRect(
            rect = editorRect,
            sourceWidthPixels = 1200,
            sourceHeightPixels = 800,
            targetWidthPixels = 2400,
            targetHeightPixels = 1600,
        )

        assertEquals(activityRect, restored)
    }

    @Test
    fun codecRoundTripsAndRejectsInvalidData() {
        val rect = SafeAreaRect(100, 200, 900, 700)

        assertEquals(rect, SafeAreaCodec.decode(SafeAreaCodec.encode(rect)))
        assertNull(SafeAreaCodec.decode("1,2,3"))
        assertNull(SafeAreaCodec.decode("1,2,1,4"))
    }
}
