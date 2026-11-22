package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Set while the Live TV guide's Reorder groups mode is open.
 *
 * The app shell's Back handler opens the Nuvio menu from any root page. During Reorder groups,
 * Back has to save the new order and leave that mode instead, so the shell handler stands down
 * until the guide clears this.
 */
internal object LiveTvGuideBack {
    var reorderActive by mutableStateOf(false)

    /**
     * When Back closes Reorder groups, Android can also deliver the same press to the app-shell
     * Back handler after the mode has already turned off. This swallows that one press so the
     * menu does not open on top of the guide.
     */
    @Volatile
    var suppressMenuUntilMs: Long = 0L
}
