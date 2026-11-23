package com.nuvio.tv.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MusicSpotifyMatchKeyTest {

    @Test
    fun `official audio and brackets do not stop a local match`() {
        assertEquals(
            MusicSpotifyWebSync.localIndexKey("Nightcall", "Kavinsky"),
            MusicSpotifyWebSync.localIndexKey("Nightcall (Official Audio)", "Kavinsky")
        )
        assertEquals(
            MusicSpotifyWebSync.localIndexKey("Nightcall", "Kavinsky"),
            MusicSpotifyWebSync.localIndexKey("Nightcall [Lyrics]", "Kavinsky")
        )
    }

    @Test
    fun `remaster and feature suffixes are ignored`() {
        assertEquals(
            MusicSpotifyWebSync.localIndexKey("Dreams", "Fleetwood Mac"),
            MusicSpotifyWebSync.localIndexKey("Dreams - Remastered 2004", "Fleetwood Mac")
        )
        assertEquals(
            MusicSpotifyWebSync.localIndexKey("Stay", "The Kid LAROI"),
            MusicSpotifyWebSync.localIndexKey("Stay feat. Justin Bieber", "The Kid LAROI")
        )
    }

    @Test
    fun `artist separators keep the lead artist`() {
        assertEquals(
            MusicSpotifyWebSync.localIndexKey("Song", "Artist"),
            MusicSpotifyWebSync.localIndexKey("Song", "Artist, Guest")
        )
        assertEquals(
            MusicSpotifyWebSync.localIndexKey("Song", "Artist"),
            MusicSpotifyWebSync.localIndexKey("Song", "Artist & Guest")
        )
    }

    @Test
    fun `punctuation and case do not matter`() {
        assertEquals(
            MusicSpotifyWebSync.localIndexKey("Don't Stop Me Now", "Queen"),
            MusicSpotifyWebSync.localIndexKey("dont stop me now", "QUEEN")
        )
    }

    @Test
    fun `different songs stay different`() {
        assertNotEquals(
            MusicSpotifyWebSync.localIndexKey("Intro", "Artist A"),
            MusicSpotifyWebSync.localIndexKey("Intro", "Artist B")
        )
        assertNotEquals(
            MusicSpotifyWebSync.localIndexKey("Song One", "Artist"),
            MusicSpotifyWebSync.localIndexKey("Song Two", "Artist")
        )
    }
}
