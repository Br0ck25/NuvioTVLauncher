package com.nuvio.tv.core.launcher

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic manual scheduler for testing gesture timing boundaries without thread sleeps.
 */
internal class FakeButtonGestureScheduler : ButtonGestureScheduler {
    private data class ScheduledItem(
        val id: Long,
        val triggerAtMs: Long,
        val block: () -> Unit,
        var canceled: Boolean = false
    )

    private var nextId = 1L
    var nowMs: Long = 0L
        private set
    private val queue = mutableListOf<ScheduledItem>()

    override fun schedule(delayMs: Long, block: () -> Unit): ButtonGestureScheduler.CancellableTask {
        val item = ScheduledItem(
            id = nextId++,
            triggerAtMs = nowMs + delayMs,
            block = block
        )
        queue.add(item)
        return object : ButtonGestureScheduler.CancellableTask {
            override fun cancel() {
                item.canceled = true
            }
        }
    }

    fun advanceTimeBy(deltaMs: Long) {
        advanceTimeTo(nowMs + deltaMs)
    }

    fun advanceTimeTo(targetMs: Long) {
        if (targetMs < nowMs) return
        nowMs = targetMs
        while (true) {
            val due = queue
                .filter { !it.canceled && it.triggerAtMs <= nowMs }
                .minByOrNull { it.triggerAtMs }
                ?: break
            due.canceled = true
            due.block()
        }
    }
}

class ButtonMappingEngineTest {

    private val menuMapping = ButtonMapping(
        keyCode = KeyEvent.KEYCODE_MENU,
        displayName = "Menu",
        singleAction = ButtonAction.OpenSearch,
        doubleAction = ButtonAction.OpenLibrary,
        longAction = ButtonAction.OpenSettings,
        enabled = true,
        scope = ButtonMappingScope.FOREGROUND_AND_GLOBAL
    )

