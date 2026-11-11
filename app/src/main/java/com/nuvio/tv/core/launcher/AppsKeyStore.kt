package com.nuvio.tv.core.launcher

import android.content.Context
import android.view.KeyEvent

/**
 * Which remote button opens the Apps page. The standard "all apps" key always does; the user can also
 * teach the app a different button (Settings -> Default launcher -> Remote Apps button), because
 * remotes disagree about what their Apps button sends.
 */
object AppsKeyStore {
    private const val FILE = "launcher_apps"
    private const val KEY = "apps_key"

    /** Buttons that must never be taken over: moving around, selecting, leaving, volume and power. */
    private val reserved = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_UNKNOWN
    )

    fun isAllowed(keyCode: Int): Boolean = keyCode !in reserved && keyCode > 0

    /** The button the user chose, or 0 for none. */
    fun chosen(context: Context): Int =
        runCatching { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY, 0) }.getOrDefault(0)

    fun set(context: Context, keyCode: Int): Boolean {
        if (!isAllowed(keyCode)) return false
        runCatching { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY, keyCode).apply() }
        return true
    }

    fun clear(context: Context) {
        runCatching { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply() }
    }

    /** True for the standard all-apps key or the button the user chose. */
    fun matches(context: Context, keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_ALL_APPS || (keyCode > 0 && keyCode == chosen(context) && isAllowed(keyCode))

    fun describe(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
}
