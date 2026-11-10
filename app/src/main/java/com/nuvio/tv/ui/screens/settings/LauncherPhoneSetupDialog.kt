@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.core.launcher.LauncherSetupServer
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Phone setup: a QR code opens a page on the phone with the ADB commands (set this app as the
 * Home app, undo it, and the optional stock-launcher disable commands) ready to copy. The page
 * only exists while this dialog is open.
 */
@Composable
fun LauncherPhoneSetupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val component = remember { ComponentName(context, MainActivity::class.java).flattenToString() }
    var handle by remember { mutableStateOf<LauncherSetupServer.Handle?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val h = LauncherSetupServer.start(context.getString(R.string.app_name), component)
        handle = h
        noNetwork = h == null
        onDispose { h?.stop() }
    }
    val qr = remember(handle?.url) {
        handle?.url?.let { runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(it, 360) }.getOrNull() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioTheme.colors.BackgroundCard)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.launcher_phone_setup_title),
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary
            )
            if (noNetwork) {
                Text(
                    stringResource(R.string.launcher_phone_setup_no_network),
                    color = NuvioTheme.colors.TextSecondary
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(190.dp).background(Color.White).padding(6.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.launcher_phone_setup_steps),
                            color = NuvioTheme.colors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        handle?.url?.let {
                            Text(it.removePrefix("http://"), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    stringResource(R.string.launcher_phone_setup_command_label),
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "adb shell cmd package set-home-activity $component",
                    color = NuvioTheme.colors.TextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(10.dp)
                )
            }
            Button(onClick = onDismiss) { Text(stringResource(R.string.launcher_phone_setup_close)) }
        }
    }
}
