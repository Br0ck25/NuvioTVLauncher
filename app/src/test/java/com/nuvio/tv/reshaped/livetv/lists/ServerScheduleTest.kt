package com.nuvio.tv.reshaped.livetv.lists

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The My Lists Worker's schedule answer, read the way the launcher reads it. `server/schedules_sample.json`
 * is a trimmed copy of what `GET /api/nuvio-tv/schedules/<account>?channels=ch_a,ch_missing` really returned
 * (an episode with a description and artwork, then a movie), so a field renamed on either side fails here
 * rather than on a TV.
 */
class ServerScheduleTest {
    private fun sample(): JSONObject =
        JSONObject(javaClass.classLoader!!.getResource("server/schedules_sample.json")!!.readText())

    @Test
    fun `an episode and a movie come out as the server planned them`() {
        val result = parseServerSchedules(sample(), listOf("ch_a", "ch_missing"))
        val slots = result.getValue("ch_a")
        val episode = slots[0]
        assertEquals("tt0108778:1:1", episode.videoId)
        assertEquals("tt0108778", episode.itemId)
        assertEquals("series", episode.itemType)
        assertEquals(1, episode.season)
        assertEquals(1, episode.episode)
        assertEquals("Friends · S1E1 The Pilot", episode.title)
        assertEquals("Rachel runs out on her wedding.", episode.description)
        assertEquals("https://example.com/e1.jpg", episode.image)
        assertEquals(22 * 60_000L, episode.endMs - episode.startMs)

        val movie = slots[1]
        assertEquals("tt0133093", movie.videoId)
        assertEquals("movie", movie.itemType)
        assertNull("a movie has no season", movie.season)
        assertNull("a movie has no episode", movie.episode)
        assertNull("no description sent", movie.description)
        assertEquals(136 * 60_000L, movie.endMs - movie.startMs)
    }

    @Test
    fun `programmes run end to end`() {
        val slots = parseServerSchedules(sample(), listOf("ch_a")).getValue("ch_a")
        assertEquals(slots[0].endMs, slots[1].startMs)
    }

    @Test
    fun `a channel the server listed as missing is left out`() {
        val result = parseServerSchedules(sample(), listOf("ch_a", "ch_missing"))
        assertEquals(setOf("ch_a"), result.keys)
    }

    @Test
    fun `a failed answer gives nothing`() {
        assertTrue(parseServerSchedules(JSONObject("""{"ok":false,"error":"x"}"""), listOf("ch_a")).isEmpty())
        assertTrue(parseServerSchedules(JSONObject("""{"ok":true}"""), listOf("ch_a")).isEmpty())
    }

    @Test
    fun `entries that cannot be played are skipped`() {
        val array = JSONArray(
            """[
              {"videoId":"","startMs":1000,"endMs":2000},
              {"videoId":"tt1:1:1","startMs":5000,"endMs":5000},
              {"videoId":"tt1:1:2","startMs":-1,"endMs":9000},
              {"videoId":"tt1:1:3","startMs":1000,"endMs":2000,"season":null,"description":null}
            ]""",
        )
        val slots = parseServerSlots(array)
        assertEquals(listOf("tt1:1:3"), slots.map { it.videoId })
        assertNull(slots[0].season)
        assertNull(slots[0].description)
        assertEquals("tt1", slots[0].itemId)
    }
}
