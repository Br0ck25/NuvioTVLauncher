package com.nuvio.tv.core.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherCapabilitiesTest {
    @Test
    fun `manufacturer and install source are not used as a native restriction verdict`() {
        for (manufacturer in listOf("Google", "TiVo", "SEI Robotics", "unknown")) {
            val observed = snapshot().copy(manufacturer = manufacturer)
            assertEquals(NativeHomeRestriction.UNKNOWN, observed.restrictionEvidence)
            assertEquals(HomeMechanism.UNAVAILABLE, observed.mechanism)
            assertEquals(HomeMechanism.NATIVE, observed.copy(isDefaultHome = true).mechanism)
        }
    }

    @Test
    fun `held role with different resolved home is reported separately from working native home`() {
        val observed = snapshot().copy(homeRoleHeld = true)
        assertEquals(NativeHomeRestriction.ROLE_RESOLVER_MISMATCH, observed.restrictionEvidence)
        assertEquals(HomeMechanism.UNAVAILABLE, observed.mechanism)
        assertEquals(NativeHomeRestriction.NONE_OBSERVED, observed.copy(isDefaultHome = true).restrictionEvidence)
    }

    @Test
    fun `request failure is evidence not a blanket device blacklist`() {
        val observed = snapshot().copy(nativeRequestFailed = true)
        assertEquals(NativeHomeRestriction.REQUEST_FAILED, observed.restrictionEvidence)
        assertEquals(HomeMechanism.ACCESSIBILITY, observed.copy(
            accessibilityEnabled = true, accessibilityOperational = true
        ).mechanism)
        assertEquals(HomeMechanism.NATIVE, observed.copy(isDefaultHome = true).mechanism)
    }

    @Test
    fun `enabled but disconnected service is never advertised as working fallback`() {
        assertEquals(HomeMechanism.UNAVAILABLE, snapshot().copy(accessibilityEnabled = true).mechanism)
        assertEquals(HomeMechanism.ACCESSIBILITY, snapshot().copy(
            accessibilityEnabled = true, accessibilityOperational = true
        ).mechanism)
    }

    @Test
    fun `changing a previously selected native default does not reactivate the fallback`() {
        val authorized = snapshot().copy(accessibilityEnabled = true, accessibilityOperational = true)
        assertEquals(HomeMechanism.ACCESSIBILITY, authorized.mechanism)
        assertEquals(HomeMechanism.UNAVAILABLE, authorized.copy(accessibilityFallbackAllowed = false).mechanism)
        assertEquals(HomeMechanism.NATIVE, authorized.copy(
            accessibilityFallbackAllowed = false, isDefaultHome = true
        ).mechanism)
    }

    private fun snapshot() = LauncherCapabilities(
        manufacturer = "unknown", model = "test", androidVersion = "9", sdkVersion = 28,
        homeRoleAvailable = false, homeRoleHeld = false, isDefaultHome = false,
        homeSettingsResolvable = false, accessibilityEnabled = false, accessibilityOperational = false,
        selectedHome = null, selectedHomeActivities = emptySet(), nativeRequestFailed = false
    )
}
