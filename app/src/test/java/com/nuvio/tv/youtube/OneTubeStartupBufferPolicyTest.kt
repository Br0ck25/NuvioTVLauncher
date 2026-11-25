package com.nuvio.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class OneTubeStartupBufferPolicyTest {
    @Test fun `caps only values above the normal player startup threshold`() {
        assertEquals(2_500, OneTubeStartupBufferPolicy.initialBufferMs(5_000))
        assertEquals(2_000, OneTubeStartupBufferPolicy.initialBufferMs(2_000))
    }

    @Test fun `keeps configured values in a safe positive range`() {
        assertEquals(1, OneTubeStartupBufferPolicy.initialBufferMs(0))
        assertEquals(1, OneTubeStartupBufferPolicy.initialBufferMs(-10))
    }
}
