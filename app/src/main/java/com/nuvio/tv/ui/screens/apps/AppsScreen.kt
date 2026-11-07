@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.apps

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.nuvio.tv.ui.components.InstalledAppTile
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import android.view.KeyEvent as AndroidKeyEvent
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.apps.launchInstalledApp
import com.nuvio.tv.core.apps.orderInstalledApps
import com.nuvio.tv.ui.components.rememberLauncherSetupState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A
}

private sealed interface AppsGridItem {
    val key: String

    data class Shortcut(
        override val key: String,
        val label: String,
        val iconVector: ImageVector,
        val onClick: () -> Unit
    ) : AppsGridItem

    data class App(
        val app: InstalledApp
    ) : AppsGridItem {
        override val key: String get() = app.packageName
    }
}

@Composable
fun AppsScreen(
    onManageHomeApps: () -> Unit = {},
    viewModel: AppsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val setup = rememberLauncherSetupState()
    val appsState by viewModel.state.collectAsState()
    val apps = appsState.apps
    var contextMenuTargetKey by remember { mutableStateOf<String?>(null) }

    fun launch(intent: Intent) {
        runCatching { context.startActivity(intent) }
    }

    val defaultLauncherLabel = if (setup.isDefaultHome) {
        stringResource(R.string.apps_default_launcher_active)
    } else {
        stringResource(R.string.apps_set_default_launcher)
    }
    val systemHomeSettingsLabel = stringResource(R.string.settings_open_home_settings)
    val homeOverrideLabel = stringResource(
        if (setup.isAccessibilityActive) R.string.apps_home_override_active
        else if (setup.isAccessibilityEnabled) R.string.apps_home_override_pending
        else R.string.apps_home_override
    )
    val manageHomeAppsLabel = stringResource(R.string.apps_manage_home_row)

    val orderedGridItems = remember(
        apps,
        appsState.order,
        setup.isDefaultHome,
        setup.showFallback,
        setup.isAccessibilityActive,
        setup.isAccessibilityEnabled,
        defaultLauncherLabel,
        systemHomeSettingsLabel,
        homeOverrideLabel,
        manageHomeAppsLabel
    ) {
        val defaultItems = buildList<AppsGridItem> {
            add(
                AppsGridItem.Shortcut(
                    key = "__shortcut_default_launcher__",
                    label = defaultLauncherLabel,
                    iconVector = if (setup.isDefaultHome) Icons.Default.CheckCircle else Icons.Default.Home,
                    onClick = setup.requestDefaultHome
                )
            )
            if (setup.showFallback) {
                add(
                    AppsGridItem.Shortcut(
                        key = "__shortcut_system_home_settings__",
                        label = systemHomeSettingsLabel,
                        iconVector = Icons.Default.Settings,
                        onClick = setup.openHomeSettings
                    )
                )
            }
            add(
                AppsGridItem.Shortcut(
                    key = "__shortcut_home_override__",
                    label = homeOverrideLabel,
                    iconVector = if (setup.isAccessibilityActive) Icons.Default.CheckCircle else Icons.Default.Home,
                    onClick = setup.openAccessibilitySettings
                )
            )
            add(
                AppsGridItem.Shortcut(
                    key = "__shortcut_manage_home_apps__",
                    label = manageHomeAppsLabel,
                    iconVector = Icons.Default.Tune,
                    onClick = onManageHomeApps
                )
            )
            apps.forEach { app ->
                add(AppsGridItem.App(app))
            }
        }
        val itemByKey = defaultItems.associateBy { it.key }
        val orderedKeys = orderInstalledApps(
            availablePackages = defaultItems.map { it.key },
            savedOrder = appsState.order,
            removedPackages = emptySet<String>()
        )
        orderedKeys.mapNotNull { itemByKey[it] }
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
                columns = GridCells.Adaptive(minSize = 180.dp),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                modifier = Modifier.fillMaxSize()
            ) {
                items(orderedGridItems, key = { it.key }) { gridItem ->
                    when (gridItem) {
                        is AppsGridItem.Shortcut -> {
                            ShortcutTile(
                                label = gridItem.label,
                                iconVector = gridItem.iconVector,
                                onClick = gridItem.onClick,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                onLongClick = {
                                    contextMenuTargetKey = gridItem.key
                                }
                            )
                        }
                        is AppsGridItem.App -> {
                            val app = gridItem.app
                            InstalledAppTile(
                                app = app,
                                fillParentWidth = true,
                                onClick = {
                                    if (!launchInstalledApp(context, app)) {
                                        launch(app.launchIntent())
                                    }
                                },
                                onLongClick = {
                                    contextMenuTargetKey = app.packageName
                                }
                            )
                        }
                    }
                }
            }
        }

        val activeTarget = orderedGridItems.firstOrNull { it.key == contextMenuTargetKey }
        if (activeTarget != null) {
            val currentIndex = orderedGridItems.indexOfFirst { it.key == activeTarget.key }
            val allKeys = remember(orderedGridItems) { orderedGridItems.map { it.key } }
            val menuViewModel: AppContextMenuViewModel = hiltViewModel()
            when (activeTarget) {
                is AppsGridItem.App -> {
                    val app = activeTarget.app
                    AppContextMenuModal(
                        app = app,
                        onDismiss = { contextMenuTargetKey = null },
                        onMoveLeft = if (currentIndex > 0) {
                            {
                                menuViewModel.moveApp(app.packageName, -1, allKeys)
                            }
                        } else null,
                        onMoveRight = if (currentIndex in 0 until orderedGridItems.size - 1) {
                            {
                                menuViewModel.moveApp(app.packageName, 1, allKeys)
                            }
                        } else null,
                        onManageOrder = {
                            contextMenuTargetKey = null
                            onManageHomeApps()
                        },
                        viewModel = menuViewModel
                    )
                }
                is AppsGridItem.Shortcut -> {
                    val shortcutDummyApp = remember(activeTarget.key, activeTarget.label) {
                        InstalledApp(
                            packageName = activeTarget.key,
                            label = activeTarget.label,
                            componentName = ComponentName(activeTarget.key, ""),
                            icon = null,
                            hasBanner = false
                        )
                    }
                    AppContextMenuModal(
                        app = shortcutDummyApp,
                        onDismiss = { contextMenuTargetKey = null },
                        isShortcutTile = true,
                        shortcutIconVector = activeTarget.iconVector,
                        onCustomOpen = {
                            contextMenuTargetKey = null
                            activeTarget.onClick()
                        },
                        onMoveLeft = if (currentIndex > 0) {
                            {
                                menuViewModel.moveApp(activeTarget.key, -1, allKeys)
                            }
                        } else null,
                        onMoveRight = if (currentIndex in 0 until orderedGridItems.size - 1) {
                            {
                                menuViewModel.moveApp(activeTarget.key, 1, allKeys)
                            }
                        } else null,
                        onManageOrder = {
                            contextMenuTargetKey = null
                            onManageHomeApps()
                        },
                        viewModel = menuViewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutTile(
    label: String,
    iconVector: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    var selectDownSeen by remember { mutableStateOf(false) }
    var selectDownAtMillis by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressTimeoutMs = remember { android.view.ViewConfiguration.getLongPressTimeout().toLong() }

    Card(
        onClick = onClick,
        modifier = modifier
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 && onLongClick != null) {
                        onLongClick()
                    }
                    return@onPreviewKeyEvent true
                }
                if (isSelectKey(native.keyCode)) {
                    when (native.action) {
                        AndroidKeyEvent.ACTION_DOWN -> {
                            if (native.repeatCount == 0) {
                                selectDownSeen = true
                                selectDownAtMillis = native.eventTime
                                longPressTriggered = false
                            } else if (selectDownSeen && !longPressTriggered && onLongClick != null) {
                                val heldMs = native.eventTime - selectDownAtMillis
                                if (native.isLongPress || heldMs >= longPressTimeoutMs) {
                                    longPressTriggered = true
                                    selectDownSeen = false
                                    onLongClick()
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                        AndroidKeyEvent.ACTION_UP -> {
                            val wasDown = selectDownSeen
                            val wasLong = longPressTriggered
                            val heldMs = native.eventTime - selectDownAtMillis
                            selectDownSeen = false
                            longPressTriggered = false
                            if (wasDown && !wasLong && !native.isCanceled) {
                                if (onLongClick != null && heldMs >= longPressTimeoutMs) {
                                    onLongClick()
                                } else {
                                    onClick()
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = shape
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.03f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(NuvioTheme.spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
