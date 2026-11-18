package com.nuvio.tv.core.launcher

import android.view.KeyEvent

/**
 * Press gesture types supported for each physical remote button mapping.
 */
enum class ButtonPressType {
    SINGLE_PRESS,
    DOUBLE_PRESS,
    LONG_PRESS
}

/**
 * Execution scope for a configured button mapping.
 */
enum class ButtonMappingScope {
    FOREGROUND_AND_GLOBAL,
    FOREGROUND_ONLY,
    GLOBAL_ONLY;

    fun allowsForeground(): Boolean = this == FOREGROUND_AND_GLOBAL || this == FOREGROUND_ONLY
    fun allowsGlobal(): Boolean = this == FOREGROUND_AND_GLOBAL || this == GLOBAL_ONLY
}

/**
 * Per-button capability status based on system policy and actual observed key events.
 */
enum class ButtonCapabilityStatus {
    UNKNOWN,
    AVAILABLE_IN_NUVIO,
    AVAILABLE_GLOBALLY,
    UNAVAILABLE,
    SYSTEM_RESERVED
}

/**
 * High-level remote button categories.
 */
enum class RemoteButtonCategory {
    NAVIGATION,
    MEDIA,
    APP_SHORTCUT,
    TV_CONTROL,
    COLORED,
    OTHER
}

/**
 * Action categories shown in the Button Mapper action picker.
 */
enum class ButtonActionCategory {
    NUVIO_NAVIGATION,
    PROFILES,
    LAUNCHER,
    LIVE_TV,
    MEDIA,
    APPLICATIONS,
    ANDROID,
    OTHER
}

/**
 * Centralized timing thresholds for remote press detection, deduplication, and capture.
 */
data class ButtonMapperTimingConfig(
    val minDuplicateGapMs: Long = DEFAULT_MIN_DUPLICATE_GAP_MS,
    val doubleClickWindowMs: Long = DEFAULT_DOUBLE_CLICK_WINDOW_MS,
    val longPressThresholdMs: Long = DEFAULT_LONG_PRESS_THRESHOLD_MS,
    val captureTimeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_MIN_DUPLICATE_GAP_MS = 120L
        const val DEFAULT_DOUBLE_CLICK_WINDOW_MS = 450L
        const val DEFAULT_LONG_PRESS_THRESHOLD_MS = 600L
        const val DEFAULT_CAPTURE_TIMEOUT_MS = 10_000L
    }
}

/**
 * Actions that can be assigned to single-click, double-click, or long-press of a remote button.
 */
sealed interface ButtonAction {
    val storageKey: String
    val category: ButtonActionCategory

    // Other
    data object None : ButtonAction {
        override val storageKey: String = "NONE"
        override val category: ButtonActionCategory = ButtonActionCategory.OTHER
    }

    data object RestoreDefault : ButtonAction {
        override val storageKey: String = "RESTORE_DEFAULT"
        override val category: ButtonActionCategory = ButtonActionCategory.OTHER
    }

    // Nuvio Navigation
    data object GoToTopOfHome : ButtonAction {
        override val storageKey: String = "GO_TO_TOP_OF_HOME"
        override val category: ButtonActionCategory = ButtonActionCategory.NUVIO_NAVIGATION
    }

    data object OpenHome : ButtonAction {
        override val storageKey: String = "OPEN_HOME"
        override val category: ButtonActionCategory = ButtonActionCategory.NUVIO_NAVIGATION
    }

    data object OpenSearch : ButtonAction {
        override val storageKey: String = "OPEN_SEARCH"
        override val category: ButtonActionCategory = ButtonActionCategory.NUVIO_NAVIGATION
    }

    data object OpenLibrary : ButtonAction {
        override val storageKey: String = "OPEN_LIBRARY"
        override val category: ButtonActionCategory = ButtonActionCategory.NUVIO_NAVIGATION
    }

    data object OpenSettings : ButtonAction {
        override val storageKey: String = "OPEN_SETTINGS"
        override val category: ButtonActionCategory = ButtonActionCategory.NUVIO_NAVIGATION
    }

