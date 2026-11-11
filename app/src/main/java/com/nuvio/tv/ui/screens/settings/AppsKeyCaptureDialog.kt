@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.launcher.AppsKeyStore
import com.nuvio.tv.ui.theme.NuvioTheme
import android.view.KeyEvent as AndroidKeyEvent

/** "Press the button": the next remote button pressed becomes the one that opens the Apps page. */
@Composable
fun AppsKeyCaptureDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var rejected by remember { mutableStateOf(false) }
    val reset = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { reset.requestFocus() } }
    Dialog(onDismissRequest = onDone) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioTheme.colors.BackgroundCard)
                .padding(20.dp)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    // Back cancels (handled by the dialog). Any other button, once pressed, is the answer.
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_BACK || native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE) return@onPreviewKeyEvent false
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                        if (AppsKeyStore.set(context, native.keyCode)) onDone() else rejected = true
                    }
                    // Keep the button's own action from firing while we are listening.
                    native.keyCode != AndroidKeyEvent.KEYCODE_DPAD_UP && native.keyCode != AndroidKeyEvent.KEYCODE_DPAD_DOWN &&
                        native.keyCode != AndroidKeyEvent.KEYCODE_DPAD_CENTER && native.keyCode != AndroidKeyEvent.KEYCODE_ENTER
                },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.apps_key_capture_title), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
            Text(stringResource(R.string.apps_key_capture_body), color = NuvioTheme.colors.TextSecondary)
            if (rejected) Text(stringResource(R.string.apps_key_capture_rejected), color = NuvioTheme.colors.Warning)
            Button(
                onClick = { AppsKeyStore.clear(context); onDone() },
                modifier = Modifier.fillMaxWidth().focusRequester(reset)
            ) { Text(stringResource(R.string.apps_key_capture_reset)) }
        }
    }
}
