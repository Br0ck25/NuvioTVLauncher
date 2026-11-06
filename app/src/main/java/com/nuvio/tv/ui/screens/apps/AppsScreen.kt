@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.apps

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.apps.launchInstalledApp
import com.nuvio.tv.ui.components.rememberLauncherSetupState
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun AppsScreen(
    onManageHomeApps: () -> Unit = {},
    viewModel: AppsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val setup = rememberLauncherSetupState()
    val appsState by viewModel.state.collectAsState()
    val apps = appsState.apps
    var contextMenuApp by remember { mutableStateOf<InstalledApp?>(null) }

    fun launch(intent: Intent) {
        runCatching { context.startActivity(intent) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = NuvioTheme.spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.nav_apps),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "default_launcher_toggle") {
                    AppTile(
                        label = if (setup.isDefaultHome) {
                            stringResource(R.string.apps_default_launcher_active)
                        } else {
                            stringResource(R.string.apps_set_default_launcher)
                        },
                        iconVector = if (setup.isDefaultHome) Icons.Default.CheckCircle else Icons.Default.Home,
                        icon = null,
                        onClick = setup.requestDefaultHome
                    )
                }
                if (setup.showFallback) {
                    item(key = "system_home_settings") {
                        AppTile(
                            label = stringResource(R.string.settings_open_home_settings),
                            iconVector = Icons.Default.Settings,
                            icon = null,
                            onClick = setup.openHomeSettings
                        )
                    }
                }
                // Some TV firmware blocks third-party default HOME apps. Keep the accessibility
                // workaround reachable even when Home settings opens but offers no usable picker.
                item(key = "home_button_override") {
                    AppTile(
                        label = stringResource(
                            if (setup.isAccessibilityActive) R.string.apps_home_override_active
                            else if (setup.isAccessibilityEnabled) R.string.apps_home_override_pending
                            else R.string.apps_home_override
                        ),
                        iconVector = if (setup.isAccessibilityActive) Icons.Default.CheckCircle else Icons.Default.Home,
                        icon = null,
                        onClick = setup.openAccessibilitySettings
                    )
                }
                item(key = "manage_home_apps") {
                    AppTile(
                        label = stringResource(R.string.apps_manage_home_row),
                        iconVector = Icons.Default.Tune,
                        icon = null,
                        onClick = onManageHomeApps
                    )
                }
                item(key = "android_settings") {
                    AppTile(
                        label = stringResource(R.string.apps_android_settings),
                        iconVector = Icons.Default.Settings,
                        icon = null,
                        onClick = {
                            launch(
                                Intent(android.provider.Settings.ACTION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    )
                }
                items(apps, key = { it.packageName }) { app ->
                    AppTile(
                        label = app.displayLabel,
                        iconVector = null,
                        icon = app.displayIcon,
                        onClick = {
                            if (!launchInstalledApp(context, app)) {
                                launch(app.launchIntent())
                            }
                        },
                        onLongClick = {
                            contextMenuApp = app
                        }
                    )
                }
            }
        }

        if (contextMenuApp != null) {
            AppContextMenuModal(
                app = contextMenuApp!!,
                onDismiss = { contextMenuApp = null }
            )
        }
    }
}

@Composable
private fun AppTile(
    label: String,
    icon: ImageBitmap?,
    iconVector: ImageVector? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(NuvioTheme.spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(width = 96.dp, height = 54.dp)
                    )
                } else {
                    Icon(
                        imageVector = iconVector ?: Icons.Default.Settings,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
