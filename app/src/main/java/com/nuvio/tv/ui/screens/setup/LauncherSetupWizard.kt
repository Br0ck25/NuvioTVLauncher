@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.setup

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.launcher.LauncherAccessibilityService
import com.nuvio.tv.core.launcher.LauncherSetup
import com.nuvio.tv.core.launcher.LocalAdbSetupFactory
import com.nuvio.tv.core.notification.LauncherNotificationListenerService
import com.nuvio.tv.ui.components.rememberLauncherSetupState
import com.nuvio.tv.ui.screens.settings.LocalAdbSetupDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/** Whether the first-run launcher setup has been finished or skipped. Kept simple on purpose. */
object LauncherSetupWizardPrefs {
    private const val FILE = "launcher_setup"
    private const val KEY_DONE = "wizard_done"

    fun isDone(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false) }.getOrDefault(false)

    private const val KEY_STARTED = "wizard_started"

    /** The first screen was passed once. Progress itself is read from the real system state, never saved. */
    fun isStarted(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_STARTED, false) }.getOrDefault(false)

    fun markStarted(context: Context) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_STARTED, true).apply() }
    }

    fun markDone(context: Context) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply() }
    }

    /** Shows the setup again at the checklist (not the welcome screen), e.g. after an update turned the override off. */
    fun reopen(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DONE, false).putBoolean(KEY_STARTED, true).apply()
        }
    }
}

/**
 * First run, before the sign-in screen: two screens that walk through making this app the TV's Home
 * screen. Everything is optional and can be done later from Settings → Default launcher.
 */
@Composable
fun LauncherSetupWizard(onFinished: () -> Unit) {
    val context = LocalContext.current
    // After a restart (or coming back from Settings) skip the welcome screen when setup is already under way:
    // either it was started before, or some of the system state is already in place.
    var step by remember {
        mutableIntStateOf(
            if (LauncherSetupWizardPrefs.isStarted(context) || LauncherSetup.homeAppSet(context) ||
                LauncherAccessibilityService.isServiceEnabled(context) ||
                com.nuvio.tv.core.notification.LauncherNotificationListenerService.isAccessGranted(context)
            ) 1 else 0
        )
    }
    val scroll = rememberScrollState()
    // Always start at the top: a focused button below the fold used to scroll the title out of view.
    // Focus is requested shortly after composition and scrolls the page, so look again once it has settled.
    LaunchedEffect(step) {
        scroll.scrollTo(0)
        kotlinx.coroutines.delay(350)
        if (!scroll.isScrollInProgress) scroll.scrollTo(0)
    }
    BackHandler { if (step > 0) step-- }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .padding(horizontal = 48.dp, vertical = 32.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (step == 0) WelcomeStep(
                onStart = { LauncherSetupWizardPrefs.markStarted(context); step = 1 },
                onSkip = onFinished
            )
            else SetupStep(onDone = onFinished, onBack = { step = 0 })
            // Room under the last button so its focus ring is not clipped by the screen edge.
            androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit, onSkip: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Text(stringResource(R.string.wizard_welcome_title), style = MaterialTheme.typography.displaySmall, color = NuvioTheme.colors.TextPrimary)
    Text(stringResource(R.string.wizard_welcome_body), style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
    Text(stringResource(R.string.wizard_welcome_points), style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextPrimary)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = onStart, modifier = Modifier.focusRequester(first)) { Text(stringResource(R.string.wizard_start)) }
        OutlinedButton(onClick = onSkip) { Text(stringResource(R.string.wizard_skip)) }
    }
}

