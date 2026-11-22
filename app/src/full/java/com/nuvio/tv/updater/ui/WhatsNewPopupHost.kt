package com.nuvio.tv.updater.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.R
import com.nuvio.tv.updater.WhatsNewViewModel

/** The release notes of the installed version, shown once after an update. [suppress] holds it back, e.g. during playback. */
@Composable
fun WhatsNewPopupHost(suppress: Boolean = false) {
    val viewModel: WhatsNewViewModel = hiltViewModel()
    val notes by viewModel.notes.collectAsState()
    val shown = notes ?: return
    if (suppress) return
    UpdateReleaseNotesDialog(
        update = shown,
        onDismiss = viewModel::dismiss,
        dialogTitle = stringResource(R.string.whats_new_title)
    )
}
