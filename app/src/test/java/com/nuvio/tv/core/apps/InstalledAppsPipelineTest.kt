package com.nuvio.tv.core.apps

import android.content.ComponentName
import androidx.compose.ui.graphics.ImageBitmap
import com.nuvio.tv.data.local.InstalledAppsPreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppsPipelineTest {

    @Test
    fun `customization pipeline respects manual override over global icon pack`() = runBlocking {
        val iconPackManager = mockk<IconPackManager>(relaxed = true)
        val manualOverrideBitmap = mockk<ImageBitmap>(relaxed = true)
        val packBitmap = mockk<ImageBitmap>(relaxed = true)
        val nativeIcon = mockk<ImageBitmap>(relaxed = true)

        val component = ComponentName("com.example.app", "com.example.app.MainActivity")
        val baseApp = InstalledApp(
            packageName = "com.example.app",
            label = "Example",
            componentName = component,
            icon = nativeIcon,
            hasBanner = true
        )

        val prefs = InstalledAppsPreferences(
            selectedIconPackPackage = "com.cool.iconpack",
            customIcons = mapOf("com.example.app" to "iconpack:manual.pack:custom_drawable")
        )

        coEvery {
            iconPackManager.loadSpecificIcon("manual.pack", "custom_drawable")
        } returns manualOverrideBitmap

        coEvery {
            iconPackManager.loadIconForApp("com.example.app", component, "com.cool.iconpack")
        } returns packBitmap

        // Pipeline execution logic:
        val iconRef = prefs.customIcons[baseApp.packageName]
        val customIcon = when {
            iconRef == "system_banner" -> baseApp.bannerBitmap ?: baseApp.icon
            iconRef == "system_icon" -> baseApp.squareBitmap ?: baseApp.icon
            iconRef?.startsWith("iconpack:") == true -> {
                val parts = iconRef.removePrefix("iconpack:").split(":")
                if (parts.size >= 2) {
                    iconPackManager.loadSpecificIcon(parts[0], parts[1])
                } else null
            }
            prefs.selectedIconPackPackage != null -> {
                iconPackManager.loadIconForApp(baseApp.packageName, baseApp.componentName, prefs.selectedIconPackPackage)
            }
            else -> null
        }

        val customizedApp = baseApp.copy(customIcon = customIcon)

        // Manual override wins!
        assertEquals(manualOverrideBitmap, customizedApp.customIcon)
        assertEquals(manualOverrideBitmap, customizedApp.displayIcon)
    }

    @Test
    fun `customization pipeline applies icon pack when no manual override exists`() = runBlocking {
        val iconPackManager = mockk<IconPackManager>(relaxed = true)
        val packBitmap = mockk<ImageBitmap>(relaxed = true)
        val nativeIcon = mockk<ImageBitmap>(relaxed = true)

        val component = ComponentName("com.netflix.ninja", "com.netflix.ninja.MainActivity")
        val baseApp = InstalledApp(
            packageName = "com.netflix.ninja",
            label = "Netflix",
            componentName = component,
            icon = nativeIcon,
            hasBanner = true
        )

        val prefs = InstalledAppsPreferences(
            selectedIconPackPackage = "com.projectivy.iconpack"
        )

        coEvery {
            iconPackManager.loadIconForApp("com.netflix.ninja", component, "com.projectivy.iconpack")
        } returns packBitmap

        val iconRef = prefs.customIcons[baseApp.packageName]
        val customIcon = when {
            iconRef == "system_banner" -> baseApp.bannerBitmap ?: baseApp.icon
            iconRef == "system_icon" -> baseApp.squareBitmap ?: baseApp.icon
            iconRef?.startsWith("iconpack:") == true -> {
                val parts = iconRef.removePrefix("iconpack:").split(":")
                if (parts.size >= 2) {
                    iconPackManager.loadSpecificIcon(parts[0], parts[1])
                } else null
            }
            prefs.selectedIconPackPackage != null -> {
                iconPackManager.loadIconForApp(baseApp.packageName, baseApp.componentName, prefs.selectedIconPackPackage)
            }
            else -> null
        }

        val customizedApp = baseApp.copy(customIcon = customIcon)

        // Selected pack is used!
        assertEquals(packBitmap, customizedApp.customIcon)
        assertEquals(packBitmap, customizedApp.displayIcon)
    }

    @Test
    fun `customization pipeline falls back to native icon when pack does not contain app`() = runBlocking {
        val iconPackManager = mockk<IconPackManager>(relaxed = true)
        val nativeIcon = mockk<ImageBitmap>(relaxed = true)

        val component = ComponentName("com.unmapped.app", "com.unmapped.app.MainActivity")
        val baseApp = InstalledApp(
            packageName = "com.unmapped.app",
            label = "Unmapped",
            componentName = component,
            icon = nativeIcon,
            hasBanner = false
        )

        val prefs = InstalledAppsPreferences(
            selectedIconPackPackage = "com.projectivy.iconpack"
        )

        coEvery {
            iconPackManager.loadIconForApp("com.unmapped.app", component, "com.projectivy.iconpack")
        } returns null

        val iconRef = prefs.customIcons[baseApp.packageName]
        val customIcon = when {
            iconRef == "system_banner" -> baseApp.bannerBitmap ?: baseApp.icon
            iconRef == "system_icon" -> baseApp.squareBitmap ?: baseApp.icon
            iconRef?.startsWith("iconpack:") == true -> {
                val parts = iconRef.removePrefix("iconpack:").split(":")
                if (parts.size >= 2) {
                    iconPackManager.loadSpecificIcon(parts[0], parts[1])
                } else null
            }
            prefs.selectedIconPackPackage != null -> {
                iconPackManager.loadIconForApp(baseApp.packageName, baseApp.componentName, prefs.selectedIconPackPackage)
            }
            else -> null
        }

        val customizedApp = baseApp.copy(customIcon = customIcon)

        // Falls back to native icon!
        assertNull(customizedApp.customIcon)
        assertEquals(nativeIcon, customizedApp.displayIcon)
    }
}
