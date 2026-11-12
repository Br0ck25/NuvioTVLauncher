package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.launcher.LauncherSelfSetup
import com.nuvio.tv.core.launcher.LocalAdbSetup
import com.nuvio.tv.core.launcher.PhonePairingSession
import com.nuvio.tv.core.player.ExternalPlaybackKeepAliveService
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LocalAdbSetupDialog(
    setup: LocalAdbSetup,
    onDismiss: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    // Shared by the TV fields and the phone page so only one setup run can be active.
    // The server starts before first composition so the phone address is available immediately.
    val session = remember { PhonePairingSession(context, setup).also { it.startPhoneServer() } }
    DisposableEffect(session) {
        // While the user is in Settings reading the pairing code, Nuvio is in the background and the
        // TV can freeze it, which leaves the phone's request hanging. A foreground service prevents that.
        ExternalPlaybackKeepAliveService.start(context)
        onDispose {
            session.stop()
            ExternalPlaybackKeepAliveService.stop(context)
        }
    }
    val sessionState by session.state.collectAsStateWithLifecycle()
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    // When the phone has finished, show the result for a moment, then close and let the checklist refresh.
    LaunchedEffect(sessionState) {
        if (sessionState is PhonePairingSession.State.Done) {
            kotlinx.coroutines.delay(2_500)
            onFinished()
        }
    }
    val running = sessionState is PhonePairingSession.State.Working
    val finished = sessionState is PhonePairingSession.State.Done
    val error = (sessionState as? PhonePairingSession.State.Failed)?.message

    val yes = stringResource(R.string.self_setup_yes)
    val no = stringResource(R.string.self_setup_no)
    // Both blank means "already paired, just connect".
    val validPairing = pairingPort.toIntOrNull() != null && pairingCode.trim().length == 6
    val canStart = !running && (validPairing || (pairingPort.isEmpty() && pairingCode.isEmpty()))

    NuvioDialog(
        onDismiss = { if (!running) onDismiss() },
        title = stringResource(R.string.self_setup_dialog_title),
        width = 720.dp,
        contentPadding = 20.dp,
        contentSpacing = 12.dp
    ) {
        Text(
            text = stringResource(R.string.self_setup_dialog_steps),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        session.phoneUrl?.let { url ->
                val qr = remember(url) { runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(url, 360) }.getOrNull() }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = stringResource(R.string.self_setup_scan_phone),
                            modifier = Modifier.size(112.dp).background(Color.White).padding(4.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.self_setup_scan_phone),
                            style = MaterialTheme.typography.titleSmall,
                            color = NuvioTheme.colors.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.self_setup_phone_hint, url),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
        // Outside the scrolling area: when focus scrolled the fields into view it used to cut the start
        // of this sentence off ("don't). Use the other buttons...").
        Text(
            text = stringResource(R.string.self_setup_not_found_hint),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        Column(
            modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InputField(
                value = pairingPort,
                onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                placeholder = stringResource(R.string.self_setup_pairing_port),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
            InputField(
                value = pairingCode,
                onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                placeholder = stringResource(R.string.self_setup_pairing_code),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
            InputField(
                value = connectPort,
                onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                placeholder = stringResource(R.string.self_setup_connect_port),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Text(
                    text = stringResource(R.string.self_setup_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336)
                )
            }
            if (finished) {
                val status = LauncherSelfSetup.status(context)
                Text(
                    text = stringResource(
                        R.string.self_setup_result,
                        if (status.isDefaultHome) yes else no,
                        if (status.accessibilityEnabled) yes else no
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
            Button(
                onClick = {
                    if (finished) {
                        onFinished()
                        return@Button
                    }
                    session.submit(
                        pairingPort = pairingPort.toIntOrNull(),
                        pairingCode = pairingCode.ifEmpty { null },
                        connectPort = connectPort.toIntOrNull()
                    )
                },
                enabled = finished || canStart,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        when {
                            finished -> R.string.self_setup_close
                            running -> R.string.self_setup_working
                            else -> R.string.self_setup_start
                        }
                    ),
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs)
                )
            }
        }
    }
}
