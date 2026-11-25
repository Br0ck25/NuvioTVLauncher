package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferMemoryPolicyTest {
    private val mb = 1024L * 1024L

    @Test
    fun automaticTargetUsesCurrentFreeMemoryAndKeepsConfiguredCeiling() {
        assertEquals(
            402L * mb,
            PlaybackBufferMemoryPolicy.sizedTargetBytes(
                ceilingBytes = 500L * mb,
                availableBytes = 800L * mb,
                thresholdBytes = 200L * mb,
                heldBytes = 20L * mb,
                outsideBudgetBytes = 50L * mb,
                videoReserveBytes = 140L * mb,
                floorBytes = 50L * mb,
                lowMemory = false,
            ),
        )
    }

    @Test
    fun lowMemorySizingUsesFloorAndHealthyLargeHeadroomUsesCeiling() {
        val inputs = mapOf(
            "ceilingBytes" to 500L * mb,
            "availableBytes" to 900L * mb,
            "thresholdBytes" to 100L * mb,
            "heldBytes" to 0L,
            "outsideBudgetBytes" to 0L,
            "videoReserveBytes" to 140L * mb,
            "floorBytes" to 50L * mb,
        )
        assertEquals(
            50L * mb,
            PlaybackBufferMemoryPolicy.sizedTargetBytes(
                ceilingBytes = inputs.getValue("ceilingBytes"),
                availableBytes = inputs.getValue("availableBytes"),
                thresholdBytes = inputs.getValue("thresholdBytes"),
                heldBytes = inputs.getValue("heldBytes"),
                outsideBudgetBytes = inputs.getValue("outsideBudgetBytes"),
                videoReserveBytes = inputs.getValue("videoReserveBytes"),
                floorBytes = inputs.getValue("floorBytes"),
                lowMemory = true,
            ),
        )
        assertEquals(
            500L * mb,
            PlaybackBufferMemoryPolicy.sizedTargetBytes(
                ceilingBytes = inputs.getValue("ceilingBytes"),
                availableBytes = inputs.getValue("availableBytes"),
                thresholdBytes = inputs.getValue("thresholdBytes"),
                heldBytes = inputs.getValue("heldBytes"),
                outsideBudgetBytes = inputs.getValue("outsideBudgetBytes"),
                videoReserveBytes = inputs.getValue("videoReserveBytes"),
                floorBytes = inputs.getValue("floorBytes"),
                lowMemory = false,
            ),
        )
    }

    @Test
    fun thirtyTwoBitAddressSpaceCapLeavesSafetySpareAndAddsHeldBufferBack() {
        assertEquals(
            576L * mb,
            PlaybackBufferMemoryPolicy.addressSpaceCapBytes(
                is64Bit = false,
                usedAddressSpaceMb = 1_800L,
                addressSpaceLimitMb = 3_072L,
                heldBytes = 100L * mb,
                ceilingBytes = 1_000L * mb,
                floorBytes = 50L * mb,
            ),
        )
        assertEquals(
            null,
            PlaybackBufferMemoryPolicy.addressSpaceCapBytes(
                is64Bit = true,
                usedAddressSpaceMb = 1_800L,
                addressSpaceLimitMb = 3_072L,
                heldBytes = 100L * mb,
                ceilingBytes = 1_000L * mb,
                floorBytes = 50L * mb,
            ),
        )
    }

    @Test
    fun warningCutsQuarterOfAvailableBufferButKeepsFloor() {
        assertEquals(163L, PlaybackBufferMemoryPolicy.stepDown(200L, 50L, critical = false))
        assertEquals(58L, PlaybackBufferMemoryPolicy.stepDown(60L, 50L, critical = false))
    }

    @Test
    fun criticalPressureCutsMoreAndNeverCrossesFloor() {
        assertEquals(125L, PlaybackBufferMemoryPolicy.stepDown(200L, 50L, critical = true))
        assertEquals(65L, PlaybackBufferMemoryPolicy.stepDown(80L, 50L, critical = true))
    }

    @Test
    fun recoveryGrowsInStepsAndSignalsWhenCeilingIsRestored() {
        assertEquals(150L, PlaybackBufferMemoryPolicy.stepUp(100L, 200L, 50L))
        assertEquals(-1L, PlaybackBufferMemoryPolicy.stepUp(150L, 200L, 50L))
        assertEquals(-1L, PlaybackBufferMemoryPolicy.stepUp(200L, 200L, 50L))
    }
}
