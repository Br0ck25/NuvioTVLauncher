package com.nuvio.tv.core.launcher

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeButtonRegressionTest {

    @Test
    fun `factory default Home mapping has Single GoToTopOfHome Double OpenProfileSwitcher Long None`() {
        val defaults = ButtonMapping.defaultHomeMapping()
        assertEquals(KeyEvent.KEYCODE_HOME, defaults.keyCode)
        assertEquals(ButtonAction.GoToTopOfHome, defaults.singleAction)
        assertEquals(ButtonAction.OpenProfileSwitcher, defaults.doubleAction)
        assertEquals(ButtonAction.None, defaults.longAction)
        assertTrue(defaults.enabled)
        assertTrue(defaults.matchesFactoryHomeDefaults())
    }

    @Test
    fun `first Home press immediately executes GoToTopOfHome without waiting for double-click window`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )
        val homeMapping = ButtonMapping.defaultHomeMapping()

        scheduler.advanceTimeTo(1_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)
        scheduler.advanceTimeTo(1_050L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_050L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)

        // Immediate execution at t = 1050L (0ms wait after release!)
        assertEquals(1, resolved.size)
        assertEquals(ButtonPressType.SINGLE_PRESS, resolved.single().pressType)
        assertEquals(ButtonAction.GoToTopOfHome, resolved.single().action)
    }

    @Test
    fun `second Home press within double-click window executes OpenProfileSwitcher`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )
        val homeMapping = ButtonMapping.defaultHomeMapping()

        // First press at 1000..1040 -> immediate GoToTopOfHome
        scheduler.advanceTimeTo(1_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)
        scheduler.advanceTimeTo(1_040L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_040L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)

        // Second press at 1280..1320 (280ms after first release, within 450ms window) -> OpenProfileSwitcher
        scheduler.advanceTimeTo(1_280L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_280L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)
        scheduler.advanceTimeTo(1_320L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_320L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)

        assertEquals(2, resolved.size)
        assertEquals(ButtonAction.GoToTopOfHome, resolved[0].action)
        assertEquals(ButtonPressType.SINGLE_PRESS, resolved[0].pressType)
        assertEquals(ButtonAction.OpenProfileSwitcher, resolved[1].action)
        assertEquals(ButtonPressType.DOUBLE_PRESS, resolved[1].pressType)
    }

    @Test
    fun `two separate Home presses outside double-click window both execute GoToTopOfHome`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )
        val homeMapping = ButtonMapping.defaultHomeMapping()

        // First press at 1000..1040
        scheduler.advanceTimeTo(1_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)
        scheduler.advanceTimeTo(1_040L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_040L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)

        // Second press at 1700..1740 (700ms > 450ms window)
        scheduler.advanceTimeTo(1_700L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_700L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)
        scheduler.advanceTimeTo(1_740L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_740L, source = ButtonEventSource.FOREGROUND, mapping = homeMapping)

        assertEquals(2, resolved.size)
        assertEquals(listOf(ButtonAction.GoToTopOfHome, ButtonAction.GoToTopOfHome), resolved.map { it.action })
        assertEquals(listOf(ButtonPressType.SINGLE_PRESS, ButtonPressType.SINGLE_PRESS), resolved.map { it.pressType })
    }

    @Test
    fun `Home long press executes assigned long action and suppresses single press on release`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )
        val customHome = ButtonMapping.defaultHomeMapping().copy(
            longAction = ButtonAction.OpenSettings
        )

        scheduler.advanceTimeTo(2_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 2_000L, source = ButtonEventSource.FOREGROUND, mapping = customHome)

        // Hold for 600ms
        scheduler.advanceTimeTo(2_600L)
        assertEquals(1, resolved.size)
        assertEquals(ButtonPressType.LONG_PRESS, resolved.single().pressType)
        assertEquals(ButtonAction.OpenSettings, resolved.single().action)

        // Release at 2700ms does not trigger GoToTopOfHome
        scheduler.advanceTimeTo(2_700L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 2_700L, source = ButtonEventSource.FOREGROUND, mapping = customHome)
        assertEquals(1, resolved.size)
    }

    @Test
    fun `Home mapping disabled does not intercept or fire mapped actions`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )
        val disabledHome = ButtonMapping.defaultHomeMapping().copy(enabled = false)

        assertFalse(
            engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = disabledHome)
        )
        assertFalse(
            engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_050L, source = ButtonEventSource.FOREGROUND, mapping = disabledHome)
        )
        assertEquals(HomeKeyPress.IGNORED, engine.onHomePress(1_050L, disabledHome))
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `Home single action and double action can be customized independently`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )
        val customizedHome = ButtonMapping.defaultHomeMapping().copy(
            singleAction = ButtonAction.OpenSearch,
            doubleAction = ButtonAction.OpenApps
        )

        // First press -> OpenSearch
        scheduler.advanceTimeTo(1_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = customizedHome)
        scheduler.advanceTimeTo(1_040L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_040L, source = ButtonEventSource.FOREGROUND, mapping = customizedHome)

        // Second press within window -> OpenApps
        scheduler.advanceTimeTo(1_300L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_300L, source = ButtonEventSource.FOREGROUND, mapping = customizedHome)
        scheduler.advanceTimeTo(1_340L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_340L, source = ButtonEventSource.FOREGROUND, mapping = customizedHome)

        assertEquals(listOf(ButtonAction.OpenSearch, ButtonAction.OpenApps), resolved.map { it.action })
    }

    @Test
    fun `Home assignments cleared fires no actions`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )
        val clearedHome = ButtonMapping.defaultHomeMapping().copy(
            singleAction = ButtonAction.None,
            doubleAction = ButtonAction.None,
            longAction = ButtonAction.None
        )

        scheduler.advanceTimeTo(1_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = clearedHome)
        scheduler.advanceTimeTo(1_040L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_040L, source = ButtonEventSource.FOREGROUND, mapping = clearedHome)

        scheduler.advanceTimeTo(1_300L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, eventTimeMs = 1_300L, source = ButtonEventSource.FOREGROUND, mapping = clearedHome)
        scheduler.advanceTimeTo(1_340L)
        engine.onKeyEvent(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, eventTimeMs = 1_340L, source = ButtonEventSource.FOREGROUND, mapping = clearedHome)

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `primed first Home press from cold CATEGORY_HOME launch recognizes second press as double click`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )
        val homeMapping = ButtonMapping.defaultHomeMapping()

        engine.primeFirstHomePress(1_000L)
        val press = engine.onHomePress(
            pressedAtMs = 1_300L,
            mapping = homeMapping
        )

        assertEquals(HomeKeyPress.DOUBLE, press)
        assertEquals(1, resolved.size)
        assertEquals(ButtonAction.OpenProfileSwitcher, resolved.single().action)
    }
}
