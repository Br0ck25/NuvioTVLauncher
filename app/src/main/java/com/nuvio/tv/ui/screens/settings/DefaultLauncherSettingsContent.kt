package com.nuvio.tv.ui.screens.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.R
import com.nuvio.tv.core.launcher.HomeApp
import com.nuvio.tv.ui.theme.NuvioTheme

/** Settings → Default launcher: make this app the Home app through Android's own chooser. */
@Composable
fun DefaultLauncherSettingsContent(initialFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(HomeApp.isDefault(context)) }
    var showPhoneSetup by remember { mutableStateOf(false) }

    // The chooser is another screen, so re-check whenever we come back to this one.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isDefault = HomeApp.isDefault(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The framework decides; RESULT_OK is not proof, so read the resolver again afterwards.
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = HomeApp.isDefault(context)
    }

    fun requestHome() {
        val role = HomeApp.roleRequestIntent(context)
        try {
            if (role != null) {
                roleLauncher.launch(role)
            } else {
                HomeApp.bestRequestIntent(context)?.let(context::startActivity)
            }
        } catch (_: ActivityNotFoundException) {
            // This firmware hides the picker. The status line stays accurate; nothing else to do.
        }
    }

    fun open(intent: android.content.Intent?) {
        try { intent?.let(context::startActivity) } catch (_: ActivityNotFoundException) { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_default_launcher),
            subtitle = stringResource(R.string.settings_default_launcher_subtitle)
        )
        SettingsGroupCard {
            SettingsActionRow(
                title = stringResource(
                    if (isDefault) R.string.default_launcher_status_on else R.string.default_launcher_status_off
                ),
                subtitle = stringResource(
                    if (isDefault) R.string.default_launcher_status_on_hint else R.string.default_launcher_status_off_hint
                ),
                onClick = { if (!isDefault) requestHome() },
                leadingIcon = Icons.Default.Home,
                modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
            )
            if (!isDefault) {
                SettingsActionRow(
                    title = stringResource(R.string.default_launcher_make_default),
                    subtitle = stringResource(R.string.default_launcher_make_default_hint),
                    onClick = ::requestHome,
                    leadingIcon = Icons.Default.Home
                )
            }
            SettingsActionRow(
                title = stringResource(R.string.launcher_phone_setup_title),
                subtitle = stringResource(R.string.launcher_phone_setup_hint),
                onClick = { showPhoneSetup = true },
                leadingIcon = Icons.Default.Phone
            )
            if (HomeApp.homeSettingsIntent(context) != null) {
                SettingsActionRow(
                    title = stringResource(R.string.default_launcher_open_home_settings),
                    subtitle = stringResource(R.string.default_launcher_open_home_settings_hint),
                    onClick = { open(HomeApp.homeSettingsIntent(context)) },
                    leadingIcon = Icons.Default.Settings
                )
            }
        }
        SettingsNote(
            text = stringResource(R.string.default_launcher_firmware_note),
            modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
        )
    }
    if (showPhoneSetup) LauncherPhoneSetupDialog(onDismiss = { showPhoneSetup = false })
}
