@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nuvio.tv.R
import com.nuvio.tv.core.vpn.VpnProfilePhoneServer
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.vpn.android.VpnSettingsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.nuvio.vpn.android.R as VpnR

private sealed interface VpnDialog {
    data object Mode : VpnDialog
    data object Apply : VpnDialog
    data class ProfileMenu(val id: String) : VpnDialog
    data class Remove(val id: String) : VpnDialog
    data class Credentials(val id: String) : VpnDialog
}

private tailrec fun Context.vpnActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.vpnActivity()
    else -> null
}

/** VPN profiles and automatic protection, shown inside Settings like every other section. */
@Composable
fun VpnSettingsContent(initialFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<VpnSettingsController.State?>(null) }
    val controller = remember { VpnSettingsController(context) { state = it } }
    // Read once up front so the connection card is there from the first frame and nothing below it shifts.
    var connection by remember { mutableStateOf<VpnSettingsController.Connection?>(runCatching { controller.connection() }.getOrNull()) }
    var dialog by remember { mutableStateOf<VpnDialog?>(null) }
    var pickerMissing by remember { mutableStateOf(false) }
    var pickingOpenVpn by remember { mutableStateOf(false) }
    // The top row (the connection card) takes the focus when the pane opens, and is where focus lands
    // if the row a dialog came from is gone.
    val ownTopRequester = remember { FocusRequester() }
    val topRequester = initialFocusRequester ?: ownTopRequester
    val modeRequester = remember { FocusRequester() }
    val applyRequester = remember { FocusRequester() }
    val profileRequesters = remember { mutableMapOf<String, FocusRequester>() }
    // The row that opened the dialog now showing; focus goes back to it when the dialog closes.
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.importProfile(uri, pickingOpenVpn)
    }
    var phoneOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun importProfileText(text: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                // Remove leftovers from an import whose screen left before cleanup ran.
                context.cacheDir.listFiles { file -> file.name.startsWith("vpn-import-") }
                    ?.forEach { it.delete() }
            }
            val file = File(context.cacheDir, "vpn-import-${System.currentTimeMillis()}.conf")
            withContext(Dispatchers.IO) { file.writeText(text) }
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull() ?: return@launch
            controller.importProfile(uri, false)
            // The controller reads the document on its own worker. Remove the plaintext copy
            // once it has had time to import; the stored profile itself is encrypted.
            delay(30_000)
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    DisposableEffect(controller) {
        controller.refresh()
        onDispose { controller.close() }
    }
    // Saved profiles are private: keep this page out of screenshots and recent-app previews.
    DisposableEffect(context) {
        val window = context.vpnActivity()?.window
        val added = window != null && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) == 0
        if (added) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (added) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(controller) {
        while (true) {
            connection = controller.connection()
            delay(500)
        }
    }
    LaunchedEffect(dialog) {
        if (dialog == null) {
            val target = returnFocus ?: return@LaunchedEffect
            returnFocus = null
            // Give the dialog a moment to leave, then go back to the row it came from.
            delay(60)
            if (runCatching { target.requestFocus() }.isFailure) runCatching { topRequester.requestFocus() }
        }
    }
    val askCredentialsFor = state?.askCredentialsFor
    LaunchedEffect(askCredentialsFor) {
        if (askCredentialsFor != null) dialog = VpnDialog.Credentials(askCredentialsFor)
    }

    fun choose(openVpn: Boolean) {
        if (state?.busy == true) return
        pickingOpenVpn = openVpn
        pickerMissing = false
        try {
            picker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            pickerMissing = true
        }
    }

    val current = state
    val busy = current?.busy == true
    val mode = current?.mode ?: VpnSettingsController.MODE_OFF

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(VpnR.string.vpn_profiles_title),
            subtitle = if (busy) "Saving…" else stringResource(VpnR.string.vpn_profiles_description)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            connection?.let { live ->
                item(key = "status") {
                    SettingsGroupCard {
                        SettingsActionRow(
                            title = live.title,
                            subtitle = listOfNotNull(live.detail, live.traffic).joinToString("\n"),
                            leadingIcon = Icons.Default.Shield,
                            // Keep status focusable for scrolling; protection is changed
                            // only through the explicit Automatic protection setting.
                            onClick = {},
                            modifier = Modifier.focusRequester(topRequester)
                        )
                    }
                }
            }
            item(key = "import") {
                SettingsGroupCard(title = stringResource(VpnR.string.vpn_import_title)) {
                    SettingsActionRow(
                        title = stringResource(VpnR.string.vpn_wireguard_title),
                        subtitle = stringResource(VpnR.string.vpn_wireguard_detail),
                        leadingIcon = Icons.Default.Add,
                        onClick = { choose(openVpn = false) }
                    )
                    SettingsActionRow(
                        title = stringResource(VpnR.string.vpn_openvpn_title),
                        subtitle = stringResource(VpnR.string.vpn_openvpn_detail),
                        leadingIcon = Icons.Default.Add,
                        onClick = { choose(openVpn = true) }
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.vpn_phone_add),
                        subtitle = stringResource(R.string.vpn_phone_add_detail),
                        leadingIcon = Icons.Default.Add,
                        onClick = { phoneOpen = true }
                    )
                    // Import results and errors show right under the buttons that caused them.
                    val notice = current?.notice
                    if (notice != null) {
                        SettingsNote(
                            text = notice,
                            tone = if (current?.noticeIsError == true) SettingsNoteTone.Danger else SettingsNoteTone.Info
                        )
                    }
                    if (pickerMissing) {
                        SettingsNote(text = stringResource(VpnR.string.vpn_profiles_no_picker), tone = SettingsNoteTone.Danger)
                    }
                    SettingsNote(text = stringResource(VpnR.string.vpn_connection_limits))
                }
            }
            item(key = "protection") {
                SettingsGroupCard(title = stringResource(VpnR.string.vpn_scope_title)) {
                    SettingsActionRow(
                        title = stringResource(VpnR.string.vpn_scope_title),
                        subtitle = stringResource(modeDetail(mode)),
                        value = stringResource(modeTitle(mode)),
                        leadingIcon = Icons.Default.Tune,
                        onClick = { if (!busy) { returnFocus = modeRequester; dialog = VpnDialog.Mode } },
                        modifier = Modifier.focusRequester(modeRequester)
                    )
                    SettingsActionRow(
                        title = stringResource(VpnR.string.vpn_apply_title),
                        subtitle = stringResource(
                            if (current?.shared != false) VpnR.string.vpn_apply_all_detail else VpnR.string.vpn_apply_one_detail
                        ),
                        value = stringResource(
                            if (current?.shared != false) VpnR.string.vpn_apply_all else VpnR.string.vpn_apply_one
                        ),
                        onClick = { if (!busy) { returnFocus = applyRequester; dialog = VpnDialog.Apply } },
                        modifier = Modifier.focusRequester(applyRequester)
                    )
                }
            }
            item(key = "profiles") {
                SettingsGroupCard(title = stringResource(VpnR.string.vpn_saved_profiles_title)) {
                    val profiles = current?.profiles.orEmpty()
                    // The result of what was just done here (login saved, profile chosen or removed) shows where it happened.
                    current?.notice?.let { savedNotice ->
                        SettingsNote(
                            text = savedNotice,
                            tone = if (current.noticeIsError) SettingsNoteTone.Danger else SettingsNoteTone.Info
                        )
                    }
                    if (current?.loaded == true && profiles.isEmpty()) {
                        SettingsNote(
                            text = stringResource(VpnR.string.vpn_profiles_empty) + " " +
                                stringResource(VpnR.string.vpn_profiles_empty_detail)
                        )
                    }
                    profiles.forEach { profile ->
                        SettingsActionRow(
                            title = profile.name,
                            subtitle = if (profile.readable) {
                                val kind = stringResource(VpnR.string.vpn_profiles_pending, profile.kind)
                                val login = when (profile.login) {
                                    VpnSettingsController.LOGIN_SAVED -> " · Login saved"
                                    VpnSettingsController.LOGIN_REQUIRED -> " · Login needed"
                                    else -> ""
                                }
                                if (profile.selected) kind + " · " + stringResource(VpnR.string.vpn_profiles_selected) + login else kind + login
                            } else {
                                null
                            },
                            leadingIcon = if (profile.selected) Icons.Default.Check else null,
                            onClick = {
                                if (!busy) {
                                    returnFocus = profileRequesters.getOrPut(profile.id) { FocusRequester() }
                                    dialog = VpnDialog.ProfileMenu(profile.id)
                                }
                            },
                            modifier = Modifier.focusRequester(profileRequesters.getOrPut(profile.id) { FocusRequester() })
                        )
                    }
                    SettingsNote(text = stringResource(VpnR.string.vpn_profiles_storage_notice))
                }
            }
        }
    }

    when (val open = dialog) {
        null -> Unit
        VpnDialog.Mode -> {
            val modes = listOf(
                VpnSettingsController.MODE_OFF,
                VpnSettingsController.MODE_HTTP,
                VpnSettingsController.MODE_HTTP_HTTPS,
                VpnSettingsController.MODE_APP
            )
            SettingsSingleChoiceDialog(
                title = stringResource(VpnR.string.vpn_scope_title),
                options = modes.map { SettingsPickerOption(it, stringResource(modeTitle(it)), stringResource(modeDetail(it))) },
                selectedValue = mode,
                onOptionSelected = { picked ->
                    dialog = null
                    // Selecting Full app again also retries an earlier declined permission request.
                    if (picked != mode || picked == VpnSettingsController.MODE_APP) controller.setMode(picked)
                },
                onDismiss = { dialog = null },
                width = 560.dp
            )
        }
        VpnDialog.Apply -> {
            val shared = current?.shared != false
            SettingsSingleChoiceDialog(
                title = stringResource(VpnR.string.vpn_apply_title),
                options = listOf(
                    SettingsPickerOption(true, stringResource(VpnR.string.vpn_apply_all), stringResource(VpnR.string.vpn_apply_all_detail)),
                    SettingsPickerOption(false, stringResource(VpnR.string.vpn_apply_one), stringResource(VpnR.string.vpn_apply_one_detail))
                ),
                selectedValue = shared,
                onOptionSelected = { picked ->
                    dialog = null
                    if (picked != shared) controller.setShared(picked)
                },
                onDismiss = { dialog = null },
                width = 560.dp
            )
        }
        is VpnDialog.ProfileMenu -> {
            val profile = current?.profiles?.firstOrNull { it.id == open.id }
            if (profile == null) {
                dialog = null
            } else {
                val useLabel = stringResource(VpnR.string.vpn_profiles_select)
                val clearLabel = stringResource(VpnR.string.vpn_profiles_clear_selection)
                val credentialsLabel = stringResource(VpnR.string.vpn_service_credentials)
                val removeLabel = stringResource(VpnR.string.vpn_profiles_remove)
                val options = mutableListOf<SettingsPickerOption<String>>()
                if (profile.readable) {
                    options.add(
                        if (profile.selected) SettingsPickerOption("clear", clearLabel)
                        else SettingsPickerOption("select", useLabel)
                    )
                    if (profile.login != VpnSettingsController.LOGIN_NONE) {
                        options.add(SettingsPickerOption("credentials", credentialsLabel))
                    }
                }
                options.add(SettingsPickerOption("remove", removeLabel))
                SettingsSingleChoiceDialog(
                    title = profile.name,
                    options = options,
                    selectedValue = "",
                    onOptionSelected = { action ->
                        dialog = null
                        when (action) {
                            "select" -> controller.selectProfile(profile.id)
                            "clear" -> controller.selectProfile(null)
                            "credentials" -> dialog = VpnDialog.Credentials(profile.id)
                            "remove" -> dialog = VpnDialog.Remove(profile.id)
                        }
                    },
                    onDismiss = { dialog = null }
                )
            }
        }
        is VpnDialog.Remove -> {
            val profile = current?.profiles?.firstOrNull { it.id == open.id }
            NuvioDialog(
                onDismiss = { dialog = null },
                title = stringResource(VpnR.string.vpn_profiles_remove_question),
                subtitle = profile?.name,
                width = 520.dp
            ) {
                SettingsDialogActionRow {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { dialog = null }
                    )
                    SettingsDialogActionButton(
                        text = stringResource(VpnR.string.vpn_profiles_remove),
                        onClick = {
                            dialog = null
                            controller.remove(open.id)
                        },
                        primary = true
                    )
                }
            }
        }
        is VpnDialog.Credentials -> {
            var username by remember(open.id) { mutableStateOf("") }
            var password by remember(open.id) { mutableStateOf("") }
            val save = {
                val user = username
                val secret = password
                username = ""
                password = ""
                dialog = null
                controller.saveCredentials(open.id, user, secret)
            }
            NuvioDialog(
                onDismiss = { dialog = null },
                title = stringResource(VpnR.string.vpn_service_credentials),
                width = 640.dp
            ) {
                InputField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = stringResource(VpnR.string.vpn_service_username),
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
                InputField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(VpnR.string.vpn_service_password),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    imeAction = ImeAction.Done,
                    onImeAction = save,
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsDialogActionRow {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { dialog = null }
                    )
                    SettingsDialogActionButton(
                        text = stringResource(VpnR.string.vpn_save_credentials),
                        onClick = save,
                        primary = true
                    )
                }
            }
        }
    }
    if (phoneOpen) {
        VpnPhoneImportDialog(
            onDismiss = { phoneOpen = false },
            onProfile = { text -> importProfileText(text) }
        )
    }
}

