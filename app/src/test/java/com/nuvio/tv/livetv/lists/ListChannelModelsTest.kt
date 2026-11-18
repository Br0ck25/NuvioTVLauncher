package com.nuvio.tv.livetv.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListChannelModelsTest {
    @Test
    fun aChannelLinkRoundTripsAndIsRecognised() {
        val url = listChannelUrl("abc-123")
        assertTrue(url.startsWith(LIST_CHANNEL_SCHEME))
        assertTrue(url.isListChannelUrl())
        assertEquals("abc-123", listChannelIdOf(url))
        assertEquals("list:abc-123", listChannelGuideKey("abc-123"))
    }

    @Test
    fun ordinaryLinksAreNotListChannels() {
        assertFalse("https://example.com/live/1.m3u8".isListChannelUrl())
        assertFalse("torrent://abcdef".isListChannelUrl())
        assertFalse((null as String?).isListChannelUrl())
    }

    @Test
    fun listIdentityDistinguishesWhatItPointsAt() {
        val a = ListRef.Catalog("addon", "movie", "top", "Top")
        val b = ListRef.Catalog("addon", "series", "top", "Top")
        val withGenre = ListRef.Catalog("addon", "movie", "top", "Top", genre = "Drama")
        assertNotEquals(a.key, b.key)
        assertNotEquals(a.key, withGenre.key)
        // A title change must not change which list it is, or an edited channel would lose its lists.
        assertEquals(a.key, a.copy(title = "Renamed").key)
        assertEquals("f|c1|f2", ListRef.Folder("c1", "f2", "x").key)
        assertEquals("l|watchlist", ListRef.Library("watchlist", "Watchlist").key)
    }

    @Test
    fun aSlotKnowsHowLongItRuns() {
        val slot = ListSlot("tt1", "movie", "tt1", null, null, "Film", null, null, 1_000L, 6_001_000L)
        assertEquals(6_000_000L, slot.durationMs)
    }

    @Test
    fun listChannelsSitWellAbovePlaylistNumbers() {
        assertTrue(LIST_CHANNEL_NUMBER_BASE >= 9000)
    }
}
