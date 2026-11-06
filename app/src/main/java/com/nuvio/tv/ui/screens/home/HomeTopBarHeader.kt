@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
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
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun HomeTopBarHeader(
    onNotificationsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val notifications by LauncherNotificationListenerService.notifications.collectAsState()
    val notificationCount = notifications.size

    val hasTvInputs = remember {
        val candidateIntents = listOf(
            Intent("com.android.tv.action.VIEW_INPUTS"),
            Intent("com.google.android.tv.action.VIEW_INPUTS"),
            Intent("android.settings.TV_INPUTS"),
            Intent("android.settings.TV_INPUT_SETTINGS"),
            Intent("android.media.tv.action.VIEW_INPUTS")
        )
        candidateIntents.any { context.packageManager.resolveActivity(it, 0) != null }
    }

    var timeText by remember { mutableStateOf(currentTimeString()) }
    LaunchedEffect(Unit) {
        while (true) {
            timeText = currentTimeString()
            delay(15_000)
        }
    }

    val isConnected = remember(context) { isNetworkConnected(context) }

    Row(
        modifier = modifier
            .padding(end = 48.dp, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (isConnected) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }

        // TV Inputs Button
        if (hasTvInputs) {
            Surface(
                onClick = {
                    val intents = listOf(
                        Intent("com.android.tv.action.VIEW_INPUTS"),
                        Intent("com.google.android.tv.action.VIEW_INPUTS"),
                        Intent("android.settings.TV_INPUTS"),
                        Intent("android.settings.TV_INPUT_SETTINGS"),
                        Intent("android.media.tv.action.VIEW_INPUTS")
                    )
                    var launched = false
                    for (intent in intents) {
                        try {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (context.packageManager.resolveActivity(intent, 0) != null) {
                                context.startActivity(intent)
                                launched = true
                                break
                            }
                        } catch (_: Exception) {}
                    }
                    if (!launched) {
                        try {
                            context.startActivity(Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.profile_tv_inputs_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.size(36.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    focusedContainerColor = Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))),
                    focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.Primary))
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = stringResource(R.string.profile_tv_inputs_label),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Notifications Button
        Surface(
            onClick = onNotificationsClick,
            modifier = Modifier.height(36.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.08f),
                focusedContainerColor = Color.White
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))),
                focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.Primary))
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = stringResource(R.string.top_bar_notifications),
                    modifier = Modifier.size(18.dp)
                )
                if (notificationCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFFE67E22))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = notificationCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = Color.White
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
                containerColor = Color.White.copy(alpha = 0.08f),
                focusedContainerColor = Color.White
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))),
                focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.Primary))
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.top_bar_settings),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Text(
            text = timeText,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            ),
            color = Color.White.copy(alpha = 0.92f)
        )
    }
}

private fun currentTimeString(): String {
    return runCatching {
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault("")
}

private fun isNetworkConnected(context: Context): Boolean {
    return runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
