package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvBufferPolicyTest {
    @Test fun smallPhysicalRamKeepsByteBudgetAsTheHardLimit() {
        val budget = LiveTvBufferPolicy.forDevice(512L, 1L shl 30)

        assertEquals(((512L shl 20) * 3 / 10).toInt(), budget.targetBytes)
        assertFalse(budget.prioritizeTimeOverSize)
    }

    @Test fun largerDeviceRetainsTimePriority() {
        val budget = LiveTvBufferPolicy.forDevice(512L, 3L shl 30)

        assertTrue(budget.prioritizeTimeOverSize)
    }

    @Test fun unknownHeapUsesSafeDefaultAndClampsTheBudget() {
        val budget = LiveTvBufferPolicy.forDevice(0L, 0L)

        assertEquals(((512L shl 20) * 3 / 10).toInt(), budget.targetBytes)
        assertTrue(budget.prioritizeTimeOverSize)
        assertEquals(32 * 1024 * 1024, LiveTvBufferPolicy.forDevice(16L, 3L shl 30).targetBytes)
        assertEquals(160 * 1024 * 1024, LiveTvBufferPolicy.forDevice(2048L, 3L shl 30).targetBytes)
    }
}
