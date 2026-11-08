@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.calendar.CalendarEntryPoint
import com.nuvio.tv.calendar.CalendarSettings
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences
import com.nuvio.tv.ui.components.ClassicLayoutPreview
import com.nuvio.tv.ui.components.GridLayoutPreview
import com.nuvio.tv.ui.components.ModernLayoutPreview
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
internal fun LayoutHomeLayoutSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    val firstHomeLayoutFocusRequester = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsOptionRow(firstHomeLayoutFocusRequester),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        listOf(HomeLayout.MODERN, HomeLayout.GRID, HomeLayout.CLASSIC).forEachIndexed { index, layout ->
            LayoutCard(
                layout = layout,
                isSelected = uiState.selectedLayout == layout,
                onClick = { onEvent(LayoutSettingsEvent.SelectLayout(layout)) },
                modifier = Modifier
                    .weight(1f)
                    .then(if (index == 0) Modifier.focusRequester(firstHomeLayoutFocusRequester) else Modifier)
            )
        }
    }

    if (uiState.selectedLayout == HomeLayout.MODERN) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_fullscreen_hero_backdrop),
            subtitle = stringResource(R.string.layout_fullscreen_hero_backdrop_sub),
            checked = uiState.modernHeroFullScreenBackdropEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetModernHeroFullScreenBackdropEnabled(!uiState.modernHeroFullScreenBackdropEnabled))
            }
        )
    }

    if (uiState.selectedLayout == HomeLayout.CLASSIC) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_classic_focus_gradient),
            subtitle = stringResource(R.string.layout_classic_focus_gradient_sub),
            checked = uiState.classicFocusGradientEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetClassicFocusGradientEnabled(!uiState.classicFocusGradientEnabled))
            }
        )
    }

    if (uiState.selectedLayout != HomeLayout.MODERN) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_show_hero),
            subtitle = stringResource(R.string.layout_show_hero_sub),
            checked = uiState.heroSectionEnabled,
            onToggle = { onEvent(LayoutSettingsEvent.SetHeroSectionEnabled(!uiState.heroSectionEnabled)) }
        )
        if (uiState.heroSectionEnabled && uiState.availableCatalogs.isNotEmpty()) {
            Text(
                text = stringResource(R.string.layout_hero_catalogs),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextSecondary
            )
            Text(
                text = stringResource(R.string.layout_hero_catalogs_sub),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
            val firstHeroCatalogFocusRequester = remember { FocusRequester() }
            LazyRow(
                modifier = Modifier.settingsOptionRow(firstHeroCatalogFocusRequester),
                contentPadding = PaddingValues(end = NuvioTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                itemsIndexed(
                    items = uiState.availableCatalogs,
                    key = { _, catalog -> catalog.key }
                ) { catalogIndex, catalog ->
                    CatalogChip(
                        catalogInfo = catalog,
                        isSelected = catalog.key in uiState.heroCatalogKeys,
                        onClick = { onEvent(LayoutSettingsEvent.ToggleHeroCatalog(catalog.key)) },
                        modifier = if (catalogIndex == 0) {
                            Modifier.focusRequester(firstHeroCatalogFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun LayoutHomeContentSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    if (uiState.selectedLayout != HomeLayout.MODERN) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_poster_labels),
            subtitle = stringResource(R.string.layout_poster_labels_sub),
            checked = uiState.posterLabelsEnabled,
            onToggle = { onEvent(LayoutSettingsEvent.SetPosterLabelsEnabled(!uiState.posterLabelsEnabled)) }
        )
        SettingsToggleRow(
            title = stringResource(R.string.layout_addon_name),
            subtitle = stringResource(R.string.layout_addon_name_sub),
            checked = uiState.catalogAddonNameEnabled,
            onToggle = { onEvent(LayoutSettingsEvent.SetCatalogAddonNameEnabled(!uiState.catalogAddonNameEnabled)) }
        )
    }
    SettingsToggleRow(
        title = stringResource(R.string.layout_catalog_type),
        subtitle = stringResource(R.string.layout_catalog_type_sub),
        checked = uiState.catalogTypeSuffixEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetCatalogTypeSuffixEnabled(!uiState.catalogTypeSuffixEnabled)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_hide_unreleased),
        subtitle = stringResource(R.string.layout_hide_unreleased_sub),
        checked = uiState.hideUnreleasedContent,
        onToggle = { onEvent(LayoutSettingsEvent.SetHideUnreleasedContent(!uiState.hideUnreleasedContent)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_overall_ratings),
        subtitle = stringResource(
            if (uiState.homeImdbRatingsVisibility.showRatings) {
                R.string.layout_overall_ratings_sub_on
            } else {
                R.string.layout_overall_ratings_sub_off
            }
        ),
        checked = uiState.homeImdbRatingsVisibility.showRatings,
        onToggle = {
            val visibility = if (uiState.homeImdbRatingsVisibility.showRatings) {
                HomeImdbRatingsVisibility.HIDE_ALL
            } else {
                HomeImdbRatingsVisibility.SHOW_ALL
            }
            onEvent(LayoutSettingsEvent.SetHomeImdbRatingsVisibility(visibility))
        }
    )
    // Classic and Grid only show descriptions in the hero carousel.
    if (uiState.selectedLayout == HomeLayout.MODERN || uiState.heroSectionEnabled) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_hero_description_auto_scroll),
            subtitle = stringResource(R.string.layout_hero_description_auto_scroll_sub),
            checked = uiState.heroDescriptionAutoScrollEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetHeroDescriptionAutoScrollEnabled(!uiState.heroDescriptionAutoScrollEnabled))
            }
        )
    }
}