    // Profiles
    data object OpenProfileSwitcher : ButtonAction {
        override val storageKey: String = "OPEN_PROFILE_SWITCHER"
        override val category: ButtonActionCategory = ButtonActionCategory.PROFILES
    }

    // Launcher
    data object OpenApps : ButtonAction {
        override val storageKey: String = "OPEN_APPS"
        override val category: ButtonActionCategory = ButtonActionCategory.LAUNCHER
    }

    data object OpenAppDrawer : ButtonAction {
        override val storageKey: String = "OPEN_APP_DRAWER"
        override val category: ButtonActionCategory = ButtonActionCategory.LAUNCHER
    }

    // Live TV
    data object OpenLiveTv : ButtonAction {
        override val storageKey: String = "OPEN_LIVE_TV"
        override val category: ButtonActionCategory = ButtonActionCategory.LIVE_TV
    }

    data object OpenTvGuide : ButtonAction {
        override val storageKey: String = "OPEN_TV_GUIDE"
        override val category: ButtonActionCategory = ButtonActionCategory.LIVE_TV
    }

    // Media
    data object MediaPlayPause : ButtonAction {
        override val storageKey: String = "MEDIA_PLAY_PAUSE"
        override val category: ButtonActionCategory = ButtonActionCategory.MEDIA
    }

    data object MediaRewind : ButtonAction {
        override val storageKey: String = "MEDIA_REWIND"
        override val category: ButtonActionCategory = ButtonActionCategory.MEDIA
    }

    data object MediaFastForward : ButtonAction {
        override val storageKey: String = "MEDIA_FAST_FORWARD"
        override val category: ButtonActionCategory = ButtonActionCategory.MEDIA
    }

    data object MediaPrevious : ButtonAction {
        override val storageKey: String = "MEDIA_PREVIOUS"
        override val category: ButtonActionCategory = ButtonActionCategory.MEDIA
    }

    data object MediaNext : ButtonAction {
        override val storageKey: String = "MEDIA_NEXT"
        override val category: ButtonActionCategory = ButtonActionCategory.MEDIA
    }

    data object MediaStop : ButtonAction {
        override val storageKey: String = "MEDIA_STOP"
        override val category: ButtonActionCategory = ButtonActionCategory.MEDIA
    }

    // Applications
    data class LaunchApp(
        val packageName: String,
        val appLabel: String = ""
    ) : ButtonAction {
        override val storageKey: String = "$LAUNCH_APP_PREFIX$packageName|$appLabel"
        override val category: ButtonActionCategory = ButtonActionCategory.APPLICATIONS
    }

    // Android
    data object OpenAndroidSettings : ButtonAction {
        override val storageKey: String = "OPEN_ANDROID_SETTINGS"
        override val category: ButtonActionCategory = ButtonActionCategory.ANDROID
    }

    data object OpenDisplaySettings : ButtonAction {
        override val storageKey: String = "OPEN_DISPLAY_SETTINGS"
        override val category: ButtonActionCategory = ButtonActionCategory.ANDROID
    }

    companion object {
        private const val LAUNCH_APP_PREFIX = "LAUNCH_APP:"

        fun fromStorageKey(raw: String?): ButtonAction {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty() || value == None.storageKey) return None
            if (value.startsWith(LAUNCH_APP_PREFIX)) {
                val payload = value.removePrefix(LAUNCH_APP_PREFIX)
                val sepIndex = payload.indexOf('|')
                val pkg = if (sepIndex >= 0) payload.substring(0, sepIndex).trim() else payload.trim()
                val label = if (sepIndex >= 0) payload.substring(sepIndex + 1).trim() else ""
                return if (pkg.isNotEmpty()) LaunchApp(packageName = pkg, appLabel = label) else None
            }
            return when (value) {
                RestoreDefault.storageKey -> RestoreDefault
                GoToTopOfHome.storageKey -> GoToTopOfHome
                OpenHome.storageKey -> OpenHome
                OpenSearch.storageKey -> OpenSearch
                OpenLibrary.storageKey -> OpenLibrary
                OpenSettings.storageKey -> OpenSettings
                OpenProfileSwitcher.storageKey -> OpenProfileSwitcher
                OpenApps.storageKey -> OpenApps
                OpenAppDrawer.storageKey -> OpenAppDrawer
                OpenLiveTv.storageKey -> OpenLiveTv
                OpenTvGuide.storageKey -> OpenTvGuide
                MediaPlayPause.storageKey -> MediaPlayPause
                MediaRewind.storageKey -> MediaRewind
                MediaFastForward.storageKey -> MediaFastForward
                MediaPrevious.storageKey -> MediaPrevious
                MediaNext.storageKey -> MediaNext
                MediaStop.storageKey -> MediaStop
                OpenAndroidSettings.storageKey -> OpenAndroidSettings
                OpenDisplaySettings.storageKey -> OpenDisplaySettings
                else -> None
            }
        }
    }
}

