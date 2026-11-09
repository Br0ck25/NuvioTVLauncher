package com.nuvio.tv.reshaped.livetv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvAddonsTest {
    @Test
    fun `leading emoji are dropped from addon names`() {
        assertEquals("ABC", LiveTvAddons.cleanLabel("📺 ABC"))
        assertEquals("Live Now", LiveTvAddons.cleanLabel("🔴 Live Now"))
        assertEquals("NUVIO LIVE SPORTS", LiveTvAddons.cleanLabel("🏆 NUVIO LIVE SPORTS"))
        assertEquals("LIVE: Chargers vs Texans", LiveTvAddons.cleanLabel("🔴 LIVE: Chargers vs Texans"))
    }

    @Test
    fun `names without leading symbols are unchanged`() {
        assertEquals("ESPN", LiveTvAddons.cleanLabel("ESPN"))
        assertEquals("#1 Sports", LiveTvAddons.cleanLabel("#1 Sports"))
        assertEquals("Disney+ Kids", LiveTvAddons.cleanLabel("  Disney+ Kids "))
    }

    @Test
    fun `a name that is only an emoji keeps its text`() {
        assertEquals("📺", LiveTvAddons.cleanLabel("📺"))
    }
}
