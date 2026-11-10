package com.nuvio.tv.core.setup

import android.content.Context

/**
 * The Play Store flavor ships neither the on-device ADB setup nor `WRITE_SECURE_SETTINGS`, so there
 * is nothing to explain: the row is hidden and the two Android routes are the whole offer.
 */
internal object LocalAdbSetupFactory {
    fun create(context: Context): LocalAdbSetup? = null

    fun available(context: Context): Boolean = false
}
