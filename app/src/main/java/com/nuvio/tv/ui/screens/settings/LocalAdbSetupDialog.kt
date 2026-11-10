@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.ExternalPlaybackKeepAliveService
import com.nuvio.tv.core.setup.LocalAdbSetup
import com.nuvio.tv.core.setup.PhoneSetupSession
import com.nuvio.tv.core.setup.SetupMode
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The TV side of the on-device setup. Everything here can also be driven from a phone on the same
 * network, which is the path most people need: the TV's "Pair device with pairing code" popup closes
 * as soon as Settings is left, so its code cannot be typed into Nuvio on the TV.
 */
@Composable
internal fun LocalAdbSetupDialog(
    setup: LocalAdbSetup,
    onDismiss: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    // Shared by the TV fields and the phone page, so only one setup run can be active. The server
    // starts before first composition so the phone address is available immediately.
    val session = remember { PhoneSetupSession(context, setup).also { it.startPhoneServer() } }
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
    var mode by remember { mutableStateOf(SetupMode.HOME_APP) }
    var disableStockLauncher by remember { mutableStateOf(false) }
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    val running = sessionState is PhoneSetupSession.State.Working
    val outcome = (sessionState as? PhoneSetupSession.State.Done)?.outcome
    val error = (sessionState as? PhoneSetupSession.State.Failed)?.message

    val yes = stringResource(R.string.self_setup_yes)
    val no = stringResource(R.string.self_setup_no)
    // Both blank means "already paired, just connect".
    val validPairing = pairingPort.toIntOrNull() != null && pairingCode.trim().length == 6
    val canStart = !running && (validPairing || (pairingPort.isEmpty() && pairingCode.isEmpty()))

    NuvioDialog(
        onDismiss = { if (!running) onDismiss() },
        title = stringResource(R.string.self_setup_dialog_title),
        width = 640.dp,
        contentPadding = 20.dp,
        contentSpacing = 12.dp
    ) {
        Column(
            modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.self_setup_dialog_steps),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
            session.phoneUrl?.let { url ->
                Text(
                    text = stringResource(R.string.self_setup_phone_hint, url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsChoiceChip(
                    label = stringResource(R.string.self_setup_mode_home),
                    selected = mode == SetupMode.HOME_APP,
                    onClick = { mode = SetupMode.HOME_APP },
                    modifier = Modifier.weight(1f)
                )
                SettingsChoiceChip(
                    label = stringResource(R.string.self_setup_mode_grants),
                    selected = mode == SetupMode.GRANTS_ONLY,
                    onClick = { mode = SetupMode.GRANTS_ONLY },
                    modifier = Modifier.weight(1f)
                )
            }
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
            if (mode == SetupMode.HOME_APP) {
                SettingsChoiceChip(
                    label = stringResource(R.string.self_setup_disable_stock),
                    selected = disableStockLauncher,
                    onClick = { disableStockLauncher = !disableStockLauncher },
                    multiSelect = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.self_setup_disable_stock_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
            error?.let {
                Text(
                    text = stringResource(R.string.self_setup_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336)
                )
            }
            outcome?.let { result ->
                val summary = stringResource(
                    R.string.self_setup_result,
                    if (result.homeIsNuvio) yes else no,
                    if (result.grantsApplied) yes else no
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextPrimary
                )
                result.homeComponent?.takeIf { !result.homeIsNuvio }?.let { home ->
                    Text(
                        text = stringResource(R.string.self_setup_result_home_value, home),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
                if (result.disabledPackages.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.self_setup_result_disabled,
                            result.disabledPackages.joinToString(", ")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
                if (result.recoveryCommands.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.self_setup_recovery_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    result.recoveryCommands.forEach { command ->
                        Text(
                            text = command,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary
                        )
                    }
                }
            }
            Button(
                onClick = {
                    if (outcome != null) {
                        onFinished()
                        return@Button
                    }
                    session.submit(
                        pairingPort = pairingPort.toIntOrNull(),
                        pairingCode = pairingCode.ifEmpty { null },
                        connectPort = connectPort.toIntOrNull(),
                        mode = mode,
                        disableStockLauncher = disableStockLauncher && mode == SetupMode.HOME_APP
                    )
                },
                enabled = outcome != null || canStart,
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
                            outcome != null -> R.string.self_setup_close
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
