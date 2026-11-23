package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvAudioSyncTest {

    @Test
    fun `keeps corrections within five seconds either way`() {
        assertEquals(-5_000, LiveTvAudioSync.clamp(-20_000))
        assertEquals(5_000, LiveTvAudioSync.clamp(20_000))
        assertEquals(250, LiveTvAudioSync.clamp(250))
    }

    @Test
    fun `a single press moves fifty milliseconds`() {
        assertEquals(50, LiveTvAudioSync.stepForRepeat(0))
        assertEquals(50, LiveTvAudioSync.stepForRepeat(2))
    }

    @Test
    fun `holding the button accelerates but stays bounded`() {
        assertEquals(100, LiveTvAudioSync.stepForRepeat(3))
        assertEquals(500, LiveTvAudioSync.stepForRepeat(30))
        assertEquals(500, LiveTvAudioSync.stepForRepeat(10_000))
    }

    @Test
    fun `labels show the direction and unit`() {
        assertEquals("0 ms", LiveTvAudioSync.format(0))
        assertEquals("+250 ms", LiveTvAudioSync.format(250))
        assertEquals("-150 ms", LiveTvAudioSync.format(-150))
    }
}
