package com.nuvio.tv.core.launcher

import android.os.Build
import com.nuvio.tv.BuildConfig

/** Whether the phone-driven setup can run on this device, and if not, why (so the user can be told). */
internal enum class PhoneSetupSupport {
    /** The TV can pair with itself. It still needs Wireless debugging in Developer options. */
    AVAILABLE,

    /** Wireless debugging pairing arrived with Android 11. */
    ANDROID_TOO_OLD,

    /** The Play Store build has no on-device ADB. */
    NOT_IN_THIS_BUILD;

    companion object {
        fun current(): PhoneSetupSupport = when {
            BuildConfig.FLAVOR == "playstore" -> NOT_IN_THIS_BUILD
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> ANDROID_TOO_OLD
            else -> AVAILABLE
        }
    }
}
