@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.apps

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.ui.components.InstalledAppArtwork
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.ambient.AmbientDialogEffect
import com.nuvio.tv.ui.theme.NuvioTheme

val LocalNavigateToManageHomeApps = compositionLocalOf<(() -> Unit)?> { null }

private enum class MenuScreenMode {
    MAIN,
    RENAME,
    ICON_PICKER,
    PACK_ICONS
}

@Composable
fun AppContextMenuModal(
    app: InstalledApp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onMoveLeft: (() -> Unit)? = null,
    onMoveRight: (() -> Unit)? = null,
    onRemoveFromRow: (() -> Unit)? = null,
    onManageOrder: (() -> Unit)? = null,
    isShortcutTile: Boolean = false,
    shortcutIconVector: ImageVector? = null,
    onCustomOpen: (() -> Unit)? = null,
    viewModel: AppContextMenuViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var currentMode by remember { mutableStateOf(MenuScreenMode.MAIN) }
    var renameText by remember(app.displayLabel) { mutableStateOf(app.displayLabel) }
    val initialFocusRequester = remember { FocusRequester() }
    var isReady by remember { mutableStateOf(false) }
    var backDownSeen by remember(currentMode) { mutableStateOf(false) }
    val fallbackManageOrder = LocalNavigateToManageHomeApps.current
    val effectiveOnManageOrder = onManageOrder ?: fallbackManageOrder

    LaunchedEffect(Unit) {
        delay(120)
        isReady = true
    }

    val handleBack = {
        when (currentMode) {
            MenuScreenMode.MAIN -> onDismiss()
            MenuScreenMode.RENAME -> currentMode = MenuScreenMode.MAIN
            MenuScreenMode.ICON_PICKER -> currentMode = MenuScreenMode.MAIN
            MenuScreenMode.PACK_ICONS -> {
                viewModel.clearSelectedPack()
                currentMode = MenuScreenMode.ICON_PICKER
            }
        }
    }

    LaunchedEffect(currentMode) {
        repeat(2) { withFrameNanos { } }
        runCatching { initialFocusRequester.requestFocus() }
        isReady = true
    }

    Dialog(
        onDismissRequest = handleBack,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AmbientDialogEffect(onDismiss)
        BackHandler(onBack = handleBack)

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_HOME) {
                        if (native.action == AndroidKeyEvent.ACTION_UP && !native.isCanceled) {
                            onDismiss()
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_BACK ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE
                    ) {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            backDownSeen = true
                        } else if (native.action == AndroidKeyEvent.ACTION_UP && backDownSeen) {
                            backDownSeen = false
                            handleBack()
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            isReady = true
                        }
                        if (!isReady) {
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
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
                shape = RoundedCornerShape(24.dp),
                colors = SurfaceDefaults.colors(
                    containerColor = Color(0xFF16191E).copy(alpha = 0.96f)
                ),
                border = Border(
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                )
            ) {
                when (currentMode) {
                    MenuScreenMode.MAIN -> {
                        MainMenuContent(
                            app = app,
                            isShortcutTile = isShortcutTile,
                            shortcutIconVector = shortcutIconVector,
                            canExecuteAction = isReady,
                            onOpen = {
                                if (isReady) {
                                    if (onCustomOpen != null) {
                                        onCustomOpen()
                                    } else {
                                        viewModel.launchApp(app)
                                    }
                                    onDismiss()
                                }
                            },
                            onSettings = {
                                if (isReady) {
                                    viewModel.openAppSettings(app.packageName)
                                    onDismiss()
                                }
                            },
                            onUninstall = {
                                if (isReady) {
                                    viewModel.uninstallApp(app.packageName)
                                    onDismiss()
                                }
                            },
                            onMoveLeft = onMoveLeft?.let { move ->
                                {
                                    if (isReady) move()
                                }
                            },
                            onMoveRight = onMoveRight?.let { move ->
                                {
                                    if (isReady) move()
                                }
                            },
                            onRemoveFromRow = onRemoveFromRow?.let { remove ->
                                {
                                    if (isReady) {
                                        remove()
                                        onDismiss()
                                    }
                                }
                            },
                            onManageOrder = effectiveOnManageOrder?.let { manage ->
                                {
                                    if (isReady) {
                                        onDismiss()
                                        manage()
                                    }
                                }
                            },
                            onRenameClick = { if (isReady) currentMode = MenuScreenMode.RENAME },
                            onChangeIconClick = { if (isReady) currentMode = MenuScreenMode.ICON_PICKER },
                            onResetClick = {
                                if (isReady) {
                                    viewModel.resetAppCustomization(app.packageName)
                                    onDismiss()
                                }
                            },
                            initialFocusRequester = initialFocusRequester
                        )
                    }
                    MenuScreenMode.RENAME -> {
                        RenameDialogContent(
                            currentText = renameText,
                            onTextChange = { renameText = it },
                            onSave = {
                                viewModel.renameApp(app.packageName, renameText)
                                onDismiss()
                            },
                            onReset = {
                                viewModel.renameApp(app.packageName, "")
                                onDismiss()
                            },
                            onCancel = { currentMode = MenuScreenMode.MAIN },
                            initialFocusRequester = initialFocusRequester
                        )
                    }
                    MenuScreenMode.ICON_PICKER -> {
                        IconPickerContent(
                            app = app,
                            uiState = uiState,
                            onSelectOption = { iconRef ->
                                viewModel.setAppIcon(app.packageName, iconRef)
                                onDismiss()
                            },
                            onSelectPack = { pack ->
                                viewModel.loadPackIcons(pack.packageName)
                                currentMode = MenuScreenMode.PACK_ICONS
                            },
                            onBack = { currentMode = MenuScreenMode.MAIN },
                            initialFocusRequester = initialFocusRequester
                        )
                    }
                    MenuScreenMode.PACK_ICONS -> {
                        PackIconsContent(
                            packPackage = uiState.selectedPackPackage.orEmpty(),
                            icons = uiState.selectedPackIcons,
                            isLoading = uiState.isLoadingPackIcons,
                            onSelectIcon = { drawableName ->
                                viewModel.applyIconPackToApp(
                                    packageName = app.packageName,
                                    iconPackPackage = uiState.selectedPackPackage.orEmpty(),
                                    drawableName = drawableName
                                )
                                onDismiss()
                            },
                            onApplyToAll = {
                                viewModel.setGlobalIconPack(uiState.selectedPackPackage)
                                onDismiss()
                            },
                            onBack = {
                                viewModel.clearSelectedPack()
                                currentMode = MenuScreenMode.ICON_PICKER
                            },
                            initialFocusRequester = initialFocusRequester
                        )
                    }
                }
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

@Composable
private fun MainMenuContent(
    app: InstalledApp,
    isShortcutTile: Boolean,
    shortcutIconVector: ImageVector?,
    canExecuteAction: Boolean,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
    onUninstall: () -> Unit,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
    onRemoveFromRow: (() -> Unit)?,
    onManageOrder: (() -> Unit)?,
    onRenameClick: () -> Unit,
    onChangeIconClick: () -> Unit,
    onResetClick: () -> Unit,
    initialFocusRequester: FocusRequester
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isShortcutTile && shortcutIconVector != null) {
                    Icon(
                        imageVector = shortcutIconVector,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    InstalledAppArtwork(app = app, modifier = Modifier.size(48.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isShortcutTile) {
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                MenuItemRow(
                    title = stringResource(R.string.app_menu_open),
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onOpen,
                    canExecute = canExecuteAction,
                    modifier = Modifier.focusRequester(initialFocusRequester)
                )
            }
        if (!isShortcutTile) {
            item {
                MenuItemRow(
                    title = stringResource(R.string.app_menu_settings),
                    icon = Icons.Default.Settings,
                    onClick = onSettings,
                    canExecute = canExecuteAction
                )
            }
            item {
                MenuItemRow(
                    title = stringResource(R.string.app_menu_uninstall),
                    icon = Icons.Default.Delete,
                    onClick = onUninstall,
                    canExecute = canExecuteAction
                )
            }
        }

        if (onMoveLeft != null || onMoveRight != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        MenuItemRow(
                            title = stringResource(R.string.app_menu_move_left),
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            onClick = { onMoveLeft?.invoke() },
                            canExecute = canExecuteAction,
                            enabled = onMoveLeft != null
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        MenuItemRow(
                            title = stringResource(R.string.app_menu_move_right),
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            onClick = { onMoveRight?.invoke() },
                            canExecute = canExecuteAction,
                            enabled = onMoveRight != null
                        )
                    }
                }
            }
        }

        if (onManageOrder != null) {
            item {
                MenuItemRow(
                    title = stringResource(R.string.installed_apps_manage_title),
                    icon = Icons.Default.Tune,
                    onClick = onManageOrder,
                    canExecute = canExecuteAction
                )
            }
        }

        if (onRemoveFromRow != null && !isShortcutTile) {
            item {
                MenuItemRow(
                    title = stringResource(R.string.app_menu_hide_from_row),
                    icon = Icons.Default.VisibilityOff,
                    onClick = onRemoveFromRow,
                    canExecute = canExecuteAction
                )
            }
        }

        if (!isShortcutTile) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_menu_appearance),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                )
            }

            item {
                MenuItemRow(
                    title = stringResource(R.string.app_menu_title),
                    subtitle = app.displayLabel,
                    icon = Icons.Default.TextFields,
                    onClick = onRenameClick,
                    canExecute = canExecuteAction
                )
            }

            item {
                MenuItemRow(
                    title = stringResource(R.string.app_menu_change_icon),
                    subtitle = if (app.customIcon != null) "Customized" else stringResource(R.string.app_menu_icon_default),
                    icon = Icons.Default.Image,
                    onClick = onChangeIconClick,
                    canExecute = canExecuteAction
                )
            }

            if (app.customLabel != null || app.customIcon != null) {
                item {
                    MenuItemRow(
                        title = stringResource(R.string.app_menu_reset),
                        icon = Icons.Default.Refresh,
                        onClick = onResetClick,
                        canExecute = canExecuteAction
                    )
                }
            }
        }
    }
}
}

