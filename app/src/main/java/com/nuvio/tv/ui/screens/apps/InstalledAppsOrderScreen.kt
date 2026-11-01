@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.ui.components.InstalledAppArtwork
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@Composable
fun InstalledAppsOrderScreen(
    onBackPress: () -> Unit,
    viewModel: InstalledAppsOrderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    BackHandler { onBackPress() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = stringResource(R.string.installed_apps_manage_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.installed_apps_manage_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBackPress) {
                    Text(stringResource(R.string.action_back))
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.installed_apps_in_row, state.apps.size),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }
        when {
            state.isLoading -> item(key = "loading") { LoadingIndicator() }
            state.apps.isEmpty() -> item(key = "empty") {
                Text(
                    text = stringResource(
                        if (state.removedApps.isEmpty()) R.string.installed_apps_none else R.string.installed_apps_all_removed
                    ),
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        itemsIndexed(state.apps, key = { _, app -> "app_${app.packageName}" }) { index, app ->
            InstalledAppOrderItem(
                app = app,
                canMoveLeft = index > 0,
                canMoveRight = index < state.apps.lastIndex,
                isRemoved = false,
                onMove = { direction ->
                    viewModel.move(app.packageName, direction)
                    scope.launch { listState.animateScrollToItem((index + 1 + direction).coerceAtLeast(0)) }
                },
                onRemove = { viewModel.remove(app.packageName) }
            )
        }
        if (state.removedApps.isNotEmpty()) {
            item(key = "removed_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.installed_apps_removed, state.removedApps.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioTheme.colors.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.installed_apps_removed_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                    Button(onClick = { viewModel.restoreAll() }) {
                        Text(stringResource(R.string.installed_apps_restore_all))
                    }
                }
            }
            itemsIndexed(state.removedApps, key = { _, app -> "removed_${app.packageName}" }) { _, app ->
                InstalledAppOrderItem(app = app, isRemoved = true, onRestore = { viewModel.restore(app.packageName) })
            }
        }
    }
}

@Composable
private fun InstalledAppOrderItem(
    app: InstalledApp,
    canMoveLeft: Boolean = false,
    canMoveRight: Boolean = false,
    isRemoved: Boolean = false,
    onMove: (Int) -> Unit = {},
    onRemove: () -> Unit = {},
    onRestore: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InstalledAppArtwork(app, Modifier.size(width = 96.dp, height = 54.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        }
        if (canMoveLeft || canMoveRight) {
            Button(onClick = { onMove(-1) }, enabled = canMoveLeft) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.installed_apps_move_left, app.label))
            }
            Button(onClick = { onMove(1) }, enabled = canMoveRight) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.installed_apps_move_right, app.label))
            }
        }
        if (!isRemoved) {
            Button(
                onClick = onRemove,
                colors = ButtonDefaults.colors(contentColor = NuvioTheme.colors.Error)
            ) {
                Icon(Icons.Default.DeleteOutline, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.installed_apps_remove, app.label))
            }
        } else {
            Button(onClick = onRestore) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.installed_apps_restore, app.label))
            }
        }
    }
}