private fun modeTitle(mode: Int): Int = when (mode) {
    VpnSettingsController.MODE_HTTP -> VpnR.string.vpn_scope_http
    VpnSettingsController.MODE_HTTP_HTTPS -> VpnR.string.vpn_scope_http_https
    VpnSettingsController.MODE_APP -> VpnR.string.vpn_scope_app
    else -> VpnR.string.vpn_scope_off
}

private fun modeDetail(mode: Int): Int = when (mode) {
    VpnSettingsController.MODE_HTTP -> VpnR.string.vpn_scope_http_detail
    VpnSettingsController.MODE_HTTP_HTTPS -> VpnR.string.vpn_scope_http_https_detail
    VpnSettingsController.MODE_APP -> VpnR.string.vpn_scope_app_detail
    else -> VpnR.string.vpn_scope_off_detail
}

@Composable
private fun VpnPhoneImportDialog(onDismiss: () -> Unit, onProfile: (String) -> Unit) {
    val context = LocalContext.current
    val closeFocus = remember { FocusRequester() }
    var handle by remember { mutableStateOf<VpnProfilePhoneServer.Handle?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    var received by remember { mutableStateOf(false) }
    val pending = remember { MutableStateFlow<String?>(null) }
    val accentArgb = NuvioTheme.colors.Secondary.toArgb()
    val tvName = remember(context) {
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
            ?: context.getString(R.string.iptv_settings_your_tv)
    }
    val phoneTexts = remember(context, tvName) {
        VpnProfilePhoneServer.Texts.from(context.resources, tvName)
    }
    DisposableEffect(phoneTexts, accentArgb) {
        val h = VpnProfilePhoneServer.start(
            texts = phoneTexts,
            accent = String.format(java.util.Locale.US, "#%06X", accentArgb and 0xFFFFFF)
        ) { body ->
            pending.value = body
            phoneTexts.received
        }
        handle = h
        noNetwork = h == null
        onDispose { h?.stop() }
    }
    LaunchedEffect(Unit) {
        pending.collect { body ->
            if (body != null) {
                received = true
                onProfile(body)
                delay(900)
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { closeFocus.requestFocus() }
    }
    val qrBitmap = remember(handle?.url) {
        handle?.url?.let { runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(it, 420, margin = 1) }.getOrNull() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.vpn_phone_dialog_title),
        width = 620.dp
    ) {
        if (noNetwork) {
            Text(
                text = stringResource(R.string.vpn_phone_no_network),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                qrBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.vpn_phone_qr),
                        modifier = Modifier.size(196.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.vpn_phone_steps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    handle?.url?.let {
                        Text(
                            text = stringResource(R.string.vpn_phone_address, it.removePrefix("http://")),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
            Text(
                text = if (received) stringResource(R.string.vpn_phone_received_tv)
                else stringResource(R.string.vpn_phone_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = if (received) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(closeFocus)) {
                SettingsDialogActionButton(text = stringResource(R.string.action_close), onClick = onDismiss)
            }
        }
    }
}
