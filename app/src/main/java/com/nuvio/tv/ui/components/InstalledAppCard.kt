@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * One app: its banner (or icon) with the name under it. OK opens it, long-press opens the
 * system's page for it (Uninstall, Force stop).
 */
@Composable
fun InstalledAppCard(
    app: InstalledApp,
    loadImage: suspend (InstalledApp) -> Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 168.dp,
    /** The Home row shows the banners alone; other screens keep the name under the tile. */
    showLabel: Boolean = true,
    /** This app is being carried to a new place (see AppMoveMode): lifted and ringed in the accent colour. */
    moving: Boolean = false
) {
    val image by produceState<Bitmap?>(initialValue = null, app.packageName, app.iconKey) { value = loadImage(app) }
    Column(modifier = Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxSize()
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
            border = CardDefaults.border(
                border = if (moving) Border(BorderStroke(4.dp, NuvioTheme.colors.Secondary), shape = RoundedCornerShape(10.dp)) else Border.None,
                focusedBorder = Border(
                    BorderStroke(if (moving) 4.dp else 3.dp, if (moving) NuvioTheme.colors.Secondary else NuvioTheme.colors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            scale = CardDefaults.scale(focusedScale = if (moving) 1.14f else 1.06f)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                image?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = app.label,
                        // A banner fills the card; a square icon is shown whole.
                        contentScale = if (app.isTvApp) ContentScale.Crop else ContentScale.Fit,
                        modifier = if (app.isTvApp) Modifier.fillMaxSize() else Modifier.padding(10.dp).fillMaxSize()
                    )
                }
            }
        }
        if (showLabel) Text(
            text = app.label,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
    }
}
