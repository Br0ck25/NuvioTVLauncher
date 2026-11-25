package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun VodDownloadDialog(
    destinations: List<RecordingStorage.Choice>,
    supported: Boolean,
    smbConfigured: Boolean,
    onDownload: (destinationId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedId by remember(destinations) {
        mutableStateOf(destinations.firstOrNull { it.isDevice }?.id ?: destinations.firstOrNull()?.id.orEmpty())
    }
    var permissionConfirmed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(430.dp)
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(NuvioTheme.radii.xl))
                .padding(NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            val smbSetupRequired = stringResource(R.string.vod_download_smb_setup_required)
            Text(stringResource(R.string.vod_download_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(if (supported) R.string.vod_download_supported else R.string.vod_download_unsupported),
                style = MaterialTheme.typography.bodyMedium
            )
            destinations.forEach { choice ->
                val enabled = !choice.isSmb || smbConfigured
                Button(
                    onClick = { selectedId = choice.id },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = buildString {
                            append(if (choice.id == selectedId) "●  " else "○  ")
                            append(choice.label)
                            if (choice.isSmb && !smbConfigured) append(" · ").append(smbSetupRequired)
                        },
                        maxLines = 1
                    )
                }
            }
            if (!smbConfigured) {
                Text(stringResource(R.string.vod_download_smb_setup_hint), style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { permissionConfirmed = !permissionConfirmed },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (permissionConfirmed) R.string.vod_download_permission_confirmed
                        else R.string.vod_download_permission_prompt
                    )
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { onDownload(selectedId) },
                    enabled = supported && permissionConfirmed && destinations.any { it.id == selectedId && (!it.isSmb || smbConfigured) }
                ) { Text(stringResource(R.string.vod_download_action)) }
            }
        }
    }
}
