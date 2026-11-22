package com.nuvio.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeDeviceProfileTest {
    private fun profile(
        lowRamDevice: Boolean = false,
        totalRamBytes: Long = 3L * 1024 * 1024 * 1024,
        hasHardwareAvc: Boolean = true,
        hasHardwareVp9: Boolean = true,
        hasHardwareAv1: Boolean = true,
        maxAvcHeight: Int? = 1080,
        maxVp9Height: Int? = 2160,
        maxAv1Height: Int? = 2160,
    ) = YouTubeDeviceProfile(
        lowRamDevice = lowRamDevice,
        totalRamBytes = totalRamBytes,
        hasHardwareAvc = hasHardwareAvc,
        hasHardwareVp9 = hasHardwareVp9,
        hasHardwareAv1 = hasHardwareAv1,
        maxAvcHeight = maxAvcHeight,
        maxVp9Height = maxVp9Height,
        maxAv1Height = maxAv1Height,
    )

    @Test
    fun `capable device keeps full quality`() {
        val device = profile()
        assertFalse(device.needsCompatibility)
    }

    @Test
    fun `no hardware vp9 uses compatibility`() {
        val device = profile(hasHardwareVp9 = false, maxVp9Height = null)
        assertTrue(device.needsCompatibility)
        assertEquals(1080, device.compatibilityHeight)
    }

    @Test
    fun `low memory uses compatibility`() {
        val device = profile(totalRamBytes = 3L * 1024 * 1024 * 1024 / 2)
        assertTrue(device.needsCompatibility)
    }

    @Test
    fun `very low memory starts at 720p`() {
        val device = profile(
            lowRamDevice = true,
            totalRamBytes = 1024L * 1024 * 1024,
        )
        assertTrue(device.needsCompatibility)
        assertEquals(720, device.compatibilityHeight)
    }

    @Test
    fun `missing hardware h264 starts at 720p`() {
        val device = profile(hasHardwareAvc = false)
        assertTrue(device.needsCompatibility)
        assertEquals(720, device.compatibilityHeight)
    }

    @Test
    fun `hardware vp9 limited to 720p uses compatibility`() {
        val device = profile(maxVp9Height = 720)
        assertTrue(device.needsCompatibility)
    }
}
