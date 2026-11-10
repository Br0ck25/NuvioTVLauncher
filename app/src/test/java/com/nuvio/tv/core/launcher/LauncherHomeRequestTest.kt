package com.nuvio.tv.core.launcher

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherHomeRequestTest {
    private val gesture = HomeKeyGesture()

    @Test
    fun `native HOME intents reset Home then request the switcher on double tap`() {
        val intent = nativeHomeIntent()
        assertEquals(HomeKeyPress.SINGLE, LauncherHomeRequest.onIntent(intent, gesture, 1_000))
        assertEquals(HomeKeyPress.DOUBLE, LauncherHomeRequest.onIntent(intent, gesture, 1_250))
    }

    @Test
    fun `cold native HOME launch preserves startup but counts as the first press`() {
        val intent = nativeHomeIntent()
        assertEquals(HomeKeyPress.IGNORED, LauncherHomeRequest.onIntent(intent, gesture, 1_000, fromNewIntent = false))
        assertEquals(HomeKeyPress.DOUBLE, LauncherHomeRequest.onIntent(intent, gesture, 1_250))
    }

    @Test
    fun `cold accessibility launch acts on the explicit Home request`() {
        val intent = accessibilityHomeIntent(1_000)
        assertEquals(HomeKeyPress.SINGLE, LauncherHomeRequest.onIntent(intent, gesture, 1_050, fromNewIntent = false))
    }

    @Test
    fun `accessibility presses remain a double tap despite delayed Activity delivery`() {
        assertEquals(HomeKeyPress.SINGLE, LauncherHomeRequest.onIntent(accessibilityHomeIntent(1_000), gesture, 1_050))
        assertEquals(HomeKeyPress.DOUBLE, LauncherHomeRequest.onIntent(accessibilityHomeIntent(1_250), gesture, 2_000))
    }

    @Test
    fun `first press from another app and second while foregrounded use one detector`() {
        assertEquals(HomeKeyPress.SINGLE, LauncherHomeRequest.onIntent(accessibilityHomeIntent(1_000), gesture, 1_050))
        assertEquals(HomeKeyPress.DOUBLE, LauncherHomeRequest.onIntent(accessibilityHomeIntent(1_250), gesture, 1_300))
    }

    @Test
    fun `raw HOME and native intent duplicates do not trigger a false switcher`() {
        assertEquals(HomeKeyPress.SINGLE, gesture.onPress(1_000))
        assertEquals(HomeKeyPress.IGNORED, LauncherHomeRequest.onIntent(nativeHomeIntent(), gesture, 1_050))
        assertEquals(HomeKeyPress.DOUBLE, gesture.onPress(1_150))
    }

    @Test
    fun `ordinary app launches do not count as Home presses`() {
        val iconIntent = nativeHomeIntent().also {
            every { it.hasCategory(Intent.CATEGORY_HOME) } returns false
        }
        assertEquals(HomeKeyPress.IGNORED, LauncherHomeRequest.onIntent(iconIntent, gesture, 1_000))
        assertEquals(HomeKeyPress.SINGLE, LauncherHomeRequest.onIntent(nativeHomeIntent(), gesture, 1_250))
    }

    @Test
    fun `null intents are ignored`() {
        assertEquals(HomeKeyPress.IGNORED, LauncherHomeRequest.onIntent(null, gesture, 1_000))
    }

    @Test
    fun `invalid forwarded timestamps fall back to current uptime`() {
        assertEquals(2_000L, LauncherHomeRequest.pressUptimeMs(accessibilityHomeIntent(-1), 2_000))
        assertEquals(2_000L, LauncherHomeRequest.pressUptimeMs(accessibilityHomeIntent(3_000), 2_000))
    }

    private fun nativeHomeIntent(): Intent = mockk<Intent>().also { intent ->
        every { intent.getBooleanExtra(LauncherHomeRequest.EXTRA_GO_HOME, false) } returns false
        every { intent.action } returns Intent.ACTION_MAIN
        every { intent.hasCategory(Intent.CATEGORY_HOME) } returns true
        every { intent.getLongExtra(LauncherHomeRequest.EXTRA_HOME_PRESS_UPTIME_MS, any()) } answers { secondArg<Long>() }
    }

    private fun accessibilityHomeIntent(pressedAtMs: Long): Intent = mockk<Intent>().also { intent ->
        every { intent.getBooleanExtra(LauncherHomeRequest.EXTRA_GO_HOME, false) } returns true
        every { intent.getLongExtra(LauncherHomeRequest.EXTRA_HOME_PRESS_UPTIME_MS, any()) } returns pressedAtMs
    }
}
