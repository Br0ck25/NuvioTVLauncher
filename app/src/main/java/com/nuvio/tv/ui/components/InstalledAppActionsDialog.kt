@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Long-press on an app: open it, move it earlier or later (the Apps page and the Home row share
 * one order), or see its system page (Uninstall, Force stop). Moves keep the dialog open so an app
 * can be walked along the row.
 */
@Composable
fun InstalledAppActionsDialog(
    app: InstalledApp,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onOpen: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onAppInfo: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioTheme.colors.BackgroundCard)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(app.label, style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
            Button(onClick = { onDismiss(); onOpen() }, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                Text(stringResource(R.string.app_action_open))
            }
            Button(onClick = onMoveEarlier, enabled = canMoveEarlier, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.app_action_move_earlier))
            }
            Button(onClick = onMoveLater, enabled = canMoveLater, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.app_action_move_later))
            }
            Button(onClick = { onDismiss(); onAppInfo() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.app_action_info))
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(stringResource(R.string.self_setup_close))
            }
        }
    }
}
