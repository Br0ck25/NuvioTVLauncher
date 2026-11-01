package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@Composable
internal fun LocalAdbSetupDialog(
    setup: LocalAdbSetup,
    onDismiss: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    val yes = stringResource(R.string.self_setup_yes)
    val no = stringResource(R.string.self_setup_no)
    val canStart = !running && pairingPort.toIntOrNull() != null && pairingCode.trim().length == 6

    NuvioDialog(
        onDismiss = { if (!running) onDismiss() },
        title = stringResource(R.string.self_setup_dialog_title),
        width = 640.dp,
        contentPadding = 20.dp,
        contentSpacing = 12.dp
    ) {
        Column(
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.self_setup_dialog_steps),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
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
                    running = true
                    error = null
                    scope.launch {
                        val result = setup.setUp(
                            pairingPort = pairingPort.toInt(),
                            pairingCode = pairingCode,
                            connectPort = connectPort.toIntOrNull()
                        )
                        running = false
                        result.onSuccess { finished = true }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                    }
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