@Composable
internal fun LayoutSidebarSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LiveTvPreferences.ensureLoaded(context)
    val liveTvEnabled by LiveTvPreferences.enabled.collectAsStateWithLifecycle()
    val calendarPrefs = remember(context) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                CalendarEntryPoint::class.java
            ).calendarPreferences()
        }.getOrNull()
    }
    val calendarSettings by (calendarPrefs?.settings ?: flowOf(CalendarSettings()))
        .collectAsState(initial = CalendarSettings())

    SettingsToggleRow(
        title = stringResource(R.string.layout_modern_sidebar),
        subtitle = stringResource(R.string.layout_modern_sidebar_sub),
        checked = uiState.modernSidebarEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetModernSidebarEnabled(!uiState.modernSidebarEnabled)) }
    )
    if (uiState.modernSidebarEnabled) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_modern_sidebar_blur),
                subtitle = stringResource(R.string.layout_modern_sidebar_blur_sub),
                checked = uiState.modernSidebarBlurEnabled,
                onToggle = { onEvent(LayoutSettingsEvent.SetModernSidebarBlurEnabled(!uiState.modernSidebarBlurEnabled)) }
            )
        }
        SettingsToggleRow(
            title = stringResource(R.string.layout_hide_floating_pill),
            subtitle = stringResource(R.string.layout_hide_floating_pill_sub),
            checked = uiState.sidebarCollapsedByDefault,
            onToggle = { onEvent(LayoutSettingsEvent.SetSidebarCollapsed(!uiState.sidebarCollapsedByDefault)) }
        )
    } else {
        SettingsToggleRow(
            title = stringResource(R.string.layout_collapse_sidebar),
            subtitle = stringResource(R.string.layout_collapse_sidebar_sub),
            checked = uiState.sidebarCollapsedByDefault,
            onToggle = { onEvent(LayoutSettingsEvent.SetSidebarCollapsed(!uiState.sidebarCollapsedByDefault)) }
        )
    }
    DiscoverLocationRow(
        selectedLocation = uiState.discoverLocation,
        rememberedLocation = uiState.lastNonOffDiscoverLocation,
        onLocationSelected = { location -> onEvent(LayoutSettingsEvent.SetDiscoverLocation(location)) }
    )

    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Sidebar Menu Items",
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextSecondary
    )
    Text(
        text = "Turn items on or off and move them up or down to rearrange the sidebar menu",
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextTertiary
    )

    val strNavHome = stringResource(R.string.nav_home)
    val strNavDiscover = stringResource(R.string.nav_discover)
    val strNavSearch = stringResource(R.string.nav_search)
    val strNavLibrary = stringResource(R.string.nav_library)
    val strNavLiveTv = stringResource(R.string.live_tv_title)
    val strNavApps = stringResource(R.string.nav_apps)
    val strNavSettings = stringResource(R.string.nav_settings)

    val order = uiState.sidebarItemOrder
    order.forEachIndexed { index, itemKey ->
        val title = when (itemKey) {
            "home" -> strNavHome
            "discover" -> strNavDiscover
            "search" -> strNavSearch
            "library" -> strNavLibrary
            "calendar" -> "Calendar"
            "livetv" -> strNavLiveTv
            "apps" -> strNavApps
            "settings" -> strNavSettings
            else -> itemKey.replaceFirstChar { it.uppercase() }
        }
        val isChecked = when (itemKey) {
            "discover" -> uiState.discoverLocation == DiscoverLocation.IN_SIDEBAR &&
                "discover" !in uiState.disabledSidebarItems
            "calendar" -> !calendarSettings.calendarOff &&
                "calendar" !in uiState.disabledSidebarItems
            "livetv" -> liveTvEnabled &&
                "livetv" !in uiState.disabledSidebarItems
            else -> itemKey !in uiState.disabledSidebarItems
        }
        val onToggle: () -> Unit = {
            when (itemKey) {
                "discover" -> {
                    val nextOn = !isChecked
                    onEvent(LayoutSettingsEvent.SetSidebarItemEnabled("discover", nextOn))
                    onEvent(
                        LayoutSettingsEvent.SetDiscoverLocation(
                            if (nextOn) DiscoverLocation.IN_SIDEBAR else DiscoverLocation.OFF
                        )
                    )
                }
                "calendar" -> {
                    val nextOn = !isChecked
                    onEvent(LayoutSettingsEvent.SetSidebarItemEnabled("calendar", nextOn))
                    scope.launch {
                        calendarPrefs?.updateSettings {
                            if (nextOn) {
                                it.copy(calendarOff = false, calendarInMenu = true)
                            } else {
                                it.copy(calendarInMenu = false)
                            }
                        }
                    }
                }
                "livetv" -> {
                    val nextOn = !isChecked
                    onEvent(LayoutSettingsEvent.SetSidebarItemEnabled("livetv", nextOn))
                    LiveTvPreferences.setEnabled(context, nextOn)
                }
                else -> {
                    onEvent(LayoutSettingsEvent.SetSidebarItemEnabled(itemKey, !isChecked))
                }
            }
        }
        SidebarOrderToggleRow(
            title = title,
            checked = isChecked,
            onToggle = onToggle,
            onMoveUp = if (index > 0) {
                { onEvent(LayoutSettingsEvent.MoveSidebarItem(itemKey, -1)) }
            } else null,
            onMoveDown = if (index < order.lastIndex) {
                { onEvent(LayoutSettingsEvent.MoveSidebarItem(itemKey, 1)) }
            } else null
        )
    }
}

