package com.nuvio.tv.core.launcher

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only platform checks. Does not grant permissions, change defaults or open settings. */
@RunWith(AndroidJUnit4::class)
class LauncherCapabilityPlatformTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun capabilitySnapshotMatchesPlatformResolverAndRoleState() {
        val observed = LauncherCapabilities.detect(context)
        val selectedPackage = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        assertEquals(Build.MANUFACTURER, observed.manufacturer)
        assertEquals(Build.MODEL, observed.model)
        assertEquals(Build.VERSION.SDK_INT, observed.sdkVersion)
        assertEquals(selectedPackage == context.packageName, observed.isDefaultHome)
        assertEquals(LauncherSetup.isDefaultHome(context), observed.isDefaultHome)
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            assertEquals(roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true, observed.homeRoleAvailable)
            assertEquals(roles?.isRoleHeld(RoleManager.ROLE_HOME) == true, observed.homeRoleHeld)
        } else {
            assertFalse(observed.homeRoleAvailable)
            assertFalse(observed.homeRoleHeld)
        }
    }

    @Test
    fun authorizationRequiresTheExactComponentAndGlobalAccessibilitySwitch() {
        val target = ComponentName(context, LauncherAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val globallyEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        assertEquals(globallyEnabled && LauncherAccessibilityService.containsEnabledComponent(enabled, target),
            LauncherAccessibilityService.isServiceEnabled(context))
        assertTrue(LauncherAccessibilityService.containsEnabledComponent(target.flattenToString(), target))
        assertTrue(LauncherAccessibilityService.containsEnabledComponent(target.flattenToShortString(), target))
        assertTrue(LauncherAccessibilityService.containsEnabledComponent("other.app/.Reader:${target.flattenToString()}", target))
        assertFalse(LauncherAccessibilityService.containsEnabledComponent(null, target))
        assertFalse(LauncherAccessibilityService.containsEnabledComponent("malformed", target))
        assertFalse(LauncherAccessibilityService.containsEnabledComponent(
            "another.package/${LauncherAccessibilityService::class.java.name}", target))
    }

    @Test
    fun explicitNativeSelectionAndFallbackOptInSurviveARecreatedContext() {
        // Isolate app-internal observations from the real install; do not alter Android defaults.
        fun isolatedContext() = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                super.getSharedPreferences("launcher_platform_test", mode)
        }
        val first = isolatedContext()
        LauncherSetup.preferences(first).edit().clear().commit()
        try {
            assertTrue(LauncherSetup.accessibilityFallbackAllowed(first))
            LauncherSetup.observeNativeHome(first, true)
            assertFalse(LauncherSetup.accessibilityFallbackAllowed(isolatedContext()))
            LauncherSetup.observeNativeHome(first, false)
            assertFalse(LauncherSetup.accessibilityFallbackAllowed(isolatedContext()))
            LauncherSetup.allowAccessibilityFallback(first)
            assertTrue(LauncherSetup.accessibilityFallbackAllowed(isolatedContext()))
            LauncherSetup.preferences(first).edit().putBoolean(LauncherSetup.AUTO_REQUEST_ATTEMPTED, true).commit()
            assertTrue(LauncherSetup.preferences(isolatedContext()).getBoolean(LauncherSetup.AUTO_REQUEST_ATTEMPTED, false))
        } finally {
            LauncherSetup.preferences(first).edit().clear().commit()
        }
    }

    @Test
    fun selectedFallbackActivitiesAreDeclaredHomeActivitiesInSelectedPackage() {
        val observed = LauncherCapabilities.detect(context)
        val candidates = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        ).map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toSet()
        observed.selectedHomeActivities.forEach {
            assertTrue(it in candidates)
            assertEquals(observed.selectedHome?.packageName, it.packageName)
        }
        assertEquals(observed.accessibilityEnabled && LauncherAccessibilityService.isOperational.value,
            observed.accessibilityOperational)
    }
}
