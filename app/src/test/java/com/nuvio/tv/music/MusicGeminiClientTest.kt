package com.nuvio.tv.music

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MusicGeminiClientTest {

    @Test
    fun `reads tracks and albums in order`() {
        val picks = MusicGeminiClient.parseSuggestions(
            """
            {"items":[
              {"type":"track","title":"Nightcall","artist":"Kavinsky","year":2010,"reason":"synthwave"},
              {"type":"album","title":"Random Access Memories","artist":"Daft Punk","year":2013,"reason":"classic"}
            ]}
            """.trimIndent()
        )

        assertEquals(2, picks.size)
        assertEquals("nightcall", picks[0].title.lowercase())
        assertEquals("track", picks[0].type)
        assertEquals(2010, picks[0].year)
        assertEquals("album", picks[1].type)
        assertEquals("classic", picks[1].reason)
    }

    @Test
    fun `drops entries that are blank or not music`() {
        val picks = MusicGeminiClient.parseSuggestions(
            """
            {"items":[
              {"type":"track","title":"","artist":"Someone"},
              {"type":"track","title":"Real","artist":" "},
              {"type":"playlist","title":"A playlist","artist":"Someone"},
              {"type":"","title":"Defaulted to a track","artist":"Someone"},
              {"type":"album","title":"Kept","artist":"Artist","year":1800}
            ]}
            """.trimIndent()
        )

        assertEquals(2, picks.size)
        assertEquals("Defaulted to a track", picks[0].title)
        assertEquals("track", picks[0].type)
        assertEquals("Kept", picks[1].title)
        assertNull(picks[1].year)
    }

    @Test
    fun `deduplicates titles and artists regardless of case`() {
        val picks = MusicGeminiClient.parseSuggestions(
            """
            {"items":[
              {"type":"track","title":"Nightcall","artist":"Kavinsky"},
              {"type":"track","title":"nightcall","artist":"KAVINSKY"},
              {"type":"album","title":"Nightcall","artist":"Kavinsky"}
            ]}
            """.trimIndent()
        )

        assertEquals(2, picks.size)
        assertEquals("track", picks[0].type)
        assertEquals("album", picks[1].type)
    }

    @Test
    fun `rejects malformed answers instead of showing a broken row`() {
        assertThrows(IOException::class.java) {
            MusicGeminiClient.parseSuggestions("not json")
        }
        assertThrows(IOException::class.java) {
            MusicGeminiClient.parseSuggestions("""{"suggestions":[]}""")
        }
    }
}
