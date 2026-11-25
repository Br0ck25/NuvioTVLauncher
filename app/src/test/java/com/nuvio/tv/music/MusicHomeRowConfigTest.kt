package com.nuvio.tv.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicHomeRowConfigTest {

    @Test
    fun `all rows are visible in the default order`() {
        val config = MusicHomeRowConfig()
        assertEquals(MusicHomeRow.entries.toList(), config.visibleRows())
    }

    @Test
    fun `hidden rows are left out`() {
        val config = MusicHomeRowConfig(hidden = setOf(MusicHomeRow.QUICK_PICKS, MusicHomeRow.BECAUSE))
        val visible = config.visibleRows()
        assertTrue(MusicHomeRow.QUICK_PICKS !in visible)
        assertTrue(MusicHomeRow.BECAUSE !in visible)
        assertEquals(MusicHomeRow.entries.size - 2, visible.size)
    }

    @Test
    fun `pinning the library lifts playlists above everything else`() {
        val config = MusicHomeRowConfig(pinLibrary = true)
        assertEquals(MusicHomeRow.PLAYLISTS, config.visibleRows().first())
    }

    @Test
    fun `pinning a hidden library does nothing`() {
        val config = MusicHomeRowConfig(
            hidden = setOf(MusicHomeRow.PLAYLISTS),
            pinLibrary = true
        )
        assertTrue(MusicHomeRow.PLAYLISTS !in config.visibleRows())
    }

    @Test
    fun `stored order is respected and missing rows are appended`() {
        val saved = listOf(MusicHomeRow.ON_REPEAT, MusicHomeRow.QUICK_PICKS)
        val order = (saved + MusicHomeRow.entries).distinct()
        val config = MusicHomeRowConfig(order = order)
        assertEquals(MusicHomeRow.ON_REPEAT, config.visibleRows().first())
        assertEquals(MusicHomeRow.QUICK_PICKS, config.visibleRows()[1])
        assertEquals(MusicHomeRow.entries.size, config.visibleRows().size)
    }

    @Test
    fun `row ids survive a round trip`() {
        MusicHomeRow.entries.forEach { row ->
            assertEquals(row, MusicHomeRow.fromId(row.id))
        }
    }
}