@Composable
private fun SetupStep(onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val setup = rememberLauncherSetupState()
    // Null where the on-device setup isn't available (the Play build, or before Android 11).
    val phoneSetup = remember { LocalAdbSetupFactory.create(context) }
    var showPhoneSetup by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }

    // The phone setup and Android's own screens change things behind our back, so look again on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Notification access can change while a system screen is open: check again every second.
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_000); revision++ } }
    val notificationsOn = remember(revision) { LauncherNotificationListenerService.isAccessGranted(context) }
    val homeOn = setup.isDefaultHome
    val overrideOn = setup.isAccessibilityActive
    // Android blocks the override switch for apps installed outside a store; the TV has no menu to allow it.
    val restrictedBlocked = remember(revision) { LauncherSetup.restrictedSettingsBlocked(context) == true }
    // Or by what happened: the user went to turn it on, came back, and it is still off.
    val attemptFailed = remember(revision) { LauncherSetup.overrideAttemptFailed(context) }
    // An update can switch the override off without telling anyone: say so.
    val overrideLost = remember(revision) { LauncherSetup.overrideLostNotice(context) }
    LaunchedEffect(overrideOn) { if (overrideOn) LauncherSetup.clearOverrideLostNotice(context) }
    val first = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Text(stringResource(R.string.wizard_setup_title), style = MaterialTheme.typography.headlineLarge, color = NuvioTheme.colors.TextPrimary)
    if (overrideLost && !overrideOn) {
        Text(stringResource(R.string.wizard_override_lost), style = MaterialTheme.typography.bodyLarge, color = androidx.compose.ui.graphics.Color(0xFFFFB74D))
    }
    Text(stringResource(R.string.wizard_setup_body), style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusLine(stringResource(R.string.wizard_status_home), homeOn)
        StatusLine(stringResource(R.string.wizard_status_override), overrideOn)
        StatusLine(stringResource(R.string.wizard_status_notifications), notificationsOn)
        val mechanism = when {
            setup.isDefaultHome && !overrideOn && com.nuvio.tv.core.launcher.LauncherSetup.isDefaultHome(context) -> R.string.wizard_works_native
            overrideOn -> R.string.wizard_works_override
            else -> null
        }
        mechanism?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
        }
    }

    Text(stringResource(R.string.wizard_manual_title), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.padding(top = 4.dp))
    // No toasts here: they sat on top of Android's own dialog and hid its buttons. The hint is on screen already.
    Button(
        onClick = { setup.requestDefaultHome() },
        modifier = Modifier.fillMaxWidth().focusRequester(first)
    ) {
        Text(stringResource(R.string.wizard_make_home))
    }
    Text(stringResource(R.string.wizard_role_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
    OutlinedButton(
        onClick = { setup.openAccessibilitySettings() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.wizard_turn_on_override))
    }
    Text(
        stringResource(if (overrideOn) R.string.wizard_override_done else R.string.wizard_accessibility_explain),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary
    )
    if (!overrideOn) {
        if (restrictedBlocked || attemptFailed) {
            // The switch will not stay on. Say so plainly instead of letting it quietly flip back.
            Text(
                stringResource(R.string.wizard_override_blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color(0xFFFFB74D)
            )
        } else {
            Text(stringResource(R.string.wizard_restricted_settings_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        }
    }
    OutlinedButton(onClick = { LauncherNotificationListenerService.openAccessSettings(context) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_turn_on_notifications))
    }
    if (!notificationsOn) {
        Text(stringResource(R.string.wizard_notifications_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
    }
    if (phoneSetup != null) {
        // Down from here must reach "Continue to sign in", not skip past it to Back.
        OutlinedButton(
            onClick = { showPhoneSetup = true },
            modifier = Modifier.fillMaxWidth().focusProperties { down = continueFocus }
        ) {
            Text(stringResource(R.string.wizard_phone_setup))
        }
        Text(stringResource(R.string.wizard_phone_setup_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
    } else {
        val support = com.nuvio.tv.core.launcher.PhoneSetupSupport.current()
        Text(
            text = if (support == com.nuvio.tv.core.launcher.PhoneSetupSupport.ANDROID_TOO_OLD) {
                stringResource(R.string.self_setup_unavailable_old, android.os.Build.VERSION.RELEASE)
            } else {
                stringResource(R.string.self_setup_unavailable_build)
            },
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = onDone, modifier = Modifier.focusRequester(continueFocus)) { Text(stringResource(R.string.wizard_continue)) }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.wizard_back)) }
    }
    Text(stringResource(R.string.wizard_later), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)

    if (showPhoneSetup && phoneSetup != null) {
        LocalAdbSetupDialog(
            setup = phoneSetup,
            onDismiss = { showPhoneSetup = false; revision++ },
            onFinished = { showPhoneSetup = false; revision++ }
        )
    }
}

@Composable
private fun StatusLine(label: String, on: Boolean) {
    Text(
        text = (if (on) "✓  " else "○  ") + label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (on) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
    )
}
