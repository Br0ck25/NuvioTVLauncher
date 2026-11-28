@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Home gear panel's main page, laid out like Google TV's quick settings: the date and time with an "All settings"
 * button on top, then tiles two to a line. Each tile opens the matching Android settings page (the same pages
 * Google TV's own tiles lead to) or one of the launcher's own pages (Apps, Nuvio settings).
 */
@Composable
internal fun HomeQuickSettingsPanel(
    firstFocus: FocusRequester,
    onOpenApps: () -> Unit,
    onOpenNuvioSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Android TV has no public page for some of these (Bluetooth, Screensaver), and where two apps answer one
    // (Sound, Display) a chooser would pop up: try the TV Settings app first, then any app, then the main Settings
    // page, so a tile always leads somewhere.
    fun openSystem(action: String) {
        for (candidate in listOf(action, Settings.ACTION_SETTINGS)) {
            for (pkg in listOf(TV_SETTINGS_PACKAGE, null)) {
                try {
                    val intent = Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (pkg != null) intent.setPackage(pkg)
                    context.startActivity(intent)
                    onDismiss()
                    return
                } catch (_: ActivityNotFoundException) {
                } catch (_: SecurityException) {
                }
            }
        }
    }

    // The clock moves on while the panel is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val timeText = remember(now) { formatQuickSettingsTime(context, now) }
    val dateText = remember(now) { SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(now)) }
    val audioOutput = remember(now) { activeAudioOutputName(context) }

    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dateText, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary)
                Text(timeText, style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
            }
            QuickSettingsAllButton(onClick = { openSystem(Settings.ACTION_SETTINGS) })
        }
        Spacer(Modifier.height(4.dp))

        val tiles = listOf(
            QuickTile(Icons.Default.Apps, stringResource(R.string.apps_title), null, onOpenApps),
            QuickTile(Icons.Default.Home, stringResource(R.string.home_drawer_nuvio_settings), null) {
                onDismiss(); onOpenNuvioSettings()
            },
            QuickTile(Icons.Default.Wallpaper, stringResource(R.string.quick_settings_screensaver), null) { openSystem(Settings.ACTION_DREAM_SETTINGS) },
            QuickTile(Icons.Default.SettingsEthernet, stringResource(R.string.quick_settings_network), null) { openSystem(Settings.ACTION_WIFI_SETTINGS) },
            QuickTile(Icons.Default.Bluetooth, stringResource(R.string.quick_settings_bluetooth), null) { openSystem(Settings.ACTION_BLUETOOTH_SETTINGS) },
            QuickTile(Icons.Default.Tv, stringResource(R.string.quick_settings_display), null) { openSystem(Settings.ACTION_DISPLAY_SETTINGS) },
            QuickTile(Icons.Default.VolumeUp, stringResource(R.string.quick_settings_audio_output), audioOutput) { openSystem(Settings.ACTION_SOUND_SETTINGS) },
            QuickTile(Icons.Default.Accessibility, stringResource(R.string.quick_settings_accessibility), null) { openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        )
        tiles.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { colIndex, tile ->
                    QuickSettingsTile(
                        tile,
                        Modifier.weight(1f).then(if (rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private const val TV_SETTINGS_PACKAGE = "com.android.tv.settings"

private class QuickTile(val icon: ImageVector, val label: String, val value: String?, val onClick: () -> Unit)

@Composable
private fun QuickSettingsTile(tile: QuickTile, modifier: Modifier = Modifier) {
    Card(
        onClick = tile.onClick,
        modifier = modifier,
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.07f), focusedContainerColor = NuvioTheme.colors.FocusBackground),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.FocusRing), shape = RoundedCornerShape(16.dp))),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(tile.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(tile.label, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                tile.value?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun QuickSettingsAllButton(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(CircleShape),
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.07f), focusedContainerColor = NuvioTheme.colors.FocusBackground),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.FocusRing), shape = CircleShape)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.quick_settings_all_settings), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
        }
    }
}

private fun formatQuickSettingsTime(context: Context, millis: Long): String {
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

/** What the sound is going to now ("HDMI", a Bluetooth speaker's name…), or null for the TV's own speakers. */
internal fun activeAudioOutputName(context: Context): String? = runCatching {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val external = devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_HDMI_ARC || it.type == AudioDeviceInfo.TYPE_HDMI_EARC ||
            it.type == AudioDeviceInfo.TYPE_HDMI
    }
    external?.productName?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()
