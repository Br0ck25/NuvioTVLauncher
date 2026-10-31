@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.apps.launchInstalledApp
import com.nuvio.tv.ui.theme.NuvioTheme

val InstalledAppTileWidth = 180.dp
val InstalledAppTileHeight = 128.dp

@Composable
fun InstalledAppArtwork(app: InstalledApp, modifier: Modifier = Modifier) {
    if (app.icon != null) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    } else {
        Icon(
            imageVector = Icons.Default.Android,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary,
            modifier = modifier
        )
    }
}

@Composable
fun InstalledAppTile(
    app: InstalledApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {}
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Card(
        onClick = onClick,
        modifier = modifier
            .width(InstalledAppTileWidth)
            .height(InstalledAppTileHeight)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape),
            focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape)
        ),
        scale = CardDefaults.scale(focusedScale = 1.03f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                InstalledAppArtwork(
                    app = app,
                    modifier = if (app.hasBanner) Modifier.fillMaxSize() else Modifier.size(64.dp)
                )
            }
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Shared by Classic and Grid; Modern uses the same tile inside its existing focus-aware row. */
@Composable
fun InstalledAppsRowSection(
    apps: List<InstalledApp>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    rowFocusRequester: FocusRequester = remember { FocusRequester() },
    focusedItemIndex: Int = -1,
    onItemFocused: (Int) -> Unit = {},
    horizontalPadding: Dp = 52.dp,
    showTitle: Boolean = true,
    focusRequesterForPackage: ((String) -> FocusRequester)? = null
) {
    val context = LocalContext.current
    val launchError = stringResource(R.string.installed_apps_launch_error)
    val currentOnItemFocused by rememberUpdatedState(onItemFocused)
    var lastIndex by remember { mutableIntStateOf(-1) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(apps) { requesters.keys.retainAll(apps.map { it.packageName }.toSet()) }
    LaunchedEffect(focusedItemIndex, apps) {
        val app = apps.getOrNull(focusedItemIndex) ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching {
            requesters.getOrPut(app.packageName) { FocusRequester() }.requestFocus()
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (showTitle) {
            Text(
                text = stringResource(R.string.installed_apps_title),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(start = horizontalPadding, bottom = 14.dp)
            )
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().focusRequester(rowFocusRequester).focusRestorer {
                requesters[apps.getOrNull(lastIndex.coerceAtLeast(0))?.packageName] ?: FocusRequester.Default
            }.focusGroup(),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                val requester = focusRequesterForPackage?.invoke(app.packageName)
                    ?: requesters.getOrPut(app.packageName) { FocusRequester() }
                LaunchedEffect(app.packageName) {
                    requesters[app.packageName] = requester
                }
                InstalledAppTile(
                    app = app,
                    modifier = Modifier.focusRequester(requester),
                    onFocused = {
                        lastIndex = index
                        currentOnItemFocused(index)
                    },
                    onClick = {
                        if (!launchInstalledApp(context, app)) {
                            Toast.makeText(context, launchError, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}
