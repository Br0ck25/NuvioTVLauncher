@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.notification.LauncherNotificationListenerService
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** HDMI/CEC-style inputs only; tuner and app-provided inputs can't be switched to this way. */
private fun loadPassthroughInputs(context: Context): List<TvInputInfo> = runCatching {
    val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
    manager?.tvInputList.orEmpty().filter { it.isPassthroughInput }
}.getOrDefault(emptyList())

private fun launchPassthroughInput(context: Context, inputId: String): Boolean = runCatching {
    val uri = TvContract.buildChannelUriForPassthroughInput(inputId)
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

@Composable
fun HomeTopBarHeader(
    onNotificationsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val notifications by LauncherNotificationListenerService.notifications.collectAsStateWithLifecycle()
    val notificationCount = notifications.size

    // Only real HDMI/CEC inputs get a button; boxes without any (e.g. a Shield) show none.
    val tvInputs = remember { loadPassthroughInputs(context) }
    val hasTvInputs = tvInputs.isNotEmpty()
    var showInputPicker by remember { mutableStateOf(false) }

    if (showInputPicker) {
        Dialog(onDismissRequest = { showInputPicker = false }) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.profile_tv_inputs_label),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                val firstFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
                tvInputs.forEachIndexed { index, input ->
                    Surface(
                        onClick = {
                            showInputPicker = false
                            if (!launchPassthroughInput(context, input.id)) {
                                Toast.makeText(context, R.string.profile_tv_inputs_unavailable, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        )
                    ) {
                        Text(
                            text = input.loadLabel(context)?.toString().orEmpty().ifBlank { input.id },
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = modifier
            .padding(end = 48.dp, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // TV Inputs Button
        if (hasTvInputs) {
            Surface(
                onClick = {
                    if (tvInputs.size == 1) {
                        if (!launchPassthroughInput(context, tvInputs[0].id)) {
                            Toast.makeText(context, R.string.profile_tv_inputs_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        showInputPicker = true
                    }
                },
                modifier = Modifier.size(36.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.35f),
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    pressedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))),
                    focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.FocusRing))
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = stringResource(R.string.profile_tv_inputs_label),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Notifications Button
        Surface(
            onClick = onNotificationsClick,
            modifier = Modifier
                .height(36.dp)
                .wrapContentWidth(),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Black.copy(alpha = 0.35f),
                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    pressedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))),
                focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.FocusRing))
            )
        ) {
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = stringResource(R.string.top_bar_notifications),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                if (notificationCount > 0) {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                            .clip(CircleShape)
                            .background(NuvioTheme.colors.Secondary)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (notificationCount > 99) "99+" else notificationCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                lineHeight = 11.sp
                            ),
                            color = NuvioTheme.colors.OnSecondary
                        )
                    }
                }
            }
        }

        // Settings Button
        Surface(
            onClick = onSettingsClick,
            modifier = Modifier.size(36.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Black.copy(alpha = 0.35f),
                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    pressedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))),
                focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.FocusRing))
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.top_bar_settings),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        HomeTopBarClock()
    }
}

/** The time, to the right of Settings. Follows the TV's 12/24-hour setting and updates on the minute. */
@Composable
private fun HomeTopBarClock() {
    val context = LocalContext.current
    val time by produceState(initialValue = formatClock(context)) {
        while (true) {
            value = formatClock(context)
            val now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L + 50L)
        }
    }
    Text(
        text = time,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1
    )
}

private fun formatClock(context: Context): String {
    val pattern = DateFormat.getBestDateTimePattern(
        Locale.getDefault(),
        if (DateFormat.is24HourFormat(context)) "Hm" else "hmm"
    )
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
}
