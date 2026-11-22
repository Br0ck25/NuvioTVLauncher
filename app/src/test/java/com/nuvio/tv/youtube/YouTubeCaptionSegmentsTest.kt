package com.nuvio.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeCaptionSegmentsTest {
    private val captions = """WEBVTT

00:00:02.000 --> 00:00:04.000
First

00:00:59.000 --> 00:01:02.500
Crosses boundary

00:35:10.125 --> 00:35:12.250
Later text

"""

    @Test fun `first segment clips a cue at its end`() {
        assertEquals("WEBVTT\n\n00:00:02.000 --> 00:00:04.000\nFirst\n\n00:00:59.000 --> 00:01:00.000\nCrosses boundary\n\n", YouTubeCaptionSegments.extract(captions, 0))
    }

    @Test fun `next segment continues a crossing cue from zero`() {
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:02.500\nCrosses boundary\n\n", YouTubeCaptionSegments.extract(captions, 1))
    }

    @Test fun `seeking far into a video rebases timestamps`() {
        assertEquals("WEBVTT\n\n00:00:10.125 --> 00:00:12.250\nLater text\n\n", YouTubeCaptionSegments.extract(captions, 35))
    }

    @Test fun `empty and invalid segments are valid empty VTT`() {
        assertEquals("WEBVTT\n\n", YouTubeCaptionSegments.extract(captions, 2))
        assertEquals("WEBVTT\n\n", YouTubeCaptionSegments.extract(captions, Long.MAX_VALUE))
    }
}
