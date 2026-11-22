package com.nuvio.tv.livetv.home

import android.graphics.Bitmap
import com.nuvio.tv.ui.screens.settings.MemoryBudget

/**
 * How much launcher-row work this device gets. Chosen automatically from
 * [MemoryBudget.isLowRamTier]: 1 GB TVs draw fewer, lighter cards and refresh less often,
 * while higher-RAM devices keep the full 24-card, 32-bit, two-minute path.
 */
internal data class LauncherRowsProfile(val lowRam: Boolean) {
    val maxItems: Int = if (lowRam) 12 else 24
    val cardConfig: Bitmap.Config = if (lowRam) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
    val minRefreshMs: Long = if (lowRam) 15 * 60_000L else 2 * 60_000L
    val startupDelayMs: Long = if (lowRam) 30_000L else 6_000L

    companion object {
        val current: LauncherRowsProfile get() = LauncherRowsProfile(MemoryBudget.isLowRamTier)
    }
}
