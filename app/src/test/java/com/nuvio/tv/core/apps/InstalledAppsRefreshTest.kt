package com.nuvio.tv.core.apps

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppsRefreshTest {
    @Test
    fun `the first signal is answered at once`() = runTest {
        val out = flow { emit(Unit) }.refreshSafely(windowMs = 2_000, fallback = -1) { 1 }.toList()
        assertEquals(listOf(1), out)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a burst of signals after the first is answered with one query`() = runTest {
        var calls = 0
        val out = flow {
            emit(Unit)
            delay(100)
            repeat(5) { emit(Unit); delay(100) }
        }.refreshSafely(windowMs = 2_000, fallback = -1) { ++calls }.toList()
        assertEquals(listOf(1, 2), out)
    }

    @Test
    fun `a failing query keeps the last good result instead of crashing`() = runTest {
        var calls = 0
        val out = flow {
            repeat(3) { emit(Unit); delay(5_000) }
        }.refreshSafely(windowMs = 2_000, fallback = -1) {
            calls++
            if (calls == 2) throw RuntimeException("DeadSystemException") else calls
        }.toList()
        assertEquals(listOf(1, 1, 3), out)
    }

    @Test
    fun `a query that fails before any success gives the fallback`() = runTest {
        val out = flow { emit(Unit) }.refreshSafely(windowMs = 2_000, fallback = -1) { throw IllegalStateException("x") }.toList()
        assertEquals(listOf(-1), out)
    }
}
