package com.nuvio.tv.core.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import com.nuvio.tv.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of executing a [ButtonAction].
 */
sealed interface ActionExecutionResult {
    data object Ignored : ActionExecutionResult
    data class ExecutedInForeground(val action: ButtonAction) : ActionExecutionResult
    data class ExecutedViaLauncherIntent(val action: ButtonAction) : ActionExecutionResult
    data class DispatchedMediaKey(val mediaKeyCode: Int) : ActionExecutionResult
    data class LaunchedApp(val packageName: String) : ActionExecutionResult
    data class AppNotInstalled(val packageName: String, val appLabel: String) : ActionExecutionResult
    data class OpenedSystemSettings(val settingsAction: String) : ActionExecutionResult
    data class Failed(val action: ButtonAction, val reason: String) : ActionExecutionResult
}

/**
 * Callback registered by `MainActivity` while it is in the foreground (`onResume`..`onPause`)
 * so Nuvio navigation, profile switcher, launcher, and Live TV actions execute directly without
 * activity re-launch overhead.
 */
fun interface ForegroundButtonActionHandler {
    fun onExecuteAction(action: ButtonAction): Boolean
    fun onPassthroughBack(): Boolean = false
}

/**
 * Platform operations abstracted for deterministic unit testing.
 */
interface ButtonActionPlatformBridge {
    fun dispatchMediaKey(keyCode: Int): Boolean
    fun launchInstalledPackage(packageName: String, appLabel: String): Boolean
    fun openAndroidSettings(): Boolean
    fun openDisplaySettings(): Boolean
    fun startNuvioWithAction(action: ButtonAction): Boolean
}

/**
 * Android implementation of [ButtonActionPlatformBridge].
 *
 * Media key dispatch (`AudioManager.dispatchMediaKeyEvent`) and Leanback/Launcher package
 * resolution patterns are adapted from tvOS-Launcher (`com.techgenius.tvoslauncher.ButtonMapper`,
 * Apache License 2.0) and integrated with Nuvio's launcher architecture.
 */
