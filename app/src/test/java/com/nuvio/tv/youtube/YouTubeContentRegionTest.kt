package com.nuvio.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class YouTubeContentRegionTest {
    @Test fun `region codes normalize independently of device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            assertEquals("IN", normalizeYouTubeContentRegion(" in "))
            assertEquals("US", normalizeYouTubeContentRegion("us"))
            assertEquals("", normalizeYouTubeContentRegion("  "))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
