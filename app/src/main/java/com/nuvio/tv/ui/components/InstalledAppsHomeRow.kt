package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledAppsViewModel
import com.nuvio.tv.livetv.calendar.SideMenuPages
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * "Apps" row at the bottom of Home: the installed apps, like a TV launcher's app row.
 * Follows the Apps page: when Apps is turned off in Settings → Menu & Features, the row goes too.
 */
@Composable
fun InstalledAppsHomeRow(
    startPadding: Dp,
    titleStyle: TextStyle? = null,
    viewModel: InstalledAppsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val prefs = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).liveTvPreferences()
    }
    val settings by prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val hidden = settings?.let { SideMenuPages.hidden(it.sideMenuHidden) } ?: return
    if (SideMenuPages.APPS in hidden) return
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<com.nuvio.tv.core.apps.InstalledApp?>(null) }
    if (apps.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
        Text(
            text = stringResource(R.string.apps_title),
            style = titleStyle ?: MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = startPadding, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = startPadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(apps, key = { it.packageName }) { app ->
                InstalledAppCard(
                    app = app,
                    loadImage = viewModel::image,
                    onClick = { viewModel.launch(app) },
                    onLongClick = { actionsFor = app },
                    width = 160.dp
                )
            }
        }
    }
    actionsFor?.let { app ->
        val index = apps.indexOfFirst { it.packageName == app.packageName }
        if (index < 0) actionsFor = null else InstalledAppActionsDialog(
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
