package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateDownloadProgressTest {

    @Test
    fun `fraction follows the bytes received`() {
        val progress = UpdateDownloadProgress(
            tag = "1.1.0-beta.13.4",
            downloadedBytes = 48_500_000,
            totalBytes = 97_000_000
        )
        assertEquals(0.5f, progress.fraction)
    }

    @Test
    fun `fraction is clamped when the server sends extra bytes`() {
        val progress = UpdateDownloadProgress(
            tag = "1.1.0-beta.13.4",
            downloadedBytes = 120_000_000,
            totalBytes = 97_000_000
        )
        assertEquals(1f, progress.fraction)
    }

    @Test
    fun `fraction is unknown without a total`() {
        val progress = UpdateDownloadProgress(
            tag = "1.1.0-beta.13.4",
            downloadedBytes = 1_000,
            totalBytes = null
        )
        assertNull(progress.fraction)
    }
}
