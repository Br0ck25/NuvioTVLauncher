package com.nuvio.tv.core.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home handover is the risky part of the on-device setup: a TV whose new Home does not start can
 * only be rescued over ADB. These guard the command order, the verification and the way back out.
 */
class HomeHandoverTest {

    private val ours = "com.nuvio.tv.launcher/com.nuvio.tv.MainActivity"
    private val ourPackage = "com.nuvio.tv.launcher"
    private val googleTvHome = "com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.HomeActivity"

    @Test
    fun `switching Home targets user 0 and verifies with the real resolver`() {
        val plan = HomeHandover.plan(ours, ourPackage, googleTvHome, disableStockLauncher = false)

        assertEquals("cmd package set-home-activity --user 0 $ours", plan.setHome)
        assertEquals(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
            plan.verifyHome
        )
    }

    @Test
    fun `rollback re-enables the previous launcher before pointing Home back at it`() {
        val plan = HomeHandover.plan(ours, ourPackage, googleTvHome, disableStockLauncher = false)

        assertEquals(
            listOf(
                "pm enable --user 0 com.google.android.apps.tv.launcherx",
                "cmd package set-home-activity --user 0 $googleTvHome"
            ),
            plan.rollback
        )
    }

    @Test
    fun `a launcher we do not recognise is still a valid rollback target but never disabled`() {
        val other = "com.thirdparty.launcher/com.thirdparty.launcher.Home"
        val plan = HomeHandover.plan(ours, ourPackage, other, disableStockLauncher = true)

        assertEquals(listOf("pm enable --user 0 com.thirdparty.launcher", "cmd package set-home-activity --user 0 $other"), plan.rollback)
        assertTrue(plan.disable.isEmpty())
        assertTrue(plan.recovery.isEmpty())
    }

    @Test
    fun `when Nuvio already owns Home there is nothing to switch and nothing to roll back to`() {
        val plan = HomeHandover.plan(ours, ourPackage, ours, disableStockLauncher = true)

        assertNull(plan.setHome)
        assertTrue(plan.rollback.isEmpty())
        assertTrue(plan.disable.isEmpty())
    }

    @Test
    fun `an unknown Home owner is switched away from but never disabled`() {
        val plan = HomeHandover.plan(ours, ourPackage, null, disableStockLauncher = true)

        assertEquals("cmd package set-home-activity --user 0 $ours", plan.setHome)
        assertTrue(plan.disable.isEmpty())
        assertTrue(plan.rollback.isEmpty())
    }

    @Test
    fun `disabling the stock launcher also disables what would re-enable it`() {
        val plan = HomeHandover.plan(ours, ourPackage, googleTvHome, disableStockLauncher = true)

        assertEquals(
            listOf(
                "pm disable-user --user 0 com.google.android.apps.tv.launcherx",
                "pm disable-user --user 0 com.google.android.tungsten.setupwraith"
            ),
            plan.disable
        )
        assertEquals(
            listOf(
                "adb shell pm enable --user 0 com.google.android.apps.tv.launcherx",
                "adb shell pm enable --user 0 com.google.android.tungsten.setupwraith"
            ),
            plan.recovery.take(2)
        )
        assertTrue(plan.recovery.last().startsWith("adb shell cmd package set-home-activity --user 0"))
    }

    @Test
    fun `the stock launcher is left alone unless the run asks for it`() {
        assertTrue(HomeHandover.plan(ours, ourPackage, googleTvHome, disableStockLauncher = false).disable.isEmpty())
    }

    @Test
    fun `Home owners are classified without guessing from the device`() {
        assertEquals(HomeHandover.HomeOwner.OURS, HomeHandover.classify(ourPackage, ourPackage))
        assertEquals(HomeHandover.HomeOwner.KNOWN_STOCK, HomeHandover.classify("com.google.android.tvlauncher", ourPackage))
        assertEquals(HomeHandover.HomeOwner.KNOWN_STOCK, HomeHandover.classify("com.amazon.tv.launcher", ourPackage))
        assertEquals(HomeHandover.HomeOwner.OTHER_LAUNCHER, HomeHandover.classify("com.flauncher", ourPackage))
        assertEquals(HomeHandover.HomeOwner.SYSTEM_CHOOSER, HomeHandover.classify("android", ourPackage))
        assertEquals(HomeHandover.HomeOwner.SETUP_WIZARD, HomeHandover.classify("com.google.android.tungsten.setupwraith", ourPackage))
        assertEquals(HomeHandover.HomeOwner.NONE, HomeHandover.classify(null, ourPackage))
        assertEquals(HomeHandover.HomeOwner.NONE, HomeHandover.classify("", ourPackage))
    }

    @Test
    fun `the setup wizard is never a disable target even though it owns Home on a fresh TV`() {
        val wizard = "com.google.android.tungsten.setupwraith/com.google.android.tungsten.setupwraith.RecoveryActivity"
        val plan = HomeHandover.plan(ours, ourPackage, wizard, disableStockLauncher = true)

        assertTrue(plan.disable.isEmpty())
        assertEquals(listOf("pm enable --user 0 com.google.android.tungsten.setupwraith", "cmd package set-home-activity --user 0 $wizard"), plan.rollback)
    }

    @Test
    fun `resolve output is parsed from the brief two-line form`() {
        val output = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
              com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.HomeActivity
        """.trimIndent()

        assertEquals(googleTvHome, HomeHandover.parseResolvedComponent(output))
    }

    @Test
    fun `the system chooser and empty answers parse to something that is never mistaken for us`() {
        assertEquals("android/android", HomeHandover.parseResolvedComponent("priority=0 match=0x0\n  android/android"))
        assertNull(HomeHandover.parseResolvedComponent(null))
        assertNull(HomeHandover.parseResolvedComponent(""))
        assertNull(HomeHandover.parseResolvedComponent("No activity found"))
        assertFalse(HomeHandover.homeIsOurs(HomeHandover.parseResolvedComponent(""), ours))
    }

    @Test
    fun `a short-form activity from the resolver still counts as ours`() {
        assertTrue(HomeHandover.homeIsOurs("$ourPackage/.MainActivity", ours))
        assertTrue(HomeHandover.homeIsOurs("$ourPackage/com.nuvio.tv.MainActivity/", ours))
        assertFalse(HomeHandover.homeIsOurs(googleTvHome, ours))
        assertFalse(HomeHandover.homeIsOurs(null, ours))
    }

    @Test
    fun `grant commands name the permission and the restricted-settings op`() {
        assertEquals(
            "pm grant $ourPackage android.permission.WRITE_SECURE_SETTINGS",
            HomeHandover.grantWriteSecureSettingsCommand(ourPackage)
        )
        assertEquals(
            "appops set $ourPackage ACCESS_RESTRICTED_SETTINGS allow",
            HomeHandover.allowRestrictedSettingsCommand(ourPackage)
        )
        assertEquals("cmd package compile -m speed -f $ourPackage", HomeHandover.compileCommand(ourPackage))
    }
}
