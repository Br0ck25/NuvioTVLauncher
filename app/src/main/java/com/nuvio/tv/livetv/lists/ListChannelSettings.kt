package com.nuvio.tv.livetv.lists

import android.content.Context

/** The few switches list channels have. Kept on this device. */
object ListChannelSettings {
    private const val FILE = "list_channel_settings"
    private const val KEY_TORBOX = "torbox_downloads"

    /**
     * Whether a title the viewer's Torbox account does not have may be added to it and waited for
     * (it is only ever done while a channel is being watched). On unless turned off.
     */
    fun torboxDownloads(context: Context): Boolean =
        runCatching { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_TORBOX, true) }.getOrDefault(true)

    fun setTorboxDownloads(context: Context, enabled: Boolean) {
        runCatching { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_TORBOX, enabled).apply() }
    }
}