@Composable
private fun RenameDialogContent(
    currentText: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    initialFocusRequester: FocusRequester
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_menu_rename_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )

        Surface(
            shape = RoundedCornerShape(14.dp),
            colors = SurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.06f)
            ),
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = currentText,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(initialFocusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                    decorationBox = { innerTextField ->
                        if (currentText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.app_menu_rename_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Primary,
                    focusedContainerColor = Color.White,
                    contentColor = Color.White,
                    focusedContentColor = Color.Black
                )
            ) {
                Text(stringResource(R.string.app_menu_save))
            }
            Button(
                onClick = onReset,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    focusedContainerColor = Color.White,
                    contentColor = Color.White,
                    focusedContentColor = Color.Black
                )
            ) {
                Text(stringResource(R.string.app_menu_reset))
            }
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    focusedContainerColor = Color.White,
                    contentColor = Color.White,
                    focusedContentColor = Color.Black
                )
            ) {
                Text(stringResource(R.string.app_menu_cancel))
            }
        }
    }
}

@Composable
private fun IconPickerContent(
    app: InstalledApp,
    uiState: AppContextMenuUiState,
    onSelectOption: (String?) -> Unit,
    onSelectPack: (com.nuvio.tv.core.apps.IconPackInfo) -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.app_menu_change_icon),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                MenuItemRow(
                    title = stringResource(R.string.app_menu_icon_default),
                    icon = Icons.Default.Refresh,
                    onClick = { onSelectOption("default") },
                    modifier = Modifier.focusRequester(initialFocusRequester)
                )
            }

        if (app.bannerBitmap != null) {
            item {
                MenuItemRow(
                    title = stringResource(R.string.app_menu_icon_system_banner),
                    icon = Icons.Default.Image,
                    onClick = { onSelectOption("system_banner") }
                )
            }
        }

        if (app.squareBitmap != null) {
            item {
                MenuItemRow(
                    title = stringResource(R.string.app_menu_icon_system_square),
                    icon = Icons.Default.Image,
                    onClick = { onSelectOption("system_icon") }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_menu_icon_packs),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
            )
        }

        if (uiState.isLoadingIconPacks) {
            item { LoadingIndicator() }
        } else if (uiState.iconPacks.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.app_menu_no_icon_packs),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(8.dp)
                )
            }
        } else {
            items(uiState.iconPacks.size) { index ->
                val pack = uiState.iconPacks[index]
                Surface(
                    onClick = { onSelectPack(pack) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.05f),
                        focusedContainerColor = Color.White.copy(alpha = 0.18f)
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
                        focusedBorder = Border(BorderStroke(2.dp, Color.White))
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (pack.icon != null) {
                            Image(
                                bitmap = pack.icon,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(28.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pack.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (uiState.activeGlobalPackPackage == pack.packageName) {
                                Text(
                                    text = stringResource(R.string.app_menu_active_global_pack),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    focusedContainerColor = Color.White,
                    contentColor = Color.White,
                    focusedContentColor = Color.Black
                )
            ) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}
}

@Composable
private fun PackIconsContent(
    packPackage: String,
    icons: List<com.nuvio.tv.core.apps.IconPackEntry>,
    isLoading: Boolean,
    onSelectIcon: (String) -> Unit,
    onApplyToAll: () -> Unit,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester
) {
    val firstIconFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val applyButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(icons, isLoading) {
        if (!isLoading && icons.isNotEmpty()) {
            withFrameNanos { }
            runCatching { firstIconFocusRequester.requestFocus() }
        } else if (!isLoading && icons.isEmpty()) {
            withFrameNanos { }
            runCatching { backButtonFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .focusRequester(backButtonFocusRequester)
                    .focusProperties {
                        down = firstIconFocusRequester
                    },
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    focusedContainerColor = Color.White,
                    contentColor = Color.White,
                    focusedContentColor = Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = "Pick Icon",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onApplyToAll,
                modifier = Modifier
                    .focusRequester(applyButtonFocusRequester)
                    .focusProperties {
                        down = firstIconFocusRequester
                    },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Primary,
                    focusedContainerColor = Color.White,
                    contentColor = Color.White,
                    focusedContentColor = Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(stringResource(R.string.app_menu_apply_pack_to_all))
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else if (icons.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No icons found in this pack", color = Color.White.copy(alpha = 0.5f))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusGroup()
            ) {
                itemsIndexed(icons, key = { _, entry -> entry.drawableName }) { index, entry ->
                    val itemModifier = Modifier
                        .size(64.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstIconFocusRequester) else Modifier)
                        .focusProperties {
                            if (index < 6) {
                                up = backButtonFocusRequester
                            }
                        }
                    Surface(
                        onClick = { onSelectIcon(entry.drawableName) },
                        modifier = itemModifier,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.06f),
                            focusedContainerColor = Color.White.copy(alpha = 0.28f)
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
                            focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = entry.icon,
                                contentDescription = entry.drawableName,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItemRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    canExecute: Boolean = true,
    enabled: Boolean = true
) {
    Surface(
        onClick = { if (canExecute && enabled) onClick() },
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(if (subtitle != null) 60.dp else 48.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.02f),
            focusedContainerColor = Color.White.copy(alpha = 0.18f),
            contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            focusedContentColor = Color.White
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.08f else 0.03f))),
            focusedBorder = Border(BorderStroke(2.dp, Color.White))
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
