@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.updater.ui

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.UpdateUiState
import com.nuvio.tv.updater.model.AppUpdate

@Composable
internal fun UpdateBanner(
    state: UpdateUiState,
    update: AppUpdate,
    isInstalledUpdate: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onShowReleaseNotes: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val progress = (state.downloadProgress ?: 0f).coerceIn(0f, 1f)
    val actionFocus = remember { FocusRequester() }

    LaunchedEffect(update.tag, isInstalledUpdate, state.isDownloading, state.downloadedApkPath) {
        actionFocus.requestFocusAfterFrames()
    }

    val title = if (isInstalledUpdate) {
        stringResource(R.string.update_installed_title)
    } else {
        stringResource(R.string.update_available)
    }
    val subtitle = listOfNotNull(
        update.tag,
        update.assetSizeBytes
            ?.takeUnless { isInstalledUpdate }
            ?.let { Formatter.formatShortFileSize(context, it) }
    ).joinToString(separator = " • ")
    val status = when {
        isInstalledUpdate -> stringResource(R.string.update_installed_message)
        state.errorMessage != null -> state.errorMessage.orEmpty()
        state.isDownloading && state.downloadProgress != null -> stringResource(
            R.string.update_downloading_progress,
            (progress * 100).toInt().coerceIn(0, 100)
        )
        state.isDownloading -> stringResource(R.string.update_preparing_download)
        state.downloadedApkPath != null -> stringResource(R.string.update_download_complete)
        else -> stringResource(R.string.update_new_version_message)
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = 640.dp,
        suppressFirstKeyUp = false
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.errorMessage != null) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary
        )

        if (state.isDownloading || state.downloadedApkPath != null) {
            UpdateProgressBar(
                progress = if (state.isDownloading) state.downloadProgress else 1f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isInstalledUpdate) {
                Button(
                    onClick = {
                        when {
                            state.isDownloading -> Unit
                            state.downloadedApkPath != null -> onInstall()
                            else -> onDownload()
                        }
                    },
                    modifier = Modifier.focusRequester(actionFocus),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.Secondary,
                        focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                        contentColor = NuvioTheme.colors.OnSecondary,
                        focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50))
                ) {
                    Text(
                        text = when {
                            state.isDownloading -> stringResource(R.string.update_downloading_ellipsis)
                            state.downloadedApkPath != null -> stringResource(R.string.update_install)
                            state.errorMessage != null -> stringResource(R.string.action_retry)
                            else -> stringResource(R.string.update_download_now)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!state.isDownloading || isInstalledUpdate) {
                Button(
                    onClick = onDismiss,
                    modifier = if (isInstalledUpdate) Modifier.focusRequester(actionFocus) else Modifier,
                    colors = secondaryButtonColors(),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50))
                ) {
                    Text(
                        text = if (isInstalledUpdate) {
                            stringResource(R.string.update_close)
                        } else {
                            stringResource(R.string.update_later)
                        }
                    )
                }
            }

            if (!isInstalledUpdate && update.notes.isNotBlank()) {
                Button(
                    onClick = onShowReleaseNotes,
                    colors = secondaryButtonColors(),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50))
                ) {
                    Text(stringResource(R.string.update_release_notes))
                }
            }
        }
    }
}

@Composable
private fun UpdateProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val target = (progress ?: 0f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 180),
        label = "updateCardProgress"
    )
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(NuvioTheme.colors.BackgroundCard)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .background(NuvioTheme.colors.Secondary)
        )
    }
}

@Composable
private fun secondaryButtonColors() = ButtonDefaults.colors(
    containerColor = NuvioTheme.colors.BackgroundCard,
    focusedContainerColor = NuvioTheme.colors.FocusBackground,
    contentColor = NuvioTheme.colors.TextPrimary,
    focusedContentColor = NuvioTheme.colors.Primary
)
