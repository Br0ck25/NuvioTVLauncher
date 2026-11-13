package com.nuvio.tv.core.launcher

import android.content.Context
import android.view.KeyEvent

/** The remote's standard "all apps" key opens the launcher's Apps page. Other buttons are set up in Button Mapper. */
object AppsKeyStore {
    fun matches(@Suppress("UNUSED_PARAMETER") context: Context, keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_ALL_APPS
}
