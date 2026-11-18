package com.nuvio.tv.core.launcher

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSetupTest {
    private val context = mockk<Context>(relaxed = true)
    private val roles = mockk<RoleManager>()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `native home role request is returned for an activity result when available`() {
        val intent = mockk<Intent>()
        every { roles.isRoleAvailable(RoleManager.ROLE_HOME) } returns true
        every { roles.isRoleHeld(RoleManager.ROLE_HOME) } returns false
        every { roles.createRequestRoleIntent(RoleManager.ROLE_HOME) } returns intent

        assertSame(intent, LauncherSetup.createHomeRoleRequest(roles))

        // NEW_TASK would detach the role dialog from the Activity Result caller.
        verify(exactly = 0) { intent.addFlags(any()) }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `unavailable home role leaves setup free to fall back`() {
        every { roles.isRoleAvailable(RoleManager.ROLE_HOME) } returns false

        assertNull(LauncherSetup.createHomeRoleRequest(roles))

        verify(exactly = 0) { context.startActivity(any()) }
        verify(exactly = 0) { roles.createRequestRoleIntent(any()) }
    }

    @Test
    fun `existing default does not request the home role again`() {
        every { roles.isRoleAvailable(RoleManager.ROLE_HOME) } returns true
        every { roles.isRoleHeld(RoleManager.ROLE_HOME) } returns true

        assertNull(LauncherSetup.createHomeRoleRequest(roles))

        verify(exactly = 0) { roles.createRequestRoleIntent(any()) }
    }

    @Test
    fun `system Home settings opens successfully`() {
        prepareSettingsIntents()

        assertTrue(LauncherSetup.openHomeSettings(context))

        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun `missing Home settings reports failure for UI fallback`() {
        prepareSettingsIntents()
        every { context.startActivity(any()) } throws ActivityNotFoundException()

        assertFalse(LauncherSetup.openHomeSettings(context))
    }

    @Test
    fun `blocked Home settings reports failure for UI fallback`() {
        prepareSettingsIntents()
        every { context.startActivity(any()) } throws SecurityException("Blocked by TV firmware")

        assertFalse(LauncherSetup.openHomeSettings(context))
    }

    @Test
    fun `selected native Home app is reported active without accessibility`() {
        prepareHomeResolution("com.nuvio.test")

        assertTrue(LauncherSetup.isDefaultHome(context))
        assertTrue(LauncherSetup.handlesHome(context))
    }

    @Test
    fun `stock Home app is not reported as Nuvio being the default`() {
        prepareHomeResolution("com.google.android.tvlauncher")

        assertFalse(LauncherSetup.isDefaultHome(context))
    }

    @Test
    fun `missing Home resolver is not reported active`() {
        val pm = prepareHomeResolution("com.nuvio.test")
        every { pm.resolveActivity(any<Intent>(), PackageManager.MATCH_DEFAULT_ONLY) } returns null

        assertFalse(LauncherSetup.isDefaultHome(context))
    }

    private fun prepareHomeResolution(homePackage: String): PackageManager {
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addCategory(any()) } answers { self as Intent }
        val pm = mockk<PackageManager>()
        every { context.packageManager } returns pm
        every { context.packageName } returns "com.nuvio.test"
        val result = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = homePackage }
        }
        every { pm.resolveActivity(any<Intent>(), PackageManager.MATCH_DEFAULT_ONLY) } returns result
        return pm
    }

    private fun prepareSettingsIntents() {
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
    }
}
