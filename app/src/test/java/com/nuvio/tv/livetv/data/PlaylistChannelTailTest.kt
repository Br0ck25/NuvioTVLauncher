package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistChannelTailTest {
    @Test fun removesEpisodeStraysAndRepairsAutoNumbersAndKeys() {
        val channels = mutableListOf<LiveChannel>()
        val keys = linkedSetOf<String>()
        val tail = PlaylistChannelTail(channels, keys, firstNumber = 1)
        append(tail, channels, keys, "One")
        append(tail, channels, keys, "Show S01E02")
        append(tail, channels, keys, "Two")

        val nextNumber = tail.removeStrays(4) { "S01E02" in it }

        assertEquals(listOf("One", "Two"), channels.map { it.name })
        assertEquals(listOf(1, 2), channels.map { it.number })
        assertEquals(listOf("p||One", "p||Two"), channels.map { it.key })
        assertEquals(3, nextNumber)
        assertEquals(channels.map { it.key }.toSet(), keys)
        assertEquals(1, tail.removed)
    }

    @Test fun rollbackDropsPartialChannelsAndTheirReservedKeys() {
        val channels = mutableListOf<LiveChannel>()
        val keys = linkedSetOf<String>()
        val tail = PlaylistChannelTail(channels, keys, firstNumber = 7)
        append(tail, channels, keys, "Partial")

        tail.rollback()

        assertTrue(channels.isEmpty())
        assertTrue(keys.isEmpty())
    }

    private fun append(
        tail: PlaylistChannelTail,
        channels: MutableList<LiveChannel>,
        keys: MutableSet<String>,
        name: String,
    ) {
        tail.unmarkedEntry()
        val key = "p||$name"
        keys += key
        channels += LiveChannel(
            key = key, sourceId = "p", sourceName = "Playlist", name = name,
            tvgId = null, tvgName = null, logo = null, groupId = "group", group = "Group",
            number = channels.size + 1, url = "https://example/$name", autoNumber = true
        )
    }
}