class AndroidButtonActionPlatformBridge(
    private val context: Context
) : ButtonActionPlatformBridge {

    override fun dispatchMediaKey(keyCode: Int): Boolean = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        val now = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
        true
    }.getOrDefault(false)

    override fun launchInstalledPackage(packageName: String, appLabel: String): Boolean {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty()) return false
        val pm = context.packageManager
        val intent = runCatching { pm.getLeanbackLaunchIntentForPackage(cleanPkg) }.getOrNull()
            ?: runCatching { pm.getLaunchIntentForPackage(cleanPkg) }.getOrNull()
            ?: runCatching {
                val query = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setPackage(cleanPkg)
                val info = pm.queryIntentActivities(query, 0).firstOrNull()?.activityInfo
                    ?: pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(cleanPkg),
                        0
                    ).firstOrNull()?.activityInfo
                info?.let {
                    Intent(Intent.ACTION_MAIN).apply {
                        setClassName(it.packageName, it.name)
                    }
                }
            }.getOrNull()
        if (intent == null) {
            showMissingAppToast(appLabel.ifBlank { cleanPkg })
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val launched = runCatching {
            context.startActivity(intent)
        }.isSuccess
        if (!launched) {
            showMissingAppToast(appLabel.ifBlank { cleanPkg })
        }
        return launched
    }

    override fun openAndroidSettings(): Boolean {
        val candidates = listOf(
            Settings.ACTION_SETTINGS,
            "android.settings.TV_SETTINGS"
        )
        for (action in candidates) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return true
            }
        }
        return false
    }

    override fun openDisplaySettings(): Boolean {
        val candidates = listOf(
            Settings.ACTION_DISPLAY_SETTINGS,
            "com.android.tv.settings.DISPLAY_SETTINGS",
            Settings.ACTION_SETTINGS
        )
        for (action in candidates) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return true
            }
        }
        return false
    }

    override fun startNuvioWithAction(action: ButtonAction): Boolean = runCatching {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(EXTRA_BUTTON_MAPPER_ACTION, action.storageKey)
            if (action == ButtonAction.GoToTopOfHome) {
                putExtra(LauncherHomeRequest.EXTRA_GO_HOME, true)
                putExtra(LauncherHomeRequest.EXTRA_HOME_PRESS_UPTIME_MS, SystemClock.uptimeMillis())
            } else if (action == ButtonAction.OpenProfileSwitcher) {
                putExtra(LauncherHomeRequest.EXTRA_GO_HOME, true)
                putExtra(LauncherHomeRequest.EXTRA_OPEN_PROFILE_SELECTION, true)
                putExtra(LauncherHomeRequest.EXTRA_HOME_PRESS_UPTIME_MS, SystemClock.uptimeMillis())
            }
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun showMissingAppToast(name: String) {
        runCatching {
            Toast.makeText(
                context.applicationContext,
                "App not installed: $name",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        const val EXTRA_BUTTON_MAPPER_ACTION = "com.nuvio.tv.extra.BUTTON_MAPPER_ACTION"
    }
}

/**
 * Executes resolved [ButtonAction]s both while Nuvio is in the foreground and globally
 * when triggered from [LauncherAccessibilityService].
 */
@Singleton
class ButtonActionExecutor(
    private val platformBridge: ButtonActionPlatformBridge
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        AndroidButtonActionPlatformBridge(context)
    )

    private data class HandlerRegistration(
        val ownerRef: WeakReference<Any>,
        val handler: ForegroundButtonActionHandler
    )

    @Volatile
    private var foregroundRegistration: HandlerRegistration? = null

    /**
     * Action delivered via a launch intent while no foreground handler was attached yet
     * (Activity delivered `onNewIntent` before `onResume`). It is replayed on attach instead of
     * re-launching Nuvio, which would otherwise re-enter `onNewIntent` in an endless loop.
     */
    @Volatile
    private var pendingForegroundAction: ButtonAction? = null

    fun attachForegroundHandler(owner: Any, handler: ForegroundButtonActionHandler) {
        foregroundRegistration = HandlerRegistration(WeakReference(owner), handler)
        val pending = pendingForegroundAction ?: return
        pendingForegroundAction = null
        handler.onExecuteAction(pending)
    }

    fun detachForegroundHandler(owner: Any) {
        val current = foregroundRegistration ?: return
        val currentOwner = current.ownerRef.get()
        if (currentOwner == null || currentOwner === owner) {
            foregroundRegistration = null
        }
    }

    fun isForegroundAttached(): Boolean = foregroundRegistration?.ownerRef?.get() != null

    fun executePassthroughBack(source: ButtonEventSource) {
        val foreground = foregroundRegistration
        val owner = foreground?.ownerRef?.get()
        if (foreground != null && owner != null) {
            if (foreground.handler.onPassthroughBack()) {
                return
            }
        }
        LauncherAccessibilityService.performGlobalBack()
    }

    /**
     * Executes [action] and returns an [ActionExecutionResult] describing what occurred.
     */
    fun execute(action: ButtonAction, fromLaunchIntent: Boolean = false): ActionExecutionResult {
        return when (action) {
            ButtonAction.None,
            ButtonAction.RestoreDefault -> ActionExecutionResult.Ignored

            ButtonAction.GoToTopOfHome,
            ButtonAction.OpenHome,
            ButtonAction.OpenSearch,
            ButtonAction.OpenLibrary,
            ButtonAction.OpenSettings,
            ButtonAction.OpenProfileSwitcher,
            ButtonAction.OpenApps,
            ButtonAction.OpenAppDrawer,
            ButtonAction.OpenLiveTv,
            ButtonAction.OpenTvGuide -> executeNuvioDestinationAction(action, fromLaunchIntent)

            ButtonAction.MediaPlayPause -> dispatchMedia(action, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ButtonAction.MediaRewind -> dispatchMedia(action, KeyEvent.KEYCODE_MEDIA_REWIND)
            ButtonAction.MediaFastForward -> dispatchMedia(action, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            ButtonAction.MediaPrevious -> dispatchMedia(action, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ButtonAction.MediaNext -> dispatchMedia(action, KeyEvent.KEYCODE_MEDIA_NEXT)
            ButtonAction.MediaStop -> dispatchMedia(action, KeyEvent.KEYCODE_MEDIA_STOP)

            is ButtonAction.LaunchApp -> {
                val launched = platformBridge.launchInstalledPackage(action.packageName, action.appLabel)
                if (launched) {
                    ActionExecutionResult.LaunchedApp(action.packageName)
                } else {
                    ActionExecutionResult.AppNotInstalled(action.packageName, action.appLabel)
                }
            }

            ButtonAction.OpenAndroidSettings -> {
                if (platformBridge.openAndroidSettings()) {
                    ActionExecutionResult.OpenedSystemSettings(Settings.ACTION_SETTINGS)
                } else {
                    ActionExecutionResult.Failed(action, "Unable to open Android Settings")
                }
            }

            ButtonAction.OpenDisplaySettings -> {
                if (platformBridge.openDisplaySettings()) {
                    ActionExecutionResult.OpenedSystemSettings(Settings.ACTION_DISPLAY_SETTINGS)
                } else {
                    ActionExecutionResult.Failed(action, "Unable to open Display Settings")
                }
            }
        }
    }

    private fun executeNuvioDestinationAction(
        action: ButtonAction,
        fromLaunchIntent: Boolean
    ): ActionExecutionResult {
        val foreground = foregroundRegistration
        val owner = foreground?.ownerRef?.get()
        if (foreground != null && owner != null) {
            if (foreground.handler.onExecuteAction(action)) {
                return ActionExecutionResult.ExecutedInForeground(action)
            }
        }
        if (fromLaunchIntent) {
            // We are already running because of a launch intent: never fire another one.
            pendingForegroundAction = action
            return ActionExecutionResult.Ignored
        }
        return if (platformBridge.startNuvioWithAction(action)) {
            ActionExecutionResult.ExecutedViaLauncherIntent(action)
        } else {
            ActionExecutionResult.Failed(action, "Unable to launch Nuvio destination")
        }
    }

    private fun dispatchMedia(action: ButtonAction, mediaKeyCode: Int): ActionExecutionResult {
        return if (platformBridge.dispatchMediaKey(mediaKeyCode)) {
            ActionExecutionResult.DispatchedMediaKey(mediaKeyCode)
        } else {
            ActionExecutionResult.Failed(action, "AudioManager unavailable for media key $mediaKeyCode")
        }
    }
}
