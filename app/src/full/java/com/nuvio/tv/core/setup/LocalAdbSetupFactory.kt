package com.nuvio.tv.core.setup

import android.content.Context
import android.os.Build

internal object LocalAdbSetupFactory {
    /** Wireless debugging pairing exists from Android 11 (API 30). */
    fun create(context: Context): LocalAdbSetup? =
        if (available(context)) LoopbackAdbSetup(context.applicationContext) else null

    /** False only because of the Android release, so the UI can say why instead of hiding the row. */
    fun available(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
