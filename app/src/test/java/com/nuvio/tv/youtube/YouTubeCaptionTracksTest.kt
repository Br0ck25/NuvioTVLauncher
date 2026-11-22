package com.nuvio.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCaptionTracksTest {

    private fun set(id: Int, mime: String, lang: String, url: String) =
        "<AdaptationSet id=\"$id\" mimeType=\"$mime\" lang=\"$lang\">" +
            "<Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"subtitle\"/>" +
            "<Representation id=\"$id\" codecs=\"wvtt\" bandwidth=\"268\">" +
            "<BaseURL>$url</BaseURL></Representation></AdaptationSet>"

    private fun caption(id: Int, lang: String, url: String) = set(id, "text/vtt", lang, url)

    private fun url(lang: String, extra: String = "") =
        "https://www.youtube.com/api/timedtext?v=1&amp;lang=$lang&amp;fmt=vtt$extra"

    private fun mpd(vararg sets: String) =
        "<?xml version=\"1.0\"?><MPD><Period>" +
            set(0, "video/mp4", "und", "http://video") +
            sets.joinToString("") +
            "</Period></MPD>"

    @Test
    fun `keeps device, preferred, secondary and English`() {
        val keep = YouTubeCaptionTracks.keepLanguages(
            preferred = "tr-TR",
            secondary = "de",
            device = listOf("es-ES")
        )
        assertEquals(setOf("es", "en", "tr", "de"), keep)
    }

    @Test
    fun `special subtitle settings are not languages`() {
        val keep = YouTubeCaptionTracks.keepLanguages(
            preferred = "none",
            secondary = "forced",
            device = emptyList()
        )
        assertEquals(setOf("en"), keep)
    }

    @Test
    fun `drops unused translations but keeps written and original captions`() {
        val xml = mpd(
            caption(1, "tr", url("tr", "&amp;tlang=tr")),
            caption(2, "tr", url("tr", "&amp;kind=asr")),
            caption(3, "en", url("tr", "&amp;kind=asr&amp;tlang=en")),
            caption(4, "de", url("tr", "&amp;kind=asr&amp;tlang=de")),
            caption(5, "fr", url("tr", "&amp;kind=asr&amp;tlang=fr")),
            caption(6, "tr", url("tr"))
        )
        val keep = YouTubeCaptionTracks.keepLanguages("tr", null, device = emptyList())
        val out = YouTubeCaptionTracks.limitTranslated(xml, keep)

        assertTrue("written Turkish", "lang=\"tr\"" in out)
        assertTrue("original automatic English translation kept", "tlang=en" in out)
        assertTrue("preferred Turkish translation kept", "tlang=tr" in out)
        assertFalse("German translation dropped", "tlang=de" in out)
        assertFalse("French translation dropped", "tlang=fr" in out)
        assertTrue("video set untouched", "http://video" in out)
    }

    @Test
    fun `leaves manifests without translated captions alone`() {
        val xml = mpd(
            caption(1, "tr", url("tr")),
            caption(2, "en", url("tr", "&amp;kind=asr"))
        )
        assertEquals(xml, YouTubeCaptionTracks.limitTranslated(xml, setOf("en")))
    }
}
