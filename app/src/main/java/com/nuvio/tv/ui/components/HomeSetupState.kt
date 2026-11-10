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
import com.nuvio.tv.core.setup.HomeSetup

internal data class HomeSetupState(
    val isDefaultHome: Boolean,
    val homeSettingsUnavailable: Boolean,
    val roleRequestDenied: Boolean,
    val requestDefaultHome: () -> Unit,
    val openHomeSettings: () -> Unit,
    val refresh: () -> Unit
)

/**
 * Live status of "is Nuvio the Home app", plus the two Android surfaces that can change it.
 *
 * Only the real HOME resolver is trusted: on TV firmware a role dialog can report success without
 * changing where HOME is dispatched, so the answer is re-read on every resume.
 */
@Composable
internal fun rememberHomeSetupState(): HomeSetupState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isDefaultHome by remember { mutableStateOf(HomeSetup.isDefaultHome(context)) }
    var homeSettingsUnavailable by remember { mutableStateOf(!HomeSetup.homeSettingsAvailable(context)) }
    var roleRequestDenied by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        isDefaultHome = HomeSetup.isDefaultHome(context)
        homeSettingsUnavailable = !HomeSetup.homeSettingsAvailable(context)
        if (isDefaultHome) roleRequestDenied = false
    }

    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // RESULT_OK alone is not proof on OEM TV firmware. Check the selected Home app again.
        refresh()
        if (!isDefaultHome) roleRequestDenied = true
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
        homeSettingsUnavailable = !HomeSetup.openHomeSettings(context)
    }

    fun requestDefaultHome() {
        // An already working Home app must not open redundant configuration.
        if (HomeSetup.isDefaultHome(context)) {
            refresh()
            return
        }
        val intent = HomeSetup.createHomeRoleRequest(context)
        if (intent == null) {
            openHomeSettings()
            return
        }
        homeSettingsUnavailable = false
        if (runCatching { roleRequest.launch(intent) }.isFailure) openHomeSettings()
    }

    return HomeSetupState(
        isDefaultHome = isDefaultHome,
        homeSettingsUnavailable = homeSettingsUnavailable,
        roleRequestDenied = roleRequestDenied,
        requestDefaultHome = { requestDefaultHome() },
        openHomeSettings = { openHomeSettings() },
        refresh = { refresh() }
    )
}
