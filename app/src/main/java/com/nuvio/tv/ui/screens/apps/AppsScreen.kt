package com.nuvio.tv.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledAppsViewModel
import com.nuvio.tv.ui.components.InstalledAppCard
import com.nuvio.tv.ui.theme.NuvioTheme

/** The Apps page: every app on the device, in a grid. OK opens one; long-press opens its system page. */
@Composable
fun AppsScreen(viewModel: InstalledAppsViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    var actionsFor by remember { mutableStateOf<com.nuvio.tv.core.apps.InstalledApp?>(null) }
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) runCatching { first.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 28.dp, start = 48.dp, end = 48.dp)
    ) {
        Text(
            text = stringResource(R.string.apps_title),
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.apps_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(176.dp),
                contentPadding = PaddingValues(top = 18.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.packageName }) { app ->
                    InstalledAppCard(
                        app = app,
                        loadImage = viewModel::image,
                        onClick = { viewModel.launch(app) },
                        onLongClick = { actionsFor = app },
                        modifier = if (app == apps.first()) Modifier.focusRequester(first) else Modifier,
                        width = 176.dp
                    )
                }
            }
        }
    }
    actionsFor?.let { app ->
        val index = apps.indexOfFirst { it.packageName == app.packageName }
        if (index < 0) actionsFor = null else com.nuvio.tv.ui.components.InstalledAppActionsDialog(
            app = app,
            canMoveEarlier = index > 0,
            canMoveLater = index < apps.lastIndex,
            onOpen = { viewModel.launch(app) },
            onMoveEarlier = { viewModel.move(app, -1) },
            onMoveLater = { viewModel.move(app, 1) },
            onAppInfo = { viewModel.openAppInfo(app) },
            onDismiss = { actionsFor = null }
        )
    }
}
