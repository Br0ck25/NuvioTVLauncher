package com.music.bitchord.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Song/Video switch reads YouTube's segment map, so its arithmetic is worth pinning down. */
class VersionTimeMapTest {

    // Video intro runs 0-10s, then both cuts share 60s starting at 10s / 0s.
    private val map = VersionTimeMap(
        listOf(
            VersionTimeMap.Segment(fromStartMs = 10_000L, toStartMs = 0L, durationMs = 60_000L)
        )
    )

    @Test
    fun `inside a shared stretch keeps the offset`() {
        assertEquals(15_000L, map.map(25_000L))
    }

    @Test
    fun `before the first stretch lands where the music starts`() {
        assertEquals(0L, map.map(3_000L))
    }

    @Test
    fun `past the last stretch stops at its end`() {
        assertEquals(60_000L, map.map(90_000L))
    }

    @Test
    fun `a gap between stretches lands on the next one`() {
        val gapped = VersionTimeMap(
            listOf(
                VersionTimeMap.Segment(fromStartMs = 0L, toStartMs = 5_000L, durationMs = 10_000L),
                VersionTimeMap.Segment(fromStartMs = 20_000L, toStartMs = 30_000L, durationMs = 10_000L)
            )
        )
        assertEquals(30_000L, gapped.map(15_000L))
    }

    @Test
    fun `inverted maps the other direction`() {
        val back = map.inverted()
        assertEquals(20_000L, back.map(10_000L))
        assertEquals(10_000L, back.map(-1L))
    }
}
