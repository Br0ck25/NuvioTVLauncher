package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportRedactorTest {

    @Test
    fun `addon and stream urls keep only the host`() {
        val redacted = DebugReportRedactor.redact(
            "addon=https://torrentio.strem.fun/manifest.json?config=abc123&token=xyz"
        )

        assertTrue(redacted.contains("https://torrentio.strem.fun/[redacted]"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("xyz"))
        assertFalse(redacted.contains("manifest.json"))
    }

    @Test
    fun `url user info and path are removed`() {
        val redacted = DebugReportRedactor.redact(
            "stream=https://user:pass@example.com/live/secret.m3u8?key=value"
        )

        assertTrue(redacted.contains("https://example.com/[redacted]"))
        assertFalse(redacted.contains("user:pass"))
        assertFalse(redacted.contains("secret.m3u8"))
        assertFalse(redacted.contains("key=value"))
    }

    @Test
    fun `headers and api keys are removed`() {
        val redacted = DebugReportRedactor.redact(
            """
            Authorization: Bearer abcdefgh1234567890
            api_key=supersecret
            X-Api-Key: anothersecret
            Cookie: session=private
            """.trimIndent()
        )

        assertFalse(redacted.contains("abcdefgh1234567890"))
        assertFalse(redacted.contains("supersecret"))
        assertFalse(redacted.contains("anothersecret"))
        assertFalse(redacted.contains("private"))
        assertTrue(redacted.contains("[redacted]"))
    }

    @Test
    fun `personal data is removed`() {
        val redacted = DebugReportRedactor.redact(
            "contact user@example.com from 192.168.1.20 id 98f8a8a8-f7e5-4acc-807c-bb7db4f687c1"
        )

        assertFalse(redacted.contains("user@example.com"))
        assertFalse(redacted.contains("192.168.1.20"))
        assertFalse(redacted.contains("98f8a8a8-f7e5-4acc-807c-bb7db4f687c1"))
        assertTrue(redacted.contains("[email]"))
        assertTrue(redacted.contains("[ip]"))
        assertTrue(redacted.contains("[id]"))
    }

    @Test
    fun `ordinary report text is left alone`() {
        val text = "version=1.1.0-beta.9.6 build=107213 player=INTERNAL"

        assertTrue(DebugReportRedactor.redact(text) == text)
    }
}