/**
 * Complete mapping configuration for a single physical remote button.
 */
data class ButtonMapping(
    val keyCode: Int,
    val displayName: String,
    val singleAction: ButtonAction = ButtonAction.None,
    val doubleAction: ButtonAction = ButtonAction.None,
    val longAction: ButtonAction = ButtonAction.None,
    val enabled: Boolean = true,
    val scope: ButtonMappingScope = ButtonMappingScope.FOREGROUND_AND_GLOBAL
) {
    fun actionFor(pressType: ButtonPressType): ButtonAction = when (pressType) {
        ButtonPressType.SINGLE_PRESS -> singleAction
        ButtonPressType.DOUBLE_PRESS -> doubleAction
        ButtonPressType.LONG_PRESS -> longAction
    }

    fun withAction(pressType: ButtonPressType, action: ButtonAction): ButtonMapping = when (pressType) {
        ButtonPressType.SINGLE_PRESS -> copy(singleAction = action)
        ButtonPressType.DOUBLE_PRESS -> copy(doubleAction = action)
        ButtonPressType.LONG_PRESS -> copy(longAction = action)
    }

    fun hasAnyAssignedAction(): Boolean =
        singleAction != ButtonAction.None ||
            doubleAction != ButtonAction.None ||
            longAction != ButtonAction.None

    fun isHomeButton(): Boolean = keyCode == KeyEvent.KEYCODE_HOME

    fun matchesFactoryHomeDefaults(): Boolean =
        keyCode == KeyEvent.KEYCODE_HOME &&
            singleAction == ButtonAction.GoToTopOfHome &&
            doubleAction == ButtonAction.OpenProfileSwitcher &&
            longAction == ButtonAction.None &&
            enabled

    companion object {
        fun defaultHomeMapping(): ButtonMapping = ButtonMapping(
            keyCode = KeyEvent.KEYCODE_HOME,
            displayName = "Home",
            singleAction = ButtonAction.GoToTopOfHome,
            doubleAction = ButtonAction.OpenProfileSwitcher,
            longAction = ButtonAction.None,
            enabled = true,
            scope = ButtonMappingScope.FOREGROUND_AND_GLOBAL
        )

        fun defaultActionFor(keyCode: Int, pressType: ButtonPressType): ButtonAction {
            if (keyCode == KeyEvent.KEYCODE_HOME) {
                return when (pressType) {
                    ButtonPressType.SINGLE_PRESS -> ButtonAction.GoToTopOfHome
                    ButtonPressType.DOUBLE_PRESS -> ButtonAction.OpenProfileSwitcher
                    ButtonPressType.LONG_PRESS -> ButtonAction.None
                }
            }
            return ButtonAction.None
        }
    }
}

/**
 * Descriptor for a remote button in [RemoteButtonCatalog].
 */
data class RemoteButtonDescriptor(
    val keyCode: Int,
    val displayName: String,
    val category: RemoteButtonCategory,
    val isSystemReserved: Boolean = false,
    val note: String? = null
)

/**
 * Metadata and classification for Android TV remote buttons.
 *
 * Portions of the keycode label table are adapted from tvOS-Launcher
 * (`com.techgenius.tvoslauncher.ButtonMapper`, Apache License 2.0) and extended for Nuvio.
 */
