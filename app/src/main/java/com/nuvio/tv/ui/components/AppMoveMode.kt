package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Moving an app the way Projectivy does: "Move" in the app's menu picks it up, the D-pad carries it
 * along the row (or grid), and OK or Back puts it down where it is. The app stays under the focus ring
 * the whole time, so the order is saved as it goes.
 */
@Stable
class AppMoveState {
    /** Package of the app being carried, or null when nothing is. */
    var carrying by mutableStateOf<String?>(null)
        private set

    // The key press that chose "Move" is still ending when carrying starts; its release must not
    // count as the drop. A drop needs a fresh press first.
    private var armed = false

    fun pickUp(packageName: String) {
        carrying = packageName
        armed = false
    }

    fun drop() {
        carrying = null
        armed = false
    }

    internal fun arm() { armed = true }
    internal fun isArmed() = armed
}

@Composable
fun rememberAppMoveState(): AppMoveState = remember { AppMoveState() }

/**
 * Put on the list or grid that holds the apps. While an app is being carried it takes the D-pad:
 * left/right step it one place, up/down step it [rowSize] places (1 in a row, so up and down do
 * nothing there), and OK or Back drops it. Everything else passes through as usual.
 *
 * [onStep] gets the number of places to move the carried app (negative is earlier).
 */
fun Modifier.appMoveKeys(
    state: AppMoveState,
    rowSize: () -> Int = { 1 },
    onStep: (Int) -> Unit
): Modifier = onPreviewKeyEvent { event ->
    if (state.carrying == null) return@onPreviewKeyEvent false
    val native = event.nativeKeyEvent
    val down = native.action == AndroidKeyEvent.ACTION_DOWN
    when (native.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) onStep(-1); true }
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) onStep(1); true }
        AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (down && rowSize() > 1) onStep(-rowSize()); true }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (down && rowSize() > 1) onStep(rowSize()); true }
        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        AndroidKeyEvent.KEYCODE_BUTTON_A,
        AndroidKeyEvent.KEYCODE_BACK -> {
            if (down) {
                if (native.repeatCount == 0) state.arm()
            } else if (state.isArmed()) {
                state.drop()
            }
            // Both halves of the press are used up here, so dropping never also opens the app.
            true
        }
        else -> false
    }
}

/** Steps [index] by [delta] but never past either end, so a long step near the edge still moves. */
fun clampedStep(index: Int, delta: Int, lastIndex: Int): Int =
    (index + delta).coerceIn(0, lastIndex) - index
