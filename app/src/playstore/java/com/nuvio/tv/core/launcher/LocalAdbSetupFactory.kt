package com.nuvio.tv.core.launcher

import android.content.Context

/** The Play Store flavor does not ship the on-device ADB setup. */
internal object LocalAdbSetupFactory {
    fun create(context: Context): LocalAdbSetup? = null
}
