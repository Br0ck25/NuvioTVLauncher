package com.nuvio.tv.core.launcher

import android.provider.Settings
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePlatformBridge(
    private val installedPackages: Set<String> = setOf("com.netflix.ninja", "com.google.android.youtube.tv")
) : ButtonActionPlatformBridge {
    val dispatchedMediaKeys = mutableListOf<Int>()
    val launchedPackages = mutableListOf<String>()
    val nuvioIntentActions = mutableListOf<ButtonAction>()
    var androidSettingsOpened = 0
    var displaySettingsOpened = 0

    override fun dispatchMediaKey(keyCode: Int): Boolean {
        dispatchedMediaKeys.add(keyCode)
        return true
    }

    override fun launchInstalledPackage(packageName: String, appLabel: String): Boolean {
        if (packageName !in installedPackages) return false
        launchedPackages.add(packageName)
        return true
    }

    override fun openAndroidSettings(): Boolean {
        androidSettingsOpened += 1
        return true
    }

    override fun openDisplaySettings(): Boolean {
        displaySettingsOpened += 1
        return true
    }

    override fun startNuvioWithAction(action: ButtonAction): Boolean {
        nuvioIntentActions.add(action)
        return true
    }
}

class ButtonActionExecutorTest {

    @Test
    fun `foreground Nuvio navigation and profile switcher actions execute directly when attached`() {
        val bridge = FakePlatformBridge()
        val executor = ButtonActionExecutor(bridge)
        val foregroundActions = mutableListOf<ButtonAction>()
        val owner = Any()
        executor.attachForegroundHandler(owner) { action ->
            foregroundActions.add(action)
            true
        }

        val destinations = listOf(
            ButtonAction.GoToTopOfHome,
            ButtonAction.OpenHome,
            ButtonAction.OpenSearch,
            ButtonAction.OpenLibrary,
            ButtonAction.OpenSettings,
            ButtonAction.OpenProfileSwitcher,
            ButtonAction.OpenApps,
            ButtonAction.OpenAppDrawer,
            ButtonAction.OpenLiveTv,
            ButtonAction.OpenTvGuide
        )

        destinations.forEach { action ->
            val result = executor.execute(action)
            assertEquals(ActionExecutionResult.ExecutedInForeground(action), result)
        }
        assertEquals(destinations, foregroundActions)
        assertTrue(bridge.nuvioIntentActions.isEmpty())

        // Detaching falls back to launching Nuvio via intent when triggered globally
        executor.detachForegroundHandler(owner)
        val globalResult = executor.execute(ButtonAction.GoToTopOfHome)
        assertEquals(
            ActionExecutionResult.ExecutedViaLauncherIntent(ButtonAction.GoToTopOfHome),
            globalResult
        )
        assertEquals(listOf(ButtonAction.GoToTopOfHome), bridge.nuvioIntentActions)
    }

    @Test
    fun `installed application launches package while uninstalled application is handled safely`() {
        val bridge = FakePlatformBridge(installedPackages = setOf("com.netflix.ninja"))
        val executor = ButtonActionExecutor(bridge)

        val installedResult = executor.execute(
            ButtonAction.LaunchApp(packageName = "com.netflix.ninja", appLabel = "Netflix")
        )
        assertEquals(ActionExecutionResult.LaunchedApp("com.netflix.ninja"), installedResult)
        assertEquals(listOf("com.netflix.ninja"), bridge.launchedPackages)

        val missingResult = executor.execute(
            ButtonAction.LaunchApp(packageName = "com.removed.app", appLabel = "Removed App")
        )
        assertEquals(
            ActionExecutionResult.AppNotInstalled("com.removed.app", "Removed App"),
            missingResult
        )
    }

    @Test
    fun `media actions and Android settings actions dispatch to platform bridge`() {
        val bridge = FakePlatformBridge()
        val executor = ButtonActionExecutor(bridge)

        assertEquals(
            ActionExecutionResult.DispatchedMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            executor.execute(ButtonAction.MediaPlayPause)
        )
        assertEquals(
            ActionExecutionResult.DispatchedMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND),
            executor.execute(ButtonAction.MediaRewind)
        )
        assertEquals(
            ActionExecutionResult.DispatchedMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
            executor.execute(ButtonAction.MediaFastForward)
        )
        assertEquals(
            ActionExecutionResult.DispatchedMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            executor.execute(ButtonAction.MediaPrevious)
        )
        assertEquals(
            ActionExecutionResult.DispatchedMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT),
            executor.execute(ButtonAction.MediaNext)
        )
        assertEquals(
            ActionExecutionResult.DispatchedMediaKey(KeyEvent.KEYCODE_MEDIA_STOP),
            executor.execute(ButtonAction.MediaStop)
        )
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_STOP
            ),
            bridge.dispatchedMediaKeys
        )

        assertEquals(
            ActionExecutionResult.OpenedSystemSettings(Settings.ACTION_SETTINGS),
            executor.execute(ButtonAction.OpenAndroidSettings)
        )
        assertEquals(
            ActionExecutionResult.OpenedSystemSettings(Settings.ACTION_DISPLAY_SETTINGS),
            executor.execute(ButtonAction.OpenDisplaySettings)
        )
        assertEquals(1, bridge.androidSettingsOpened)
        assertEquals(1, bridge.displaySettingsOpened)
        assertEquals(ActionExecutionResult.Ignored, executor.execute(ButtonAction.None))
    }
}
