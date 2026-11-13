package com.nuvio.tv.ui.screens.player.autosync

import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * Every playback-side request goes through the VPN protection. Unit tests replace [wrap] with `{ it }`,
 * because the VPN layer needs a running Android app and blocks requests without one.
 */
internal object PlaybackHttpProtection {
    @Volatile
    var wrap: (OkHttpClient) -> Call.Factory = { com.nuvio.vpn.android.VpnHttpClient.wrap(it) }
}
