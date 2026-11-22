package com.nuvio.tv.livetv.home

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherRowsProfileTest {
    @Test
    fun `low ram devices get the light profile automatically`() {
        val profile = LauncherRowsProfile(lowRam = true)
        assertEquals(12, profile.maxItems)
        assertEquals(Bitmap.Config.RGB_565, profile.cardConfig)
        assertEquals(15 * 60_000L, profile.minRefreshMs)
        assertEquals(30_000L, profile.startupDelayMs)
    }

    @Test
    fun `higher ram devices keep the full launcher profile`() {
        val profile = LauncherRowsProfile(lowRam = false)
        assertEquals(24, profile.maxItems)
        assertEquals(Bitmap.Config.ARGB_8888, profile.cardConfig)
        assertEquals(2 * 60_000L, profile.minRefreshMs)
        assertEquals(6_000L, profile.startupDelayMs)
    }
}
