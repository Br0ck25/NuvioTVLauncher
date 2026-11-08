package com.nuvio.tv.core.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.nuvio.tv.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LauncherAccessibilityService : AccessibilityService() {
    private var delivery = HomeFallbackPolicy()
    private var buttonKeyEventHandler: ButtonKeyEventHandler? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeServiceRef = java.lang.ref.WeakReference(this)
        delivery = HomeFallbackPolicy()
        buttonKeyEventHandler = ButtonKeyEventHandler.fromContext(this)
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            LauncherSetup.observeNativeHome(this, LauncherSetup.isDefaultHome(this))
            operational.value = true
            openAfterBoot()
        } catch (e: Exception) {
            operational.value = false
            Log.e(TAG, "Error configuring AccessibilityServiceInfo", e)
        }
    }

    /**
     * After a power cycle the firmware's own launcher comes up. When Nuvio is only the accessibility
     * fallback (not the system's default launcher), bring Nuvio to the front once the service is up
     * shortly after boot, as a default launcher would be.
     */
    private fun openAfterBoot() {
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
        if (LauncherSetup.isDefaultHome(this) || !LauncherSetup.accessibilityFallbackAllowed(this)) return
        Handler(Looper.getMainLooper()).postDelayed({ launchHome(SystemClock.uptimeMillis()) }, BOOT_OPEN_DELAY_MS)
    }

    private fun resolveKeyEventHandler(): ButtonKeyEventHandler? {
        buttonKeyEventHandler?.let { return it }
        return ButtonKeyEventHandler.fromContext(this).also { buttonKeyEventHandler = it }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val handler = resolveKeyEventHandler()
        val nativeHome = LauncherSetup.isDefaultHome(this)
        val isForeground = HomeForegroundDelivery.isResumed
        val effectiveSource = if (isForeground) ButtonEventSource.FOREGROUND else ButtonEventSource.GLOBAL

        // 1. If the user is actively capturing a remote button in Settings, offer the event first.
        if (handler != null && handler.captureController.isCapturingOrReleasing(event.keyCode, event.action)) {
            if (handler.onKeyEvent(
                    event = event,
                    source = effectiveSource,
                    accessibilityEnabled = true,
                    nativeHomeActive = nativeHome
                )
            ) {
                return true
            }
        }

        // 2. Never intercept system-reserved keys (Volume, Power, D-pad, Enter).
        if (!RemoteButtonCatalog.isMappableKey(event.keyCode)) {
            return super.onKeyEvent(event)
        }

        // 3. Record global key capability in memory when observed.
        if (handler != null &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) &&
            event.repeatCount == 0
        ) {
            handler.repository.recordKeyObserved(event.keyCode, isGlobal = true)
        }

        // 4. Home button handling: preserve existing native HOME / fallback behavior by default,
        // while honoring custom Home mappings or disabled Home mappings when configured.
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            LauncherSetup.observeNativeHome(this, nativeHome)
            val homeMapping = handler?.repository?.activeMappingFor(KeyEvent.KEYCODE_HOME, isGlobal = !isForeground)
            if (handler != null && homeMapping == null) {
                // User explicitly disabled or removed the Home mapping for this context.
                return super.onKeyEvent(event)
            }
            if (handler != null && homeMapping != null && !homeMapping.matchesFactoryHomeDefaults()) {
                val handled = handler.onKeyEvent(
                    event = event,
                    source = effectiveSource,
                    accessibilityEnabled = true,
                    nativeHomeActive = nativeHome
                )
                if (handled) {
                    delivery.onLaunch(SystemClock.uptimeMillis())
                    return true
                }
            }
            if (nativeHome || !LauncherSetup.accessibilityFallbackAllowed(this)) {
                // Native HOME is preferred. In particular, don't fight a working HOME role.
                return super.onKeyEvent(event)
            }
            // Consume the whole sequence, but only forward uncanceled releases, never held-key DOWNs.
            if (delivery.acceptKeyRelease(event.eventTime, event.action == KeyEvent.ACTION_UP, event.isCanceled)) {
                launchHome(event.eventTime)
            }
            return true
        }

        // 5. Non-Home mapped buttons: intercept keys with enabled mappings for the active context
        // (foreground when Nuvio is resumed, global when another app is active) so Android TV's
        // PhoneWindowManager cannot hijack shortcut, media, search, guide, or settings keys before
        // MainActivity sees them.
        if (handler == null) return super.onKeyEvent(event)
        handler.repository.activeMappingFor(event.keyCode, isGlobal = !isForeground)
            ?: return super.onKeyEvent(event)

        val consumed = handler.onKeyEvent(
            event = event,
            source = effectiveSource,
            accessibilityEnabled = true,
            nativeHomeActive = nativeHome
        )
        return if (consumed) true else super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        if (pkg == packageName) {
            delivery.onOtherApplicationWindow()
            return
        }
        val capabilities = LauncherCapabilities.detect(this)
        LauncherSetup.observeNativeHome(this, capabilities.isDefaultHome)
        if (capabilities.isDefaultHome || !capabilities.accessibilityFallbackAllowed || HomeForegroundDelivery.isResumed) return
        val component = ComponentName(pkg, className)
        if (component in capabilities.selectedHomeActivities) {
            // A launcher activity transition is only a last resort when firmware withholds HOME
            // keys. Dialogs, SystemUI and arbitrary windows in the launcher package are not HOME.
            val now = SystemClock.uptimeMillis()
            if (delivery.acceptLauncherWindow(now)) launchHome(now)
        } else if (isApplicationActivity(pkg, className)) {
            // Do not rearm on transient system panels/dialogs; they can accompany the same HOME.
            delivery.onOtherApplicationWindow()
        }
    }

    private fun isApplicationActivity(pkg: String, className: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getActivityInfo(ComponentName(pkg, className), 0)
        true
    }.getOrDefault(false)

    // Interrupt means feedback was interrupted, not that Android unbound this service.
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        operational.value = false
        if (activeServiceRef?.get() === this) {
            activeServiceRef = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        operational.value = false
        if (activeServiceRef?.get() === this) {
            activeServiceRef = null
        }
        super.onDestroy()
    }

    private fun launchHome(pressedAtMs: Long) {
        try {
            if (!HomeForegroundDelivery.deliver(pressedAtMs)) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra(LauncherHomeRequest.EXTRA_GO_HOME, true)
                    putExtra(LauncherHomeRequest.EXTRA_HOME_PRESS_UPTIME_MS, pressedAtMs)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
            delivery.onLaunch(SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch home activity", e)
        }
    }

    companion object {
        private const val TAG = "LauncherAccessibility"
        private const val BOOT_WINDOW_MS = 5 * 60 * 1000L
        private const val BOOT_OPEN_DELAY_MS = 4_000L
        private val operational = MutableStateFlow(false)
        @Volatile
        private var activeServiceRef: java.lang.ref.WeakReference<LauncherAccessibilityService>? = null

        /** Live binding/configuration, not proof this firmware actually delivers HOME keys. */
        internal val isOperational = operational.asStateFlow()

        fun performGlobalBack(): Boolean = runCatching {
            activeServiceRef?.get()?.performGlobalAction(GLOBAL_ACTION_BACK) == true
        }.getOrDefault(false)

        internal fun containsEnabledComponent(enabledServices: String?, target: ComponentName): Boolean =
            enabledServices?.split(':')?.any { ComponentName.unflattenFromString(it) == target } == true

        fun isServiceEnabled(context: Context): Boolean = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
                containsEnabledComponent(
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    ComponentName(context, LauncherAccessibilityService::class.java)
                )
        }.getOrDefault(false)

        fun openAccessibilitySettings(context: Context) {
            // User-initiated opt-in only; this does not grant or enable Accessibility authorization.
            LauncherSetup.allowAccessibilityFallback(context)
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