object RemoteButtonCatalog {
    // Android 12 (API 31) dedicated TV remote app shortcut keycodes
    const val KEYCODE_VIDEO_APP_1 = 289
    const val KEYCODE_VIDEO_APP_2 = 290
    const val KEYCODE_VIDEO_APP_3 = 291
    const val KEYCODE_VIDEO_APP_4 = 292
    const val KEYCODE_VIDEO_APP_5 = 293
    const val KEYCODE_VIDEO_APP_6 = 294
    const val KEYCODE_VIDEO_APP_7 = 295
    const val KEYCODE_VIDEO_APP_8 = 296
    const val KEYCODE_FEATURED_APP_1 = 297
    const val KEYCODE_FEATURED_APP_2 = 298
    const val KEYCODE_FEATURED_APP_3 = 299
    const val KEYCODE_FEATURED_APP_4 = 300
    const val KEYCODE_DEMO_APP_1 = 301
    const val KEYCODE_DEMO_APP_2 = 302
    const val KEYCODE_DEMO_APP_3 = 303
    const val KEYCODE_DEMO_APP_4 = 304

    private val systemReservedKeyCodes: Set<Int> = setOf(
        // Volume controls must remain system-controlled
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        // Power controls must remain system-controlled
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_TV_POWER,
        KeyEvent.KEYCODE_STB_POWER,
        KeyEvent.KEYCODE_AVR_POWER,
        // D-pad & OK/Enter must never be intercepted so remote UI navigation cannot break
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_DPAD_UP_LEFT,
        KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
        KeyEvent.KEYCODE_DPAD_UP_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER
    )

