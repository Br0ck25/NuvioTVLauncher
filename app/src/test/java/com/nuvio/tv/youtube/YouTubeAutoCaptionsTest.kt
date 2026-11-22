package com.nuvio.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAutoCaptionsTest {
    // The start of a real automatic-caption file: each caption has the old line on top and the new one under it,
    // then a ten-millisecond caption with the old line alone.
    private val rolling = """WEBVTT
Kind: captions
Language: en

00:00:02.159 --> 00:00:03.510 align:start position:0%
@@
hey<00:00:02.320><c> guys</c><00:00:02.560><c> welcome</c><00:00:02.879><c> back</c><00:00:03.040><c> to</c><00:00:03.120><c> our</c><00:00:03.199><c> channel</c>

00:00:03.510 --> 00:00:03.520 align:start position:0%
hey guys welcome back to our channel
@@

00:00:03.520 --> 00:00:04.789 align:start position:0%
hey guys welcome back to our channel
kurian<00:00:04.000><c> su</c>

00:00:04.789 --> 00:00:04.799 align:start position:0%
kurian su
@@

00:00:04.799 --> 00:00:06.070 align:start position:0%
kurian su
one<00:00:05.040><c> of</c><00:00:05.120><c> the</c><00:00:05.200><c> things</c>

00:00:06.070 --> 00:00:06.080 align:start position:0%
one of the things
@@
""".replace("@@", " ")

    @Test
    fun `each caption keeps only its newest line`() {
        val out = YouTubeAutoCaptions.tidy(rolling)
        assertTrue(out, out.contains("00:00:02.159 --> 00:00:03.520\nhey guys welcome back to our channel\n"))
        assertTrue(out, out.contains("00:00:03.520 --> 00:00:04.799\nkurian su\n"))
        assertTrue(out, out.contains("00:00:04.799 --> 00:00:06.080\none of the things\n"))
    }

    @Test
    fun `an old line is never shown with the one after it`() {
        val out = YouTubeAutoCaptions.tidy(rolling)
        // No caption holds two lines.
        out.split("\n\n").filter { "-->" in it }.forEach { block ->
            assertEquals(block, 2, block.trim().lines().size)
        }
        assertFalse(out, out.contains("<"))
    }

    @Test
    fun `captions follow one another without overlapping`() {
        val out = YouTubeAutoCaptions.tidy(
            """WEBVTT

00:00:01.000 --> 00:00:05.000
first

00:00:03.000 --> 00:00:06.000
second
"""
        )
        assertTrue(out, out.contains("00:00:01.000 --> 00:00:03.000\nfirst"))
        assertTrue(out, out.contains("00:00:03.000 --> 00:00:06.000\nsecond"))
    }

    @Test
    fun `hours and CRLF line ends are handled`() {
        val out = YouTubeAutoCaptions.tidy("WEBVTT\r\n\r\n01:02:03.004 --> 01:02:04.000\r\nhello\r\n\r\n")
        assertTrue(out, out.contains("01:02:03.004 --> 01:02:04.000\nhello"))
    }

    @Test
    fun `inline timestamp tags are stripped from translated captions`() {
        val out = YouTubeAutoCaptions.tidy(
            """WEBVTT

00:00:01.000 --> 00:00:03.000
Hello<00:00:01.500><c> world</c>

00:00:03.000 --> 00:00:04.000
Second<00:00:03.200><c> line</c>
"""
        )
        assertTrue(out, out.contains("Hello world"))
        assertTrue(out, out.contains("Second line"))
        assertFalse(out, out.contains("<"))
        assertFalse(out, out.contains("00:00:01.500"))
    }

    @Test
    fun `rolling captions are detected from the file when the URL has no kind marker`() {
        assertTrue(YouTubeAutoCaptions.looksAutomatic(rolling))
        assertFalse(
            YouTubeAutoCaptions.looksAutomatic(
                "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
            )
        )
    }

    @Test
    fun `a file that is not WebVTT is left alone`() {
        val ttml = "<tt><body><div><p begin=\"0s\" end=\"1s\">hi</p></div></body></tt>"
        assertEquals(ttml, YouTubeAutoCaptions.tidy(ttml))
    }

    @Test
    fun `a cue with the same Arabic line twice keeps one copy`() {
        assertEquals(
            "مرحبا",
            YouTubeAutoCaptions.cleanRollingCue("مرحبا\nمرحبا", previousText = null)
        )
    }

    @Test
    fun `a rolling cue drops the previous line`() {
        assertEquals(
            "أهلا",
            YouTubeAutoCaptions.cleanRollingCue(
                "مرحبا\nأهلا",
                previousText = "مرحبا"
            )
        )
    }

    @Test
    fun `a manual two-line cue keeps both different lines`() {
        assertEquals(
            "مرحبا\nأهلا",
            YouTubeAutoCaptions.cleanRollingCue(
                "مرحبا\nأهلا",
                previousText = "شيء آخر"
            )
        )
    }

    @Test
    fun `a single held line is returned for the caller to merge`() {
        assertEquals(
            "مرحبا",
            YouTubeAutoCaptions.cleanRollingCue("مرحبا", previousText = "مرحبا")
        )
    }
}
