package com.nuvio.tv.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

internal data class LauncherSetupState(
    val isDefaultHome: Boolean,
    val isAccessibilityEnabled: Boolean,
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
    var isDefaultHome by remember { mutableStateOf(LauncherSetup.isDefaultHome(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var showFallback by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        isDefaultHome = LauncherSetup.isDefaultHome(context)
        isAccessibilityEnabled = runCatching {
            LauncherAccessibilityService.isServiceEnabled(context)
        }.getOrDefault(false)
        if (isDefaultHome) {
            showFallback = false
            settingsUnavailable = false
        }
    }

    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // RESULT_OK alone is not proof on OEM TV firmware. Check the selected Home app again.
        refresh()
        showFallback = !isDefaultHome
    }

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
        showFallback = true
    }

    return LauncherSetupState(
        isDefaultHome = isDefaultHome,
        isAccessibilityEnabled = isAccessibilityEnabled,
        showFallback = showFallback,
        settingsUnavailable = settingsUnavailable,
        requestDefaultHome = {
            val intent = LauncherSetup.createHomeRoleRequest(context)
            if (intent == null) {
                openHomeSettings()
            } else {
                settingsUnavailable = false
                val launched = runCatching { roleRequest.launch(intent) }.isSuccess
                if (!launched) openHomeSettings()
            }
        },
        openHomeSettings = { openHomeSettings() },
        openAccessibilitySettings = { LauncherAccessibilityService.openAccessibilitySettings(context) }
    )
}
