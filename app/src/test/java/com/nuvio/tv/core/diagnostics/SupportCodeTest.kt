package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SupportCodeTest {

    @Test
    fun `support code is stable and does not expose the uuid`() {
        val userId = "91c45662-19ff-44ca-8355-71389e1f3660"
        val code = SupportCode.forUserId(userId)

        assertEquals("AC1C694222", code)
        assertEquals(code, SupportCode.forUserId(userId.uppercase()))
        assertFalse(code.contains(userId))
    }
}
