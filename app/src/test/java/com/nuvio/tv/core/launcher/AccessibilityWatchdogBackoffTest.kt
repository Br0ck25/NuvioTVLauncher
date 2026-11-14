package com.nuvio.tv.core.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityWatchdogBackoffTest {
    @Test
    fun `the first write is immediate`() {
        assertEquals(0L, accessibilityWriteBackoffMs(0))
    }

    @Test
    fun `writes that do not stick are spaced out further each time`() {
        val waits = (1..6).map { accessibilityWriteBackoffMs(it) }
        assertEquals(listOf(2_000L, 10_000L, 60_000L, 300_000L, 300_000L, 300_000L), waits)
        assertTrue(waits.zipWithNext().all { (a, b) -> b >= a })
    }
}
