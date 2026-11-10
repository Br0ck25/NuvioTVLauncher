package com.nuvio.tv.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nuvio.tv.core.launcher.LauncherAccessibilityService
import com.nuvio.tv.core.launcher.LauncherSetup
import com.nuvio.tv.core.launcher.LauncherCapabilities
import com.nuvio.tv.core.launcher.HomeMechanism

internal data class LauncherSetupState(
    val isDefaultHome: Boolean,
    val isAccessibilityEnabled: Boolean,
    val isAccessibilityActive: Boolean,
    val showFallback: Boolean,
    val settingsUnavailable: Boolean,
    val requestDefaultHome: () -> Unit,
    val openHomeSettings: () -> Unit,
    val openAccessibilitySettings: () -> Unit
)

/** Shared by Apps and Settings so canceled/suppressed role dialogs never become silent dead ends. */
@Composable
internal fun rememberLauncherSetupState(): LauncherSetupState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var capabilities by remember { mutableStateOf(LauncherCapabilities.detect(context)) }
    val serviceOperational by LauncherAccessibilityService.isOperational.collectAsStateWithLifecycle()
    val isDefaultHome = capabilities.isDefaultHome
    val isAccessibilityEnabled = capabilities.accessibilityEnabled
    var showFallback by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        capabilities = LauncherCapabilities.detect(context)
        if (capabilities.mechanism != HomeMechanism.UNAVAILABLE) {
            showFallback = false
            settingsUnavailable = false
        }
    }

    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // RESULT_OK alone is not proof on OEM TV firmware. Check the selected Home app again.
        LauncherSetup.recordRoleRequestResult(context)
        refresh()
        showFallback = capabilities.mechanism == HomeMechanism.UNAVAILABLE
    }

    LaunchedEffect(serviceOperational) { refresh() }

    DisposableEffect(lifecycleOwner, context) {
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openHomeSettings() {
        settingsUnavailable = !LauncherSetup.openHomeSettings(context)
        showFallback = capabilities.mechanism == HomeMechanism.UNAVAILABLE
    }

    return LauncherSetupState(
        isDefaultHome = isDefaultHome,
        isAccessibilityEnabled = isAccessibilityEnabled,
        isAccessibilityActive = capabilities.mechanism == HomeMechanism.ACCESSIBILITY,
        showFallback = showFallback,
        settingsUnavailable = settingsUnavailable,
        requestDefaultHome = {
            // An already working native launcher must not open redundant configuration.
            if (LauncherSetup.isDefaultHome(context)) {
                refresh()
            } else {
                val intent = LauncherSetup.createHomeRoleRequest(context)
                if (intent == null) {
                    openHomeSettings()
                } else {
                    settingsUnavailable = false
                    val launched = runCatching { roleRequest.launch(intent) }.isSuccess
                    if (!launched) {
                        LauncherSetup.recordRoleRequestResult(context, launchFailed = true)
                        openHomeSettings()
                    }
                }
            }
        },
        openHomeSettings = { openHomeSettings() },
        openAccessibilitySettings = { LauncherAccessibilityService.openAccessibilitySettings(context) }
    )
}