    private val navigationFocusedKeys: Set<Int> = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_DPAD_UP_LEFT,
        KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
        KeyEvent.KEYCODE_DPAD_UP_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER
    )

    val knownButtons: List<RemoteButtonDescriptor> = listOf(
        // Navigation
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_HOME,
            displayName = "Home",
            category = RemoteButtonCategory.NAVIGATION
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_BACK,
            displayName = "Back",
            category = RemoteButtonCategory.NAVIGATION
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_MENU,
            displayName = "Menu",
            category = RemoteButtonCategory.NAVIGATION
        ),

        // Media
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            displayName = "Play / Pause",
            category = RemoteButtonCategory.MEDIA
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY,
            displayName = "Play",
            category = RemoteButtonCategory.MEDIA
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE,
            displayName = "Pause",
            category = RemoteButtonCategory.MEDIA
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_MEDIA_STOP,
            displayName = "Stop",
            category = RemoteButtonCategory.MEDIA
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_MEDIA_REWIND,
            displayName = "Rewind",
            category = RemoteButtonCategory.MEDIA
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            displayName = "Fast Forward",
            category = RemoteButtonCategory.MEDIA
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            displayName = "Previous",
            category = RemoteButtonCategory.MEDIA
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
            displayName = "Next",
            category = RemoteButtonCategory.MEDIA
        ),

        // App Shortcut Buttons
        RemoteButtonDescriptor(
            keyCode = KEYCODE_VIDEO_APP_1,
            displayName = "Netflix",
            category = RemoteButtonCategory.APP_SHORTCUT,
            note = "Dedicated app shortcut button (Video App 1)"
        ),
        RemoteButtonDescriptor(
            keyCode = KEYCODE_VIDEO_APP_2,
            displayName = "YouTube",
            category = RemoteButtonCategory.APP_SHORTCUT,
            note = "Dedicated app shortcut button (Video App 2)"
        ),
        RemoteButtonDescriptor(
            keyCode = KEYCODE_VIDEO_APP_3,
            displayName = "Prime Video",
            category = RemoteButtonCategory.APP_SHORTCUT,
            note = "Dedicated app shortcut button (Video App 3)"
        ),
        RemoteButtonDescriptor(
            keyCode = KEYCODE_VIDEO_APP_4,
            displayName = "Disney+",
            category = RemoteButtonCategory.APP_SHORTCUT,
            note = "Dedicated app shortcut button (Video App 4)"
        ),
        RemoteButtonDescriptor(
            keyCode = KEYCODE_FEATURED_APP_1,
            displayName = "Featured App 1",
            category = RemoteButtonCategory.APP_SHORTCUT
        ),
        RemoteButtonDescriptor(
            keyCode = KEYCODE_FEATURED_APP_2,
            displayName = "Featured App 2",
            category = RemoteButtonCategory.APP_SHORTCUT
        ),
        RemoteButtonDescriptor(
            keyCode = KEYCODE_FEATURED_APP_3,
            displayName = "Featured App 3",
            category = RemoteButtonCategory.APP_SHORTCUT
        ),
        RemoteButtonDescriptor(
            keyCode = KEYCODE_FEATURED_APP_4,
            displayName = "Featured App 4",
            category = RemoteButtonCategory.APP_SHORTCUT
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_BUTTON_1,
            displayName = "App Shortcut 1 (Button 1)",
            category = RemoteButtonCategory.APP_SHORTCUT
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_BUTTON_2,
            displayName = "App Shortcut 2 (Button 2)",
            category = RemoteButtonCategory.APP_SHORTCUT
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_BUTTON_3,
            displayName = "App Shortcut 3 (Button 3)",
            category = RemoteButtonCategory.APP_SHORTCUT
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_BUTTON_4,
            displayName = "App Shortcut 4 (Button 4)",
            category = RemoteButtonCategory.APP_SHORTCUT
        ),

        // TV Controls
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_GUIDE,
            displayName = "Guide",
            category = RemoteButtonCategory.TV_CONTROL
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_TV,
            displayName = "TV",
            category = RemoteButtonCategory.TV_CONTROL
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_TV_INPUT,
            displayName = "TV Input",
            category = RemoteButtonCategory.TV_CONTROL
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_INFO,
            displayName = "Info",
            category = RemoteButtonCategory.TV_CONTROL
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_CAPTIONS,
            displayName = "Captions",
            category = RemoteButtonCategory.TV_CONTROL
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_CHANNEL_UP,
            displayName = "Channel Up",
            category = RemoteButtonCategory.TV_CONTROL
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_CHANNEL_DOWN,
            displayName = "Channel Down",
            category = RemoteButtonCategory.TV_CONTROL
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_DVR,
            displayName = "DVR",
            category = RemoteButtonCategory.TV_CONTROL
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_BOOKMARK,
            displayName = "Bookmark",
            category = RemoteButtonCategory.TV_CONTROL
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_LAST_CHANNEL,
            displayName = "Last Channel",
            category = RemoteButtonCategory.TV_CONTROL
        ),

        // Colored Buttons
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_PROG_RED,
            displayName = "Red",
            category = RemoteButtonCategory.COLORED
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_PROG_GREEN,
            displayName = "Green",
            category = RemoteButtonCategory.COLORED
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_PROG_YELLOW,
            displayName = "Yellow",
            category = RemoteButtonCategory.COLORED
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_PROG_BLUE,
            displayName = "Blue",
            category = RemoteButtonCategory.COLORED
        ),

        // Other
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_VOICE_ASSIST,
            displayName = "Assistant / Voice",
            category = RemoteButtonCategory.OTHER,
            note = "May be reserved by Android TV Google Assistant on some devices"
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_SEARCH,
            displayName = "Search",
            category = RemoteButtonCategory.OTHER
        ),
        RemoteButtonDescriptor(
            keyCode = KeyEvent.KEYCODE_SETTINGS,
            displayName = "Settings",
            category = RemoteButtonCategory.OTHER
        )
    )

    private val descriptorsByKeyCode: Map<Int, RemoteButtonDescriptor> =
        knownButtons.associateBy { it.keyCode }

    fun isSystemReservedKey(keyCode: Int): Boolean =
        keyCode <= 0 || keyCode in systemReservedKeyCodes

    fun isDpadOrEnterKey(keyCode: Int): Boolean =
        keyCode in navigationFocusedKeys

    fun isMappableKey(keyCode: Int): Boolean =
        keyCode > 0 && !isSystemReservedKey(keyCode)

    fun descriptorFor(keyCode: Int): RemoteButtonDescriptor {
        descriptorsByKeyCode[keyCode]?.let { return it }

        if (isSystemReservedKey(keyCode)) {
            return RemoteButtonDescriptor(
                keyCode = keyCode,
                displayName = systemReservedDisplayName(keyCode),
                category = RemoteButtonCategory.OTHER,
                isSystemReserved = true,
                note = "System-reserved control (Volume, Power, or D-pad navigation)"
            )
        }

        val category = when (keyCode) {
            in KEYCODE_VIDEO_APP_1..KEYCODE_DEMO_APP_4,
            in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16,
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3,
            KeyEvent.KEYCODE_EXPLORER,
            KeyEvent.KEYCODE_ENVELOPE -> RemoteButtonCategory.APP_SHORTCUT

            KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_MEDIA_EJECT,
            KeyEvent.KEYCODE_MEDIA_CLOSE,
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> RemoteButtonCategory.MEDIA

            KeyEvent.KEYCODE_TV_DATA_SERVICE,
            KeyEvent.KEYCODE_TV_RADIO_SERVICE,
            KeyEvent.KEYCODE_TV_TELETEXT,
            KeyEvent.KEYCODE_TV_NUMBER_ENTRY,
            KeyEvent.KEYCODE_TV_TERRESTRIAL_ANALOG,
            KeyEvent.KEYCODE_TV_TERRESTRIAL_DIGITAL,
            KeyEvent.KEYCODE_TV_SATELLITE,
            KeyEvent.KEYCODE_TV_ANTENNA_CABLE,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU,
            KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU,
            KeyEvent.KEYCODE_TV_TIMER_PROGRAMMING -> RemoteButtonCategory.TV_CONTROL

            else -> RemoteButtonCategory.OTHER
        }

        return RemoteButtonDescriptor(
            keyCode = keyCode,
            displayName = fallbackLabelForKeyCode(keyCode),
            category = category,
            isSystemReserved = false
        )
    }

    fun labelForKeyCode(keyCode: Int): String = descriptorFor(keyCode).displayName

    private fun systemReservedDisplayName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> "Volume Up"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume Down"
        KeyEvent.KEYCODE_VOLUME_MUTE -> "Mute"
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_TV_POWER,
        KeyEvent.KEYCODE_STB_POWER,
        KeyEvent.KEYCODE_AVR_POWER -> "Power"
        KeyEvent.KEYCODE_SLEEP -> "Sleep"
        KeyEvent.KEYCODE_WAKEUP -> "Wake Up"
        KeyEvent.KEYCODE_DPAD_UP -> "D-Pad Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "D-Pad Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "D-Pad Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Pad Right"
        KeyEvent.KEYCODE_DPAD_CENTER -> "D-Pad Center / OK"
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        else -> fallbackLabelForKeyCode(keyCode)
    }

    private fun fallbackLabelForKeyCode(keyCode: Int): String {
        when (keyCode) {
            in KEYCODE_VIDEO_APP_5..KEYCODE_VIDEO_APP_8 ->
                return "Video App ${keyCode - KEYCODE_VIDEO_APP_1 + 1}"
            in KEYCODE_DEMO_APP_1..KEYCODE_DEMO_APP_4 ->
                return "Demo App ${keyCode - KEYCODE_DEMO_APP_1 + 1}"
            in KeyEvent.KEYCODE_BUTTON_5..KeyEvent.KEYCODE_BUTTON_16 ->
                return "App Shortcut ${keyCode - KeyEvent.KEYCODE_BUTTON_1 + 1}"
            KeyEvent.KEYCODE_STEM_1 -> return "Shortcut Stem 1"
            KeyEvent.KEYCODE_STEM_2 -> return "Shortcut Stem 2"
            KeyEvent.KEYCODE_STEM_3 -> return "Shortcut Stem 3"
        }
        val raw = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrNull()
        if (raw.isNullOrBlank()) return "Key $keyCode"
        return raw.removePrefix("KEYCODE_")
            .lowercase()
            .split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch -> ch.uppercaseChar() }
            }
            .ifBlank { "Key $keyCode" }
    }
}