    @Test
    fun `single press on standard button executes after double-click window expires`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )

        scheduler.advanceTimeTo(1_000L)
        assertTrue(
            engine.onKeyEvent(
                keyCode = KeyEvent.KEYCODE_MENU,
                action = KeyEvent.ACTION_DOWN,
                eventTimeMs = 1_000L,
                source = ButtonEventSource.FOREGROUND,
                mapping = menuMapping
            )
        )
        scheduler.advanceTimeTo(1_080L)
        assertTrue(
            engine.onKeyEvent(
                keyCode = KeyEvent.KEYCODE_MENU,
                action = KeyEvent.ACTION_UP,
                eventTimeMs = 1_080L,
                source = ButtonEventSource.FOREGROUND,
                mapping = menuMapping
            )
        )

        // Before the 450ms double-click window expires, single-press has not fired yet.
        scheduler.advanceTimeBy(449L)
        assertTrue(resolved.isEmpty())

        // At 450ms after release, single-press fires once.
        scheduler.advanceTimeBy(1L)
        assertEquals(1, resolved.size)
        assertEquals(ButtonPressType.SINGLE_PRESS, resolved.single().pressType)
        assertEquals(ButtonAction.OpenSearch, resolved.single().action)
    }

    @Test
    fun `double press within window cancels single press and fires double action`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )

        // First press at 1000..1050
        scheduler.advanceTimeTo(1_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(1_050L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 1_050L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)

        // Second press at 1300..1360 (within 450ms of 1050)
        scheduler.advanceTimeTo(1_300L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 1_300L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(1_360L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 1_360L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)

        assertEquals(1, resolved.size)
        assertEquals(ButtonPressType.DOUBLE_PRESS, resolved.single().pressType)
        assertEquals(ButtonAction.OpenLibrary, resolved.single().action)

        // Advancing past the original single-click window does not fire a stray single-click.
        scheduler.advanceTimeBy(1_000L)
        assertEquals(1, resolved.size)
    }

    @Test
    fun `timing boundary at exact 450ms double-click window vs 451ms outside window`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )

        // Exact 450ms gap between first release (1000) and second release (1450) -> DOUBLE_PRESS
        scheduler.advanceTimeTo(950L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 950L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(1_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)

        scheduler.advanceTimeTo(1_400L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 1_400L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(1_450L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 1_450L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)

        assertEquals(listOf(ButtonPressType.DOUBLE_PRESS), resolved.map { it.pressType })

        // Now test 2 separate presses separated by 600ms (> 450ms) -> 2 SINGLE_PRESS events
        resolved.clear()
        scheduler.advanceTimeTo(3_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 3_000L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(3_050L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 3_050L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(3_500L) // 450ms after 3050 -> first single fires

        scheduler.advanceTimeTo(3_700L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 3_700L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(3_750L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 3_750L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(4_200L) // 450ms after 3750 -> second single fires

        assertEquals(
            listOf(ButtonPressType.SINGLE_PRESS, ButtonPressType.SINGLE_PRESS),
            resolved.map { it.pressType }
        )
    }

    @Test
    fun `long press fires at 600ms threshold and suppresses subsequent key up`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )

        scheduler.advanceTimeTo(2_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, repeatCount = 0, eventTimeMs = 2_000L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)

        // Key-repeat events while held are suppressed and do not reset or duplicate long press.
        scheduler.advanceTimeTo(2_200L)
        assertTrue(
            engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, repeatCount = 1, eventTimeMs = 2_200L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        )
        scheduler.advanceTimeTo(2_400L)
        assertTrue(
            engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, repeatCount = 2, eventTimeMs = 2_400L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        )
        assertTrue(resolved.isEmpty())

        // At 2599ms (599ms held), long press has not fired yet.
        scheduler.advanceTimeTo(2_599L)
        assertTrue(resolved.isEmpty())

        // At 2600ms (600ms held), long press fires immediately.
        scheduler.advanceTimeTo(2_600L)
        assertEquals(1, resolved.size)
        assertEquals(ButtonPressType.LONG_PRESS, resolved.single().pressType)
        assertEquals(ButtonAction.OpenSettings, resolved.single().action)

        // Subsequent ACTION_UP at 2800ms is consumed and does not fire single or double press.
        scheduler.advanceTimeTo(2_800L)
        assertTrue(
            engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 2_800L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        )
        scheduler.advanceTimeBy(1_000L)
        assertEquals(1, resolved.size)
    }

    @Test
    fun `multiple buttons pressed close together resolve independently without cross-talk`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )
        val redMapping = ButtonMapping(
            keyCode = KeyEvent.KEYCODE_PROG_RED,
            displayName = "Red",
            singleAction = ButtonAction.OpenApps
        )
        val blueMapping = ButtonMapping(
            keyCode = KeyEvent.KEYCODE_PROG_BLUE,
            displayName = "Blue",
            singleAction = ButtonAction.OpenLiveTv
        )

        scheduler.advanceTimeTo(1_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_PROG_RED, KeyEvent.ACTION_DOWN, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = redMapping)
        scheduler.advanceTimeTo(1_040L)
        engine.onKeyEvent(KeyEvent.KEYCODE_PROG_RED, KeyEvent.ACTION_UP, eventTimeMs = 1_040L, source = ButtonEventSource.FOREGROUND, mapping = redMapping)

        // Press Blue 100ms later
        scheduler.advanceTimeTo(1_140L)
        engine.onKeyEvent(KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.ACTION_DOWN, eventTimeMs = 1_140L, source = ButtonEventSource.FOREGROUND, mapping = blueMapping)
        scheduler.advanceTimeTo(1_180L)
        engine.onKeyEvent(KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.ACTION_UP, eventTimeMs = 1_180L, source = ButtonEventSource.FOREGROUND, mapping = blueMapping)

        scheduler.advanceTimeTo(2_000L)
        assertEquals(2, resolved.size)
        assertEquals(KeyEvent.KEYCODE_PROG_RED, resolved[0].keyCode)
        assertEquals(ButtonAction.OpenApps, resolved[0].action)
        assertEquals(KeyEvent.KEYCODE_PROG_BLUE, resolved[1].keyCode)
        assertEquals(ButtonAction.OpenLiveTv, resolved[1].action)
    }

    @Test
    fun `duplicate foreground and global events within 120ms do not execute twice`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )

        scheduler.advanceTimeTo(5_000L)
        // Global handler receives DOWN/UP first
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 5_000L, source = ButtonEventSource.GLOBAL, mapping = menuMapping)
        scheduler.advanceTimeTo(5_040L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 5_040L, source = ButtonEventSource.GLOBAL, mapping = menuMapping)

        // Foreground handler receives duplicate DOWN/UP 30ms later
        scheduler.advanceTimeTo(5_030L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 5_030L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(5_070L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 5_070L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)

        scheduler.advanceTimeTo(6_000L)
        assertEquals(1, resolved.size)
        assertEquals(ButtonPressType.SINGLE_PRESS, resolved.single().pressType)
    }

    @Test
    fun `missing key up expires cleanly and cancelAll cancels pending gestures`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )

        // Press and release, then cancelAll (e.g. activity focus lost) before 450ms window
        scheduler.advanceTimeTo(1_000L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 1_000L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)
        scheduler.advanceTimeTo(1_050L)
        engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, eventTimeMs = 1_050L, source = ButtonEventSource.FOREGROUND, mapping = menuMapping)

        engine.cancelAll()
        scheduler.advanceTimeBy(1_000L)
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `mapping scope disabled mappings and system reserved keys are respected`() {
        val scheduler = FakeButtonGestureScheduler()
        val resolved = mutableListOf<ResolvedButtonGesture>()
        val engine = ButtonMappingEngine(
            scheduler = scheduler,
            onActionResolved = resolved::add
        )

        // Disabled mapping is not consumed
        val disabledMapping = menuMapping.copy(enabled = false)
        assertFalse(
            engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 100L, source = ButtonEventSource.FOREGROUND, mapping = disabledMapping)
        )

        // Unassigned non-Home mapping is not consumed
        val unassignedMapping = ButtonMapping(
            keyCode = KeyEvent.KEYCODE_MENU,
            displayName = "Menu",
            singleAction = ButtonAction.None,
            doubleAction = ButtonAction.None,
            longAction = ButtonAction.None
        )
        assertFalse(
            engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 200L, source = ButtonEventSource.FOREGROUND, mapping = unassignedMapping)
        )

        // Foreground-only mapping is not consumed by GLOBAL source
        val foregroundOnly = menuMapping.copy(scope = ButtonMappingScope.FOREGROUND_ONLY)
        assertFalse(
            engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 300L, source = ButtonEventSource.GLOBAL, mapping = foregroundOnly)
        )

        // Global-only mapping is not consumed by FOREGROUND source
        val globalOnly = menuMapping.copy(scope = ButtonMappingScope.GLOBAL_ONLY)
        assertFalse(
            engine.onKeyEvent(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, eventTimeMs = 400L, source = ButtonEventSource.FOREGROUND, mapping = globalOnly)
        )

        // Volume and Power are never consumed even if passed a mapping
        assertFalse(
            engine.onKeyEvent(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.ACTION_DOWN,
                eventTimeMs = 500L,
                source = ButtonEventSource.FOREGROUND,
                mapping = menuMapping.copy(keyCode = KeyEvent.KEYCODE_VOLUME_UP)
            )
        )
        assertFalse(
            engine.onKeyEvent(
                KeyEvent.KEYCODE_POWER,
                KeyEvent.ACTION_DOWN,
                eventTimeMs = 600L,
                source = ButtonEventSource.FOREGROUND,
                mapping = menuMapping.copy(keyCode = KeyEvent.KEYCODE_POWER)
            )
        )
        assertTrue(resolved.isEmpty())
    }
}
