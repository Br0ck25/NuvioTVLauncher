package com.nuvio.tv.music

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MusicLibraryLikedDedupeTest {

    @Test
    fun `a recording is liked once even when another device matched a different video`() {
        val first = MusicLibrary.mergeLikedSongs(
            emptyList(),
            listOf(song("video-a", "Nightcall", "Kavinsky"))
        )
        assertEquals(1, first.size)

        // A sync on another device could have matched the same Spotify track to another upload.
        val duplicate = MusicLibrary.mergeLikedSongs(
            first,
            listOf(song("video-b", "Nightcall (Official Audio)", "Kavinsky"))
        )
        assertSame(first, duplicate)

        // The same position in another playlist must not add it again either.
        val sameAgain = MusicLibrary.mergeLikedSongs(
            first,
            listOf(song("video-c", "Nightcall", "Kavinsky"))
        )
        assertSame(first, sameAgain)

        // Versions are different recordings and stay separate.
        val withLive = MusicLibrary.mergeLikedSongs(
            first,
            listOf(song("video-d", "Nightcall (Live)", "Kavinsky"))
        )
        assertEquals(2, withLive.size)
    }

    private fun song(videoId: String, title: String, artist: String) = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = null,
    )
}
