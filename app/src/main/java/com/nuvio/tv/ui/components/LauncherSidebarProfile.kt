package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme

/*
 * The launcher's profile picture in the side menu (Settings → Layout → "pill shows profile", and the
 * collapsed rail). Kept here so `MainActivity.kt` only calls these (see LAUNCHER_RULES.md).
 */

/**
 * Collapsed rail: the profile's picture above Home, so you can see whose Home it is without opening the
 * sidebar. Display only (the expanded row is the one that switches profile), so it never takes the focus
 * when the rail opens.
 */
@Composable
fun BoxScope.LauncherRailProfileAvatar(
    name: String,
    colorHex: String,
    avatarImageUrl: String?,
    itemWidth: Dp,
    itemHeight: Dp,
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = 30.dp)
            .offset(x = NuvioTheme.spacing.md)
            .width(itemWidth)
            .height(itemHeight)
            .focusProperties { canFocus = false }
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center
    ) {
        ProfileAvatarCircle(
            name = name,
            colorHex = colorHex,
            size = NuvioTheme.sizes.sidebar.leadingVisual,
            avatarImageUrl = avatarImageUrl,
            imageCrossfade = false
        )
    }
}

/** The floating pill shows this profile (its picture, or colour and initial) in place of the page's icon. */
@Composable
fun LauncherPillProfileAvatar(
    name: String,
    colorHex: String,
    avatarImageUrl: String?,
) {
    ProfileAvatarCircle(
        name = name,
        colorHex = colorHex,
        size = NuvioTheme.sizes.sidebar.leadingVisual - NuvioTheme.spacing.xs,
        avatarImageUrl = avatarImageUrl,
        imageCrossfade = false
    )
}
