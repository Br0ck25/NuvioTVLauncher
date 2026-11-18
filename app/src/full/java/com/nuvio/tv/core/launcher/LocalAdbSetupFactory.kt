package com.nuvio.tv.core.launcher

import android.content.Context
import android.os.Build

internal object LocalAdbSetupFactory {
    /** Wireless debugging pairing exists from Android 11 (API 30). */
    fun create(context: Context): LocalAdbSetup? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) LoopbackAdbSetup(context.applicationContext) else null
}