@Composable
private fun SidebarOrderToggleRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    val moveUpLabel = stringResource(R.string.cd_move_up)
    val moveDownLabel = stringResource(R.string.cd_move_down)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        SettingsToggleRow(
            title = title,
            subtitle = null,
            checked = checked,
            enabled = true,
            onToggle = onToggle,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = { onMoveUp?.invoke() },
            enabled = onMoveUp != null,
            modifier = Modifier
                .width(40.dp)
                .semantics { this.contentDescription = moveUpLabel },
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                contentColor = NuvioTheme.colors.TextPrimary,
                disabledContainerColor = NuvioTheme.colors.BackgroundElevated,
                disabledContentColor = NuvioTheme.colors.TextTertiary
            ),
            scale = ButtonDefaults.scale(focusedScale = 1.05f),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("▲", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clearAndSetSemantics {})
        }
        Button(
            onClick = { onMoveDown?.invoke() },
            enabled = onMoveDown != null,
            modifier = Modifier
                .width(40.dp)
                .semantics { this.contentDescription = moveDownLabel },
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                contentColor = NuvioTheme.colors.TextPrimary,
                disabledContainerColor = NuvioTheme.colors.BackgroundElevated,
                disabledContentColor = NuvioTheme.colors.TextTertiary
            ),
            scale = ButtonDefaults.scale(focusedScale = 1.05f),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("▼", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun LayoutCard(
    layout: HomeLayout,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val animatePreview = isSelected || isFocused

    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                }
            }
            .semantics {
                role = Role.RadioButton
                this.selected = isSelected
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            ) {
                when (layout) {
                    HomeLayout.CLASSIC -> ClassicLayoutPreview(
                        modifier = Modifier.fillMaxWidth(),
                        animated = animatePreview
                    )
                    HomeLayout.GRID -> GridLayoutPreview(
                        modifier = Modifier.fillMaxWidth(),
                        animated = animatePreview
                    )
                    HomeLayout.MODERN -> ModernLayoutPreview(
                        modifier = Modifier.fillMaxWidth(),
                        animated = animatePreview
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = NuvioTheme.colors.FocusRing,
                        modifier = Modifier
                            .size(NuvioTheme.spacing.lg)
                            .padding(end = 6.dp)
                    )
                }
                Text(
                    text = when (layout) {
                        HomeLayout.CLASSIC -> stringResource(R.string.layout_classic)
                        HomeLayout.GRID -> stringResource(R.string.layout_grid)
                        HomeLayout.MODERN -> stringResource(R.string.layout_modern)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CatalogChip(
    catalogInfo: CatalogInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsChoiceChip(
        modifier = modifier,
        label = catalogInfo.name,
        selected = isSelected,
        onClick = onClick,
        multiSelect = true
    )
}

@Composable
private fun DiscoverLocationRow(
    selectedLocation: DiscoverLocation,
    rememberedLocation: DiscoverLocation,
    onLocationSelected: (DiscoverLocation) -> Unit
) {
    val sectionEnabled = selectedLocation != DiscoverLocation.OFF
    var dialogOpen by remember { mutableStateOf(false) }

    SettingsToggleRow(
        title = stringResource(R.string.layout_show_discover),
        subtitle = stringResource(R.string.layout_show_discover_sub),
        checked = sectionEnabled,
        onToggle = {
            onLocationSelected(
                when (selectedLocation) {
                    DiscoverLocation.OFF -> rememberedLocation
                    DiscoverLocation.IN_SEARCH,
                    DiscoverLocation.IN_SIDEBAR -> DiscoverLocation.OFF
                }
            )
        }
    )
    if (sectionEnabled) {
        val currentLabel = when (selectedLocation) {
            DiscoverLocation.IN_SIDEBAR -> stringResource(R.string.layout_discover_location_in_sidebar)
            DiscoverLocation.IN_SEARCH -> stringResource(R.string.layout_discover_location_in_search)
            DiscoverLocation.OFF -> ""
        }
        SettingsActionRow(
            title = stringResource(R.string.layout_discover_location_action),
            subtitle = null,
            value = currentLabel,
            onClick = { dialogOpen = true }
        )
    }

    if (dialogOpen) {
        DiscoverLocationDialog(
            selectedLocation = selectedLocation,
            onLocationSelected = { location ->
                onLocationSelected(location)
                dialogOpen = false
            },
            onDismiss = { dialogOpen = false }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverLocationDialog(
    selectedLocation: DiscoverLocation,
    onLocationSelected: (DiscoverLocation) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            DiscoverLocation.IN_SEARCH,
            stringResource(R.string.layout_discover_location_in_search),
            stringResource(R.string.layout_discover_location_in_search_desc)
        ),
        SettingsPickerOption(
            DiscoverLocation.IN_SIDEBAR,
            stringResource(R.string.layout_discover_location_in_sidebar),
            stringResource(R.string.layout_discover_location_in_sidebar_desc)
        )
    )

    val effectiveSelected = if (selectedLocation == DiscoverLocation.OFF) {
        DiscoverLocation.IN_SEARCH
    } else {
        selectedLocation
    }

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.layout_discover_location_dialog_title),
        options = options,
        selectedValue = effectiveSelected,
        onOptionSelected = onLocationSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 320.dp
    )
}
