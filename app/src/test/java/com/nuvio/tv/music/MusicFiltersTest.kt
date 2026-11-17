package com.nuvio.tv.music

import com.music.bitchord.data.model.Song
import com.nuvio.tv.music.MusicFilters.isUsable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFiltersTest {
    private fun song(
        title: String,
        artist: String = "Artist",
        duration: String? = "3:30",
        videoId: String = "v1"
    ) = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration
    )

    @Test
    fun `variants and talk are filtered out`() {
        assertTrue(MusicFilters.isVariant("Song (Karaoke Version)"))
        assertTrue(MusicFilters.isVariant("Song slowed + reverb"))
        assertTrue(MusicFilters.isVariant("Song cover"))
        assertFalse(MusicFilters.isVariant("Song"))
        assertFalse(MusicFilters.isVariant("Discovery"))

        assertTrue(MusicFilters.looksLikeTalk("Song interview"))
        assertTrue(MusicFilters.looksLikeTalk("Album review"))
        assertFalse(MusicFilters.looksLikeTalk("Song"))
        assertFalse(MusicFilters.looksLikeTalk("Preview"))
    }

    @Test
    fun `same song ignores official video decoration`() {
        val plain = song("Get Lucky")
        val official = song("Get Lucky (Official Video)")
        assertTrue(MusicFilters.sameSong(plain, official))

        assertFalse(
            MusicFilters.sameSong(
                song("Get Lucky"),
                song("Lose Yourself")
            )
        )
    }

    @Test
    fun `artist names drop channel suffixes and keep unicode`() {
        assertEquals("daft punk", MusicFilters.normalizeArtistName("Daft Punk - Topic"))
        assertEquals("daft punk", MusicFilters.normalizeArtistName("Daft Punk VEVO"))
        assertEquals("daft punk", MusicFilters.normalizeArtistName("Daft Punk Official Video"))
        assertEquals("моцарт", MusicFilters.normalizeArtistName("Моцарт"))
        assertEquals("", MusicFilters.normalizeArtistName("Various Artists"))
        assertEquals("Daft Punk", MusicFilters.cleanArtistName("Daft Punk - Topic"))
    }

    @Test
    fun `artist byline splits collaborations`() {
        assertEquals(
            listOf("A", "B", "C", "D"),
            MusicFilters.splitArtistNames("A, B & C feat. D")
        )
    }

    @Test
    fun `usable needs a youtube track and a sensible duration`() {
        assertTrue(song("Song").isUsable())
        assertTrue(song("Song", duration = null).isUsable())
        assertFalse(song("Song", duration = "0:30").isUsable())
        assertFalse(song("Song", duration = "20:00").isUsable())
        assertFalse(
            Song(
                videoId = "local:1",
                title = "Song",
                artist = "Artist",
                thumbnailUrl = null,
                localUri = "file:///song.flac"
            ).isUsable()
        )
    }

    @Test
    fun `clean title removes decorations and artist prefix`() {
        assertEquals(
            "Get Lucky",
            MusicFilters.cleanSongTitle("Daft Punk - Get Lucky (Official Video)", "Daft Punk")
        )
        assertEquals("Song", MusicFilters.cleanSongTitle("Song [Lyrics]", null))
    }
}
