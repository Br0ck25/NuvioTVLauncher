package com.nuvio.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeCaptionTranslationTest {
    @Test fun `a cached line survives tabs, newlines and backslashes`() {
        val text = "line one\nline two\twith a tab\\and a backslash"
        assertEquals(text, YouTubeCaptionTranslation.unescape(YouTubeCaptionTranslation.escape(text)))
    }

    @Test fun `an escaped line stays on one row of the cache file`() {
        val escaped = YouTubeCaptionTranslation.escape("first\nsecond\tthird")
        assertEquals(-1, escaped.indexOf('\n'))
        assertEquals(-1, escaped.indexOf('\t'))
    }

    @Test fun `language codes are mapped for the translators`() {
        assertEquals("zh-CN", YouTubeCaptionTranslation.targetCode("zh-Hans"))
        assertEquals("zh-TW", YouTubeCaptionTranslation.targetCode("zh-Hant"))
        assertEquals("he", YouTubeCaptionTranslation.targetCode("iw"))
        assertEquals("he", YouTubeCaptionTranslation.sourceCode("iw"))
        assertEquals("en", YouTubeCaptionTranslation.sourceCode("en-US"))
        assertEquals("de", YouTubeCaptionTranslation.targetCode("de"))
    }
}
