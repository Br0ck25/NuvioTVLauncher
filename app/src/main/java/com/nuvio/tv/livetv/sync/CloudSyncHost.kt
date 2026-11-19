package com.nuvio.tv.livetv.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudSyncEntryPoint {
    fun cloudSync(): LiveTvCloudSync
}

/**
 * Starts Setup Sync when the app opens (so it reacts to a sign-in right away, from any
 * screen) and shows its two one-time questions on top of whatever screen you're on.
 * Lives next to the app's navigation.
 */
@Composable
fun CloudSyncHost() {
    val context = LocalContext.current
    val sync = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, CloudSyncEntryPoint::class.java).cloudSync() }.getOrNull()
    } ?: return
    LaunchedEffect(Unit) { runCatching { sync.start() } }

    val state by sync.state.collectAsState()
    val prompt = state.prompt ?: return
    val first = remember(prompt) { FocusRequester() }
    LaunchedEffect(prompt) { delay(80); runCatching { first.requestFocus() } }

    when (prompt) {
        CloudPrompt.BackUp -> LiveDialog(onDismiss = { sync.answerBackUp(false) }, width = 520.dp) {
            LiveText(stringResource(R.string.iptv_sync_host_title), color = NuvioTheme.colors.Secondary, size = 13.sp, weight = FontWeight.SemiBold)
            LiveText(stringResource(R.string.iptv_sync_host_question), size = 22.sp, weight = FontWeight.Bold, maxLines = 3)
            Spacer(Modifier.height(6.dp))
            LiveText(
                stringResource(R.string.iptv_sync_host_help),
                color = NuvioTheme.colors.TextSecondary, size = 14.sp, maxLines = 6
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuItem(stringResource(R.string.iptv_sync_host_save), Modifier.focusRequester(first)) { sync.answerBackUp(true) }
                MenuItem(stringResource(R.string.iptv_sync_host_not_now)) { sync.answerBackUp(false) }
            }
        }
        CloudPrompt.Choose -> LiveDialog(onDismiss = { sync.answerChoose(CloudChoice.Later) }, width = 520.dp) {
            LiveText(stringResource(R.string.iptv_sync_host_title), color = NuvioTheme.colors.Secondary, size = 13.sp, weight = FontWeight.SemiBold)
            LiveText(stringResource(R.string.iptv_sync_host_saved_title), size = 22.sp, weight = FontWeight.Bold, maxLines = 3)
            Spacer(Modifier.height(6.dp))
            LiveText(
                stringResource(R.string.iptv_sync_host_choose_help),
                color = NuvioTheme.colors.TextSecondary, size = 14.sp, maxLines = 6
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuItem(stringResource(R.string.iptv_sync_host_combine), Modifier.focusRequester(first)) { sync.answerChoose(CloudChoice.Combine) }
                MenuItem(stringResource(R.string.iptv_sync_host_use_saved)) { sync.answerChoose(CloudChoice.UseSaved) }
                MenuItem(stringResource(R.string.iptv_sync_host_not_now)) { sync.answerChoose(CloudChoice.Later) }
            }
        }
    }
}
