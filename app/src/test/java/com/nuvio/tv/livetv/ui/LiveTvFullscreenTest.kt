package com.nuvio.tv.livetv.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveTvFullscreenTest {
    @Before @After fun clearRequest() {
        LiveTvFullscreen.pending = false
    }

    @Test fun `fullscreen request increments the state observed by the guide`() {
        val previousRequests = LiveTvFullscreen.requests.value

        LiveTvFullscreen.request()

        assertTrue(LiveTvFullscreen.pending)
        assertEquals(previousRequests + 1, LiveTvFullscreen.requests.value)
    }
}
