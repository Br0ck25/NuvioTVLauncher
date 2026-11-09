package com.nuvio.tv.reshaped.livetv

import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LiveTvClockTest {
    private fun dateHeader(epochMs: Long): Headers {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        return Headers.headersOf("Date", format.format(java.util.Date(epochMs)))
    }

    @After
    fun reset() {
        // A server within three seconds leaves the clock alone, which also clears any earlier offset.
        val now = System.currentTimeMillis()
        LiveTvClock.observeServerDate(dateHeader(now), now - 50, now + 50)
    }

    @Test
    fun `a TV clock that is fast is brought back to the server's`() {
        val sent = System.currentTimeMillis()
        val received = sent + 200
        // The server says it is five minutes earlier than the device thinks.
        LiveTvClock.observeServerDate(dateHeader(sent - 5 * 60_000L), sent, received)
        val corrected = LiveTvClock.nowEpochMs()
        val expected = System.currentTimeMillis() - 5 * 60_000L
        assertTrue("clock is about five minutes back: ${expected - corrected}", kotlin.math.abs(corrected - expected) < 2_000L)
    }

    @Test
    fun `a small difference is noise and changes nothing`() {
        val sent = System.currentTimeMillis()
        LiveTvClock.observeServerDate(dateHeader(sent + 1_500L), sent, sent + 100)
        assertTrue(kotlin.math.abs(LiveTvClock.nowEpochMs() - System.currentTimeMillis()) < 500L)
    }

    @Test
    fun `a slow round trip is not trusted`() {
        val sent = System.currentTimeMillis()
        LiveTvClock.observeServerDate(dateHeader(sent - 10 * 60_000L), sent, sent + 9_000L)
        assertTrue(kotlin.math.abs(LiveTvClock.nowEpochMs() - System.currentTimeMillis()) < 500L)
    }

    @Test
    fun `a reply without a Date header changes nothing`() {
        LiveTvClock.observeServerDate(Headers.headersOf(), 0L, 100L)
        assertTrue(kotlin.math.abs(LiveTvClock.nowEpochMs() - System.currentTimeMillis()) < 500L)
    }

    @Test
    fun `a corrected clock returns to the device's own once the server agrees again`() {
        val sent = System.currentTimeMillis()
        LiveTvClock.observeServerDate(dateHeader(sent - 60_000L), sent, sent + 100)
        assertTrue(kotlin.math.abs(LiveTvClock.nowEpochMs() - System.currentTimeMillis()) > 30_000L)
        val later = System.currentTimeMillis()
        LiveTvClock.observeServerDate(dateHeader(later), later, later + 100)
        assertTrue(kotlin.math.abs(LiveTvClock.nowEpochMs() - System.currentTimeMillis()) < 500L)
    }
}
