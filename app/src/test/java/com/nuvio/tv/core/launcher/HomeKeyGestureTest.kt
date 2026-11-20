package com.nuvio.tv.core.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeKeyGestureTest {
    @Test
    fun `first press shortly after boot is single not ignored or double`() {
        for (uptime in listOf(0L, 50L, 200L, 1_000L)) {
            assertEquals(HomeKeyPress.SINGLE, HomeKeyGesture().onPress(uptime))
        }
    }

    @Test
    fun `first press reacts immediately and second quick press is double`() {
        val gesture = HomeKeyGesture()
        assertEquals(HomeKeyPress.SINGLE, gesture.onPress(1_000))
        assertEquals(HomeKeyPress.DOUBLE, gesture.onPress(1_250))
    }

    @Test
    fun `a later press is single again`() {
        val gesture = HomeKeyGesture()
        assertEquals(HomeKeyPress.SINGLE, gesture.onPress(1_000))
        assertEquals(HomeKeyPress.SINGLE, gesture.onPress(2_000))
    }

    @Test
    fun `duplicate and stale deliveries do not move the accepted press clock`() {
        val gesture = HomeKeyGesture()
        assertEquals(HomeKeyPress.SINGLE, gesture.onPress(1_000))
        assertEquals(HomeKeyPress.IGNORED, gesture.onPress(1_000))
        assertEquals(HomeKeyPress.IGNORED, gesture.onPress(1_050))
        assertEquals(HomeKeyPress.IGNORED, gesture.onPress(900))
        assertEquals(HomeKeyPress.DOUBLE, gesture.onPress(1_150))
    }

    @Test
    fun `double tap includes both timing boundaries`() {
        for (gap in listOf(HomeKeyGesture.DEFAULT_MIN_GAP_MS, HomeKeyGesture.DEFAULT_DOUBLE_TAP_WINDOW_MS)) {
            val gesture = HomeKeyGesture()
            gesture.onPress(1_000)
            assertEquals(HomeKeyPress.DOUBLE, gesture.onPress(1_000 + gap))
        }
    }

    @Test
    fun `a press beyond the double tap window is single`() {
        val gesture = HomeKeyGesture()
        gesture.onPress(1_000)
        assertEquals(HomeKeyPress.SINGLE, gesture.onPress(1_001 + HomeKeyGesture.DEFAULT_DOUBLE_TAP_WINDOW_MS))
    }
}
