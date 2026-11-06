@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.apps.launchInstalledApp
import com.nuvio.tv.ui.components.InstalledAppArtwork
import com.nuvio.tv.ui.screens.apps.AppContextMenuModal
import com.nuvio.tv.ui.screens.apps.AppsViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class DrawerScreenMode {
    MENU,
    ALL_APPS
}

@Composable
fun HomeSettingsDrawer(
    onDismiss: () -> Unit,
    onOpenNuvioSettings: () -> Unit,
    modifier: Modifier = Modifier,
    appsViewModel: AppsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val appsState by appsViewModel.state.collectAsState()
    var currentMode by remember { mutableStateOf(DrawerScreenMode.MENU) }
    var contextMenuApp by remember { mutableStateOf<InstalledApp?>(null) }
    val initialFocusRequester = remember { FocusRequester() }

    BackHandler {
        if (contextMenuApp != null) {
            contextMenuApp = null
        } else if (currentMode == DrawerScreenMode.ALL_APPS) {
            currentMode = DrawerScreenMode.MENU
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(currentMode) {
        runCatching { initialFocusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onDismiss()
            }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(420.dp)
                .padding(24.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { /* swallow click */ },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF16191E).copy(alpha = 0.96f)
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(24.dp))
            )
        ) {
            when (currentMode) {
                DrawerScreenMode.MENU -> {
                    SettingsMenuContent(
                        onOpenAllApps = { currentMode = DrawerScreenMode.ALL_APPS },
                        onOpenNuvioSettings = {
                            onOpenNuvioSettings()
                            onDismiss()
                        },
                        onOpenAndroidSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            onDismiss()
                        },
                        initialFocusRequester = initialFocusRequester
                    )
                }
                DrawerScreenMode.ALL_APPS -> {
                    AllAppsContent(
                        apps = appsState.apps,
                        onAppClick = { app ->
                            if (launchInstalledApp(context, app)) {
                                onDismiss()
                            }
                        },
                        onAppLongClick = { app ->
                            contextMenuApp = app
                        },
                        initialFocusRequester = initialFocusRequester
                    )
                }
            }
        }

        contextMenuApp?.let { app ->
            AppContextMenuModal(
                app = app,
                onDismiss = { contextMenuApp = null }
            )
        }
    }
}

@Composable
private fun SettingsMenuContent(
    onOpenAllApps: () -> Unit,
    onOpenNuvioSettings: () -> Unit,
    onOpenAndroidSettings: () -> Unit,
    initialFocusRequester: FocusRequester
) {
    val dateText = remember {
        runCatching {
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM d, yyyy"))
        }.getOrDefault("")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "NUVIO TV ",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFE67E22)
                    )
                    Text(
                        text = "launcher",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.settings_menu_settings_header),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFFE67E22),
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)
            )
        }

        item {
            DrawerActionRow(
                title = stringResource(R.string.settings_menu_all_apps),
                icon = Icons.Default.GridView,
                onClick = onOpenAllApps,
                modifier = Modifier.focusRequester(initialFocusRequester)
            )
        }

        item {
            DrawerActionRow(
                title = stringResource(R.string.settings_menu_nuvio_settings),
                icon = Icons.Default.Settings,
                onClick = onOpenNuvioSettings
            )
        }

        item {
            DrawerActionRow(
                title = stringResource(R.string.settings_menu_android_settings),
                icon = Icons.Default.Android,
                onClick = onOpenAndroidSettings
            )
        }
    }
}

@Composable
private fun AllAppsContent(
    apps: List<InstalledApp>,
    onAppClick: (InstalledApp) -> Unit,
    onAppLongClick: (InstalledApp) -> Unit,
    initialFocusRequester: FocusRequester
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = null,
                    tint = Color(0xFFE67E22),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.settings_menu_all_apps),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.settings_menu_tv_apps),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFFE67E22),
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)
            )
        }

        items(apps, key = { it.packageName }) { app ->
            val isFirst = app == apps.firstOrNull()
            Surface(
                onClick = { onAppClick(app) },
                onLongClick = { onAppLongClick(app) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .then(if (isFirst) Modifier.focusRequester(initialFocusRequester) else Modifier),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.05f),
                    focusedContainerColor = Color.White
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        InstalledAppArtwork(app = app, modifier = Modifier.size(28.dp))
                    }
                    Text(
                        text = app.displayLabel,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f),
            focusedContainerColor = Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
