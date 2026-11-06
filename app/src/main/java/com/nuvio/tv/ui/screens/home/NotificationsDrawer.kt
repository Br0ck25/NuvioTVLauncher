@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.home

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.nuvio.tv.core.notification.LauncherNotificationItem
import com.nuvio.tv.core.notification.LauncherNotificationListenerService
import com.nuvio.tv.ui.components.AmbientDialogEffect
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotificationsDrawer(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasAccess = remember(context) { LauncherNotificationListenerService.isAccessGranted(context) }
    val notifications by LauncherNotificationListenerService.notifications.collectAsState()
    val initialFocusRequester = remember { FocusRequester() }

    AmbientDialogEffect(onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        BackHandler { onDismiss() }

        LaunchedEffect(Unit) {
            LauncherNotificationListenerService.instance?.refreshActiveNotifications()
            repeat(2) { withFrameNanos { } }
            runCatching { initialFocusRequester.requestFocus() }
        }

        LaunchedEffect(notifications.isEmpty()) {
            repeat(2) { withFrameNanos { } }
            runCatching { initialFocusRequester.requestFocus() }
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_BACK ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE
                    ) {
                        if (native.action == AndroidKeyEvent.ACTION_UP) {
                            onDismiss()
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_HOME) {
                        onDismiss()
                        return@onPreviewKeyEvent false
                    }
                    false
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onDismiss()
                }
        ) {
            if (!hasAccess) {
                NotificationPermissionDialog(
                    onOpenSettings = {
                        LauncherNotificationListenerService.openAccessSettings(context)
                        onDismiss()
                    },
                    onCancel = onDismiss,
                    initialFocusRequester = initialFocusRequester,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
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
                    border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)))
                ) {
                    NotificationsDrawerContent(
                        notifications = notifications,
                        onNotificationClick = { item ->
                            activateNotification(context, item)
                            onDismiss()
                        },
                        onDismissItem = { item ->
                            LauncherNotificationListenerService.instance?.dismiss(item.key)
                        },
                        onClearAll = {
                            LauncherNotificationListenerService.instance?.dismissAll()
                        },
                        initialFocusRequester = initialFocusRequester
                    )
                }
            }
        }
    }
}

private fun activateNotification(context: Context, item: LauncherNotificationItem) {
    val sentPendingIntent = runCatching {
        val pendingIntent = item.contentIntent ?: return@runCatching false
        if (Build.VERSION.SDK_INT >= 34) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                .toBundle()
            pendingIntent.send(context, 0, null, null, null, null, options)
        } else {
            pendingIntent.send()
        }
        true
    }.getOrDefault(false)

    if (!sentPendingIntent) {
        val pm = context.packageManager
        val launchIntent = pm.getLeanbackLaunchIntentForPackage(item.packageName)
            ?: pm.getLaunchIntentForPackage(item.packageName)
        val launchedApp = if (launchIntent != null) {
            runCatching {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (!launchedApp) {
            runCatching {
                val settingsIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", item.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
            }
        }
    }
    if (item.isClearable) {
        LauncherNotificationListenerService.instance?.dismiss(item.key)
    }
}

@Composable
private fun NotificationPermissionDialog(
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
    initialFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(460.dp)
            .padding(24.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { /* swallow click */ },
        shape = RoundedCornerShape(20.dp),
        colors = SurfaceDefaults.colors(
            containerColor = Color(0xFF1E222B)
        ),
        border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = stringResource(R.string.notification_access_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }

            Text(
                text = stringResource(R.string.notification_access_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1.3f)
                        .focusRequester(initialFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.Primary,
                        focusedContainerColor = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Color.Black
                    )
                ) {
                    Text(
                        text = stringResource(R.string.notification_open_settings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(0.7f),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        focusedContainerColor = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Color.Black
                    )
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

@Composable
private fun NotificationsDrawerContent(
    notifications: List<LauncherNotificationItem>,
    onNotificationClick: (LauncherNotificationItem) -> Unit,
    onDismissItem: (LauncherNotificationItem) -> Unit,
    onClearAll: () -> Unit,
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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.notifications_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }

            if (notifications.isNotEmpty()) {
                Surface(
                    onClick = onClearAll,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        focusedContainerColor = Color.White.copy(alpha = 0.22f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))),
                        focusedBorder = Border(BorderStroke(2.dp, Color.White))
                    ),
                    modifier = Modifier.focusRequester(initialFocusRequester)
                ) {
                    Text(
                        text = stringResource(R.string.notifications_clear_all),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(initialFocusRequester)
                    .focusable(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = stringResource(R.string.notifications_none),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notifications, key = { it.key }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { onNotificationClick(item) },
                            modifier = Modifier.weight(1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.06f),
                                focusedContainerColor = Color.White.copy(alpha = 0.18f)
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            border = ClickableSurfaceDefaults.border(
                                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
                                focusedBorder = Border(BorderStroke(2.dp, Color.White))
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (item.appIcon != null) {
                                            Image(
                                                bitmap = item.appIcon,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Android,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Text(
                                            text = item.appName,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = Color.White.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Text(
                                        text = formatPostTime(item.postTime),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.45f)
                                    )
                                }

                                if (item.title.isNotBlank()) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (item.text.isNotBlank()) {
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Surface(
                            onClick = { onDismissItem(item) },
                            modifier = Modifier.size(40.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.06f),
                                focusedContainerColor = Color.White.copy(alpha = 0.22f)
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            border = ClickableSurfaceDefaults.border(
                                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
                                focusedBorder = Border(BorderStroke(2.dp, Color.White))
                            )
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_cancel),
                                    tint = Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatPostTime(postTime: Long): String {
    return runCatching {
        val instant = Instant.ofEpochMilli(postTime)
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault("")
}
