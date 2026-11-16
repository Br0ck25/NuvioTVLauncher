package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import com.nuvio.tv.livetv.remote.RemoteButtons
import com.nuvio.tv.ui.theme.NuvioTheme

private const val SECTION_EVERYWHERE = "everywhere"
private const val SECTION_LIVE_TV = "livetv"

/**
 * Settings → Remote Buttons: one page for both ways of giving the remote's buttons other jobs.
 * "Everywhere, and the Home button" is the Button Mapper (it works across the launcher, and for other apps where Android allows).
 * "Live TV screens" gives buttons other jobs on the guide, full screen, Multiview, Sports and the other Live TV screens, per profile.
 * A button set in both is run by the Button Mapper; each section says so.
 */
@Composable
internal fun RemoteButtonsHubContent(initialFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { RemoteButtons.start(context) }
    val liveTv by RemoteButtons.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(SECTION_EVERYWHERE) }
    val first = initialFocusRequester ?: remember { FocusRequester() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        SettingsGroupCard(title = "Remote Buttons", modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = "Everywhere, and the Home Button",
                subtitle = "Button Mapper: give buttons other jobs across the launcher, and choose what Home does",
                value = if (section == SECTION_EVERYWHERE) "Showing" else "Show",
                onClick = { section = SECTION_EVERYWHERE },
                modifier = Modifier.focusRequester(first)
            )
            SettingsActionRow(
                title = "Live TV Screens",
                subtitle = "Other jobs for buttons on the guide, full screen, Catch-up, Multiview, Sports and more. Saved per profile",
                value = if (section == SECTION_LIVE_TV) "Showing" else if (liveTv.changedTotal > 0) "${liveTv.changedTotal} changed" else "Show",
                onClick = { section = SECTION_LIVE_TV }
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                SECTION_LIVE_TV -> RemoteButtonsSettingsContent()
                else -> ButtonMapperSettingsContent()
            }
        }
    }
}
