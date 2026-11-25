package com.nuvio.tv.livetv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLiveRowTest {
    @Test
    fun roundTripsSupportedRowKindsAndOptions() {
        val rows = listOf(
            HomeLiveRow.channels("favorites").copy(
                title = "My channels", sort = HomeLiveRow.SORT_NUMBER, showNext = true,
                opensGuide = true, shape = HomeLiveRow.SHAPE_LANDSCAPE, after = "catalog_1"
            ),
            HomeLiveRow.sports("league:nba").copy(playsGame = true, startingSoon = false),
            HomeLiveRow.vod("group:movie:playlist:category", "Action").copy(limit = 24)
        )

        assertEquals(rows, HomeLiveRow.decode(HomeLiveRow.encode(rows)))
    }

    @Test
    fun invalidRowsAreIgnoredAndDuplicateIdsKeepFirst() {
        val json = """
            [
              {"id":"one","kind":"channels","source":"favorites"},
              {"id":"one","kind":"sports","source":""},
              {"id":"bad","kind":"unsupported","source":""},
              {"id":"two","kind":"ondemand","source":"recent:movie","limit":900}
            ]
        """.trimIndent()

        val decoded = HomeLiveRow.decode(json)

        assertEquals(listOf("one", "two"), decoded.map { it.id })
        assertEquals(HomeLiveRow.KIND_CHANNELS, decoded.first().kind)
        assertEquals(HomeLiveRow.MAX_LIMIT, decoded.last().limit)
        assertTrue(HomeLiveRow.isKey("livetv_one"))
        assertFalse(HomeLiveRow.isKey("catalog_one"))
    }
}
