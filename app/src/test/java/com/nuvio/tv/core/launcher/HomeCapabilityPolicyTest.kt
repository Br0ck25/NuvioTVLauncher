package com.nuvio.tv.core.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCapabilityPolicyTest {
    @Test
    fun `native home wins over accessibility regardless of binding state`() {
        for (enabled in listOf(false, true)) for (operational in listOf(false, true)) {
            assertEquals(HomeMechanism.NATIVE, HomeCapabilityPolicy.mechanism(true, enabled, operational))
        }
    }

    @Test
    fun `fallback requires both authorization and live binding`() {
        assertEquals(HomeMechanism.ACCESSIBILITY, HomeCapabilityPolicy.mechanism(false, true, true))
        assertEquals(HomeMechanism.UNAVAILABLE, HomeCapabilityPolicy.mechanism(false, true, false))
        assertEquals(HomeMechanism.UNAVAILABLE, HomeCapabilityPolicy.mechanism(false, false, true))
        assertEquals(HomeMechanism.UNAVAILABLE, HomeCapabilityPolicy.mechanism(false, false, false))
    }

    @Test
    fun `normal unsupported and already working installs are never prompted automatically`() {
        assertFalse(request(available = false))
        assertFalse(request(defaultHome = true))
        assertFalse(request(held = true))
        // Even an authorized but disconnected fallback is an existing user's choice.
        assertFalse(request(accessibility = true))
    }

    @Test
    fun `eligible install prompts once and neither denial nor restart permits another prompt`() {
        assertTrue(request())
        assertFalse(request(attempted = true))
        assertFalse(request(attempted = true, available = false))
        assertFalse(request(attempted = true, defaultHome = true))
    }

    private fun request(
        attempted: Boolean = false,
        defaultHome: Boolean = false,
        available: Boolean = true,
        held: Boolean = false,
        accessibility: Boolean = false
    ) = HomeCapabilityPolicy.shouldRequestNativeHome(attempted, defaultHome, available, held, accessibility)
}
