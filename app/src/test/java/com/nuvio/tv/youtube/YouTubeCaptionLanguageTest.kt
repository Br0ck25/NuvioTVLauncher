package com.nuvio.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Media3 reports the Serbo-Croatian variants as hbs-hrv/hbs-srp/hbs-bos while YouTube's
 * translated caption URLs use hr/sr/bs. The translated-caption overlay matches tracks by
 * these codes, so the variants must normalize to the code YouTube uses.
 */
class YouTubeCaptionLanguageTest {
    @Test
    fun `serbo croatian variants map to youtube codes`() {
        assertEquals("hr", YouTubeMpdServer.captionLanguageCode("hbs-hrv"))
        assertEquals("hr", YouTubeMpdServer.captionLanguageCode("HBS_HR"))
        assertEquals("sr", YouTubeMpdServer.captionLanguageCode("hbs-srp"))
        assertEquals("bs", YouTubeMpdServer.captionLanguageCode("hbs-bos"))
    }

    @Test
    fun `legacy and alternate codes map to the modern code`() {
        assertEquals("he", YouTubeMpdServer.captionLanguageCode("iw"))
        assertEquals("id", YouTubeMpdServer.captionLanguageCode("in"))
        assertEquals("yi", YouTubeMpdServer.captionLanguageCode("ji"))
        assertEquals("tl", YouTubeMpdServer.captionLanguageCode("fil"))
        assertEquals("no", YouTubeMpdServer.captionLanguageCode("nb"))
    }

    @Test
    fun `ordinary codes are lowercased and keep their region`() {
        assertEquals("en-us", YouTubeMpdServer.captionLanguageCode("EN-US"))
        assertEquals("hr", YouTubeMpdServer.captionLanguageCode("hr"))
        assertEquals("pt-br", YouTubeMpdServer.captionLanguageCode("pt_BR"))
    }
}
