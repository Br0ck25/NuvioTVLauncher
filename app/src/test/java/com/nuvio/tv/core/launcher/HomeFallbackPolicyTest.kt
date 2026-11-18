package com.nuvio.tv.core.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFallbackPolicyTest {
    @Test
    fun `down repeats and canceled up do not launch or alter release timing`() {
        val policy = HomeFallbackPolicy()
        assertFalse(policy.acceptKeyRelease(900, isRelease = false))
        assertFalse(policy.acceptKeyRelease(950, isRelease = false))
        assertFalse(policy.acceptKeyRelease(1_000, isCanceled = true))
        assertTrue(policy.acceptKeyRelease(1_050))
    }

    @Test
    fun `duplicate or stale key releases never launch twice`() {
        val policy = HomeFallbackPolicy()
        assertTrue(policy.acceptKeyRelease(1_000))
        assertFalse(policy.acceptKeyRelease(1_000))
        assertFalse(policy.acceptKeyRelease(900))
        assertFalse(policy.acceptKeyRelease(1_050))
        assertTrue(policy.acceptKeyRelease(1_120))
    }

    @Test
    fun `rapid intentional presses retain the existing double home classification`() {
        val policy = HomeFallbackPolicy()
        val gesture = HomeKeyGesture()
        val actions = listOf(1_000L, 1_000L, 1_050L, 1_250L, 1_250L, 1_450L)
            .filter { policy.acceptKeyRelease(it) }.map(gesture::onPress)
        assertEquals(listOf(HomeKeyPress.SINGLE, HomeKeyPress.DOUBLE, HomeKeyPress.DOUBLE), actions)
    }

    @Test
    fun `repeated launcher windows do not generate fake presses even after debounce expires`() {
        val policy = HomeFallbackPolicy()
        assertTrue(policy.acceptLauncherWindow(0))
        policy.onLaunch(0)
        assertFalse(policy.acceptLauncherWindow(100))
        assertFalse(policy.acceptLauncherWindow(2_000))
        policy.onOtherApplicationWindow()
        assertTrue(policy.acceptLauncherWindow(2_100))
    }

    @Test
    fun `launcher window following physical home is suppressed and latched`() {
        val policy = HomeFallbackPolicy()
        assertTrue(policy.acceptKeyRelease(1_000))
        policy.onLaunch(1_000)
        assertFalse(policy.acceptLauncherWindow(1_100))
        assertFalse(policy.acceptLauncherWindow(2_000))
        // A real second HOME key is not blocked by the window debounce.
        assertTrue(policy.acceptKeyRelease(1_250))
    }

    @Test
    fun `window debounce includes its boundary and first event after boot is accepted`() {
        val policy = HomeFallbackPolicy()
        assertTrue(policy.acceptLauncherWindow(0))
        policy.onLaunch(0)
        policy.onOtherApplicationWindow()
        assertFalse(policy.acceptLauncherWindow(400))
        policy.onOtherApplicationWindow()
        assertTrue(policy.acceptLauncherWindow(401))
    }

    @Test
    fun `late stock launcher window right after our launch earns a recheck`() {
        val policy = HomeFallbackPolicy()
        assertTrue(policy.acceptKeyRelease(1_000))
        policy.onLaunch(1_000)
        // The window is suppressed (see test above) but a follow-up check is still warranted.
        assertFalse(policy.acceptLauncherWindow(1_045))
        assertTrue(policy.shouldScheduleRecheck(1_045))
        assertTrue(policy.shouldScheduleRecheck(2_000))
        assertFalse(policy.shouldScheduleRecheck(2_001))
    }

    @Test
    fun `no recheck without a physical press or a launch`() {
        val policy = HomeFallbackPolicy()
        assertFalse(policy.shouldScheduleRecheck(0))
        // A window-triggered launch alone (no key release) never grants a recheck.
        policy.onLaunch(100)
        assertFalse(policy.shouldScheduleRecheck(150))
        assertFalse(policy.consumeRecheck())
    }

    @Test
    fun `recheck budget is bounded per press and renewed by the next press`() {
        val policy = HomeFallbackPolicy()
        assertTrue(policy.acceptKeyRelease(1_000))
        policy.onLaunch(1_000)
        assertTrue(policy.consumeRecheck())
        assertTrue(policy.consumeRecheck())
        assertFalse(policy.consumeRecheck())
        assertFalse(policy.shouldScheduleRecheck(1_100))
        assertTrue(policy.acceptKeyRelease(1_300))
        policy.onLaunch(1_300)
        assertTrue(policy.shouldScheduleRecheck(1_350))
    }

    @Test
    fun `service restart starts a fresh delivery guard without a fake previous press`() {
        val previous = HomeFallbackPolicy()
        previous.acceptKeyRelease(1_000)
        previous.onLaunch(1_000)
        assertTrue(HomeFallbackPolicy().acceptKeyRelease(1_000))
        assertTrue(HomeFallbackPolicy().acceptLauncherWindow(0))
        // The Activity's detector survives a service-only restart and deduplicates redelivery.
        val gesture = HomeKeyGesture()
        assertEquals(HomeKeyPress.SINGLE, gesture.onPress(1_000))
        assertEquals(HomeKeyPress.IGNORED, gesture.onPress(1_000))
        assertEquals(HomeKeyPress.DOUBLE, gesture.onPress(1_250))
    }

    @Test
    fun `a stock launcher window that lands after our start grants a bounded re-check without a key press`() {
        val policy = HomeFallbackPolicy()
        // Our start, then the stock launcher's window right after it (screensaver wake): ignored by the latch.
        assertTrue(policy.acceptLauncherWindow(1_000))
        policy.onLaunch(1_000)
        assertFalse(policy.acceptLauncherWindow(1_700))
        // No physical press happened, so no re-check budget exists yet...
        assertFalse(policy.shouldScheduleRecheck(1_700))
        // ...the re-assert grants exactly one.
        assertTrue(policy.grantReassert(1_700))
        assertTrue(policy.consumeRecheck())
        assertFalse(policy.consumeRecheck())
    }

    @Test
    fun `re-asserts are limited so a launcher that keeps coming back cannot cause a launch loop`() {
        val policy = HomeFallbackPolicy()
        assertTrue(policy.grantReassert(1_000))
        assertTrue(policy.grantReassert(2_000))
        assertFalse(policy.grantReassert(3_000))
        assertFalse(policy.grantReassert(HomeFallbackPolicy.REASSERT_WINDOW_MS))
        // A fresh window after the quiet period gets its budget back.
        assertTrue(policy.grantReassert(1_000 + HomeFallbackPolicy.REASSERT_WINDOW_MS + 1))
    }
}
