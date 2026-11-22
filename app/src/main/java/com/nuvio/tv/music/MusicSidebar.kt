package com.nuvio.tv.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

/** The Music section's own rail, matching the listening-room mockup. */
@Composable
internal fun MusicSidebar(
    selectedTab: MusicTab,
    onTabSelected: (MusicTab) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenParty: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenIntegrations: () -> Unit,
    partyRunning: Boolean,
    onOpenNuvioMenu: () -> Unit,
    initialFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .width(186.dp)
            .fillMaxHeight()
            .background(MusicPalette.backgroundTop)
            .border(1.dp, MusicPalette.border.copy(alpha = 0.7f))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    onOpenNuvioMenu()
                    true
                } else {
                    false
                }
            }
            .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 15.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(33.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(MusicPalette.accent, MusicPalette.accentTeal)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "N",
                    color = MusicPalette.background,
                    fontFamily = MusicDisplay,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text(
                    text = "NUVIO ONE",
                    color = MusicPalette.text,
                    fontSize = 11.sp,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MUSIC",
                    color = MusicPalette.accentTeal,
                    fontSize = 8.sp,
                    letterSpacing = 2.2.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(23.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MusicSidebarItem(
                label = stringResource(R.string.music_tab_home),
                icon = Icons.Default.Home,
                selected = selectedTab == MusicTab.HOME,
                modifier = if (selectedTab == MusicTab.HOME) {
                    Modifier.focusRequester(initialFocus)
                } else {
                    Modifier
                },
                onClick = { onTabSelected(MusicTab.HOME) }
            )
            MusicSidebarItem(
                label = stringResource(R.string.music_tab_discover),
                icon = Icons.Default.AutoAwesome,
                selected = selectedTab == MusicTab.DISCOVER,
                modifier = if (selectedTab == MusicTab.DISCOVER) {
                    Modifier.focusRequester(initialFocus)
                } else {
                    Modifier
                },
                onClick = { onTabSelected(MusicTab.DISCOVER) }
            )
            MusicSidebarItem(
                label = stringResource(R.string.music_tab_explore),
                icon = Icons.Default.Explore,
                selected = selectedTab == MusicTab.EXPLORE,
                modifier = if (selectedTab == MusicTab.EXPLORE) {
                    Modifier.focusRequester(initialFocus)
                } else {
                    Modifier
                },
                onClick = { onTabSelected(MusicTab.EXPLORE) }
            )
            MusicSidebarItem(
                label = stringResource(R.string.music_tab_library),
                icon = Icons.Default.LibraryMusic,
                selected = selectedTab == MusicTab.LIBRARY,
                modifier = if (selectedTab == MusicTab.LIBRARY) {
                    Modifier.focusRequester(initialFocus)
                } else {
                    Modifier
                },
                onClick = { onTabSelected(MusicTab.LIBRARY) }
            )
            MusicSidebarItem(
                label = stringResource(R.string.music_tab_local),
                icon = Icons.Default.Folder,
                selected = selectedTab == MusicTab.LOCAL,
                modifier = if (selectedTab == MusicTab.LOCAL) {
                    Modifier.focusRequester(initialFocus)
                } else {
                    Modifier
                },
                onClick = { onTabSelected(MusicTab.LOCAL) }
            )
            MusicSidebarItem(
                label = stringResource(R.string.music_sidebar_webdav),
                icon = Icons.Default.Cloud,
                selected = false,
                onClick = onOpenWebDav
            )
            MusicSidebarItem(
                label = stringResource(R.string.music_tab_search),
                icon = Icons.Default.Search,
                selected = selectedTab == MusicTab.SEARCH,
                modifier = if (selectedTab == MusicTab.SEARCH) {
                    Modifier.focusRequester(initialFocus)
                } else {
                    Modifier
                },
                onClick = { onTabSelected(MusicTab.SEARCH) }
            )
            MusicSidebarItem(
                label = stringResource(R.string.music_sidebar_party),
                icon = Icons.Default.Groups,
                selected = false,
                live = partyRunning,
                onClick = onOpenParty
            )
            MusicSidebarItem(
                label = stringResource(R.string.music_sidebar_integrations),
                icon = Icons.Default.Extension,
                selected = false,
                onClick = onOpenIntegrations
            )
        }

        Spacer(Modifier.weight(1f))

        Spacer(Modifier.height(9.dp))
        MusicSidebarItem(
            label = stringResource(R.string.music_sidebar_settings),
            icon = Icons.Default.Settings,
            selected = false,
            onClick = onOpenSettings
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun MusicSidebarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(
                when {
                    focused -> MusicPalette.surfaceFocused
                    selected -> MusicPalette.surfaceRaised
                    else -> Color.Transparent
                }
            )
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(17.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (selected || focused) MusicPalette.accent else Color.Transparent
                )
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                focused -> MusicPalette.text
                selected -> MusicPalette.accent
                else -> MusicPalette.textSecondary
            },
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = if (focused || selected) MusicPalette.text else MusicPalette.textSecondary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
        if (live) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MusicPalette.accentTeal)
            )
        }
    }
}
