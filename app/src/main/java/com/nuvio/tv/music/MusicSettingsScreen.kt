package com.nuvio.tv.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.SevenBandEqualizerFaders
import com.nuvio.tv.core.audio.SevenBandPreset
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

/** Playback, sound and timer preferences for the Music section. */
@Composable
internal fun MusicSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val audioQuality by MusicSettings.audioQuality.collectAsState()
    val autoplay by MusicSettings.autoplay.collectAsState()
    val skipSilence by MusicSettings.skipSilence.collectAsState()
    val playbackSpeed by MusicSettings.playbackSpeed.collectAsState()
    val keepPlayingWhileBrowsing by MusicSettings.keepPlayingWhileBrowsing.collectAsState()
    val fadeSeconds by MusicSettings.fadeSeconds.collectAsState()
    val equalizerProfile by MusicSettings.equalizerProfile.collectAsState()
    val equalizerAvailable by MusicEqualizer.available.collectAsState()
    val downloadQuality by MusicSettings.downloadQuality.collectAsState()
    val wifiOnlyDownloads by MusicSettings.wifiOnlyDownloads.collectAsState()
    val homeRows by MusicHomeRows.config.collectAsState()
    val playback by MusicPlayer.state.collectAsState()
    val firstFocus = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current

    LaunchedEffect(Unit) {
        MusicSettings.init(context)
        MusicHomeRows.init(context)
    }
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused) {
                kotlinx.coroutines.delay(120)
                runCatching { firstFocus.requestFocus() }
            }
        }
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(MusicPalette.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsCloseButton(
                    onClick = onClose,
                    onDown = { runCatching { firstFocus.requestFocus() } }
                )
                Column {
                    Text(
                        text = stringResource(R.string.music_settings_screen_title),
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 34.sp,
                        lineHeight = 37.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.music_settings_subtitle),
                        color = MusicPalette.textMuted,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.music_settings_hint),
                    color = MusicPalette.textMuted,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .width(780.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "playback-label") { SettingsSection("Playback") }
                item(key = "audio-quality") {
                    SettingsSelector(
                        title = stringResource(R.string.music_settings_audio_quality),
                        detail = audioQuality.detail,
                        options = MusicAudioQuality.entries.map { it.label },
                        selectedIndex = MusicAudioQuality.entries.indexOf(audioQuality),
                        onSelect = { MusicPlayer.setAudioQuality(MusicAudioQuality.entries[it]) },
                        modifier = Modifier.focusRequester(firstFocus)
                    )
                }
                item(key = "autoplay") {
                    SettingsSelector(
                        title = stringResource(R.string.music_settings_autoplay),
                        detail = stringResource(R.string.music_settings_detail_radio),
                        options = listOf("Off", "On"),
                        selectedIndex = if (autoplay) 1 else 0,
                        onSelect = { MusicPlayer.setAutoplay(it == 1) }
                    )
                }
                item(key = "keep-playing") {
                    SettingsToggle(
                        title = stringResource(R.string.music_settings_keep_playing),
                        detail = stringResource(R.string.music_settings_detail_keep_playing),
                        checked = keepPlayingWhileBrowsing,
                        onToggle = MusicSettings::setKeepPlayingWhileBrowsing
                    )
                }
                item(key = "track-transition") {
                    SettingsSelector(
                        title = stringResource(R.string.music_settings_track_transition),
                        detail = stringResource(R.string.music_settings_detail_gapless),
                        options = listOf("Gapless", "Fade 2s", "Fade 4s"),
                        selectedIndex = when (fadeSeconds) {
                            2 -> 1
                            4 -> 2
                            else -> 0
                        },
                        onSelect = {
                            MusicPlayer.setFadeSeconds(if (it == 0) 0 else it * 2)
                        }
                    )
                }
                item(key = "skip-silence") {
                    SettingsSelector(
                        title = stringResource(R.string.music_settings_skip_silence),
                        detail = stringResource(R.string.music_settings_detail_skip_silence),
                        options = listOf("Off", "On"),
                        selectedIndex = if (skipSilence) 1 else 0,
                        onSelect = { MusicPlayer.setSkipSilence(it == 1) }
                    )
                }

                item(key = "sound-label") { SettingsSection("Sound") }
                val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                item(key = "playback-speed") {
                    SettingsSelector(
                        title = stringResource(R.string.music_settings_playback_speed),
                        detail = stringResource(R.string.music_settings_detail_lyrics_speed),
                        options = speeds.map(::speedLabel),
                        selectedIndex = speeds.indexOf(playbackSpeed).coerceAtLeast(0),
                        onSelect = { MusicPlayer.setPlaybackSpeed(speeds[it]) }
                    )
                }
                item(key = "equalizer") {
                    SettingsSelector(
                        title = stringResource(R.string.music_settings_equalizer),
                        detail = if (equalizerAvailable) "Seven-band EQ · Music profile" else stringResource(R.string.music_settings_unsupported),
                        options = SevenBandPreset.selectable.map { it.label },
                        selectedIndex = SevenBandPreset.selectable.indexOf(equalizerProfile.preset).coerceAtLeast(0),
                        onSelect = { MusicPlayer.setEqualizerPreset(SevenBandPreset.selectable[it]) }
                    )
                }
                if (equalizerProfile.preset != SevenBandPreset.OFF) {
                    item(key = "music-equalizer-faders") {
                        SevenBandEqualizerFaders(
                            gainsDb = equalizerProfile.gainsDb,
                            onBandChange = MusicPlayer::setEqualizerBand,
                            accent = MusicPalette.accent,
                            text = MusicPalette.text,
                            muted = MusicPalette.textMuted,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                        )
                    }
                    item(key = "music-equalizer-hint") {
                        Text(
                            text = "Focus a band and press Up/Down to adjust it · OK resets that band",
                            color = MusicPalette.textMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                        )
                    }
                }

                item(key = "downloads-label") { SettingsSection("Downloads") }
                item(key = "download-quality") {
                    SettingsSelector(
                        title = stringResource(R.string.music_settings_download_quality),
                        detail = "${downloadQuality.detail} · applies to new downloads.",
                        options = MusicAudioQuality.entries.map { it.label },
                        selectedIndex = MusicAudioQuality.entries.indexOf(downloadQuality),
                        onSelect = { MusicSettings.setDownloadQuality(MusicAudioQuality.entries[it]) }
                    )
                }
                item(key = "wifi-only") {
                    SettingsSelector(
                        title = stringResource(R.string.music_settings_wifi_only),
                        detail = if (wifiOnlyDownloads) {
                            stringResource(R.string.music_settings_downloads_wait)
                        } else {
                            stringResource(R.string.music_settings_downloads_mobile)
                        },
                        options = listOf("Off", "On"),
                        selectedIndex = if (wifiOnlyDownloads) 1 else 0,
                        onSelect = { MusicSettings.setWifiOnlyDownloads(it == 1) }
                    )
                }

                item(key = "home-label") { SettingsSection("Home") }
                item(key = "pin-library") {
                    SettingsToggle(
                        title = stringResource(R.string.music_settings_pin_library),
                        detail = stringResource(R.string.music_settings_pin_library_detail),
                        checked = homeRows.pinLibrary,
                        onToggle = { MusicHomeRows.setPinLibrary(it) }
                    )
                }
                homeRows.order.forEachIndexed { index, row ->
                    item(key = "home-row-${row.id}") {
                        SettingsOrderRow(
                            title = stringResource(row.labelRes),
                            status = if (row in homeRows.hidden) {
                                stringResource(R.string.music_settings_row_hidden)
                            } else {
                                stringResource(
                                    R.string.music_settings_row_position,
                                    index + 1,
                                    homeRows.order.size
                                )
                            },
                            detail = stringResource(R.string.music_settings_home_rows_detail),
                            hidden = row in homeRows.hidden,
                            canMoveUp = index > 0,
                            canMoveDown = index < homeRows.order.lastIndex,
                            onMove = { MusicHomeRows.move(row, it) },
                            onToggle = { MusicHomeRows.toggleHidden(row) }
                        )
                    }
                }
                item(key = "reset-home-rows") {
                    SettingsActionRow(
                        title = stringResource(R.string.music_settings_reset_rows),
                        detail = stringResource(R.string.music_settings_reset_rows_detail),
                        onClick = { MusicHomeRows.reset() }
                    )
                }

                item(key = "timer-label") { SettingsSection("Timer") }
                val timerOptions = listOf<Int?>(null, 15, 30, 45, 60, 90)
                val remaining = playback.sleepTimerRemainingMs
                item(key = "sleep-timer") {
                    SettingsSelector(
                        title = stringResource(R.string.music_settings_sleep_timer),
                        detail = if (playback.sleepTimerMinutes != null && remaining != null) {
                            stringResource(R.string.music_settings_pauses_in, formatMusicTime(remaining))
                        } else {
                            stringResource(R.string.music_settings_pauses_after)
                        },
                        options = timerOptions.map { if (it == null) "Off" else "${it}m" },
                        selectedIndex = timerOptions.indexOf(playback.sleepTimerMinutes)
                            .coerceAtLeast(0),
                        onSelect = { MusicPlayer.setSleepTimer(timerOptions[it]) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title.uppercase(),
        color = MusicPalette.accent,
        fontSize = 10.sp,
        letterSpacing = 1.8.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)
    )
}

/**
 * One focus target per setting. Left/Right (or Center) changes the value, Up/Down moves
 * between rows. The current value is the only option shown, keeping the list compact.
 */
@Composable
private fun SettingsSelector(
    title: String,
    detail: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val safeIndex = selectedIndex.coerceIn(0, options.lastIndex.coerceAtLeast(0))
    val value = options.getOrElse(safeIndex) { "" }
    val step: (Int) -> Unit = { delta ->
        onSelect((safeIndex + delta + options.size) % options.size)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) MusicPalette.surfaceFocused else MusicPalette.surface.copy(alpha = 0.55f)
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MusicPalette.text else MusicPalette.border.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        step(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        step(1)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        step(1)
                        true
                    }
                    Key.DirectionUp -> {
                        focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    Key.DirectionDown -> {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    else -> false
                }
            }
            .clickable { step(1) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = MusicPalette.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                color = MusicPalette.textMuted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.KeyboardArrowLeft,
                contentDescription = null,
                tint = if (focused) Color.White else MusicPalette.textMuted,
                modifier = Modifier.size(15.dp)
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (focused) Color.White else MusicPalette.surfaceRaised)
                    .border(
                        1.dp,
                        if (focused) Color.White else MusicPalette.border,
                        RoundedCornerShape(50)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = value,
                    color = if (focused) MusicPalette.background else MusicPalette.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = if (focused) Color.White else MusicPalette.textMuted,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) MusicPalette.surfaceFocused else MusicPalette.surface.copy(alpha = 0.55f)
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MusicPalette.text else MusicPalette.border.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onToggle(!checked)
                        true
                    }
                    Key.DirectionUp -> {
                        focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    Key.DirectionDown -> {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    else -> false
                }
            }
            .clickable { onToggle(!checked) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = MusicPalette.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                color = MusicPalette.textMuted,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier
                .width(46.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(50))
                .background(if (checked) MusicPalette.accent else MusicPalette.surfaceRaised)
                .border(
                    1.dp,
                    if (checked) MusicPalette.accent else MusicPalette.border,
                    RoundedCornerShape(50)
                )
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) MusicPalette.background else MusicPalette.textMuted)
            )
        }
    }
}

/**
 * One Home row in the order list. Left/Right moves it, Center hides or shows it, Up/Down leaves
 * the row, so a whole list can be arranged with the D-pad alone.
 */
@Composable
private fun SettingsOrderRow(
    title: String,
    status: String,
    detail: String,
    hidden: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onToggle: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) MusicPalette.surfaceFocused else MusicPalette.surface.copy(alpha = 0.55f)
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MusicPalette.text else MusicPalette.border.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> if (canMoveUp) { onMove(-1); true } else false
                    Key.DirectionRight -> if (canMoveDown) { onMove(1); true } else false
                    Key.DirectionCenter, Key.Enter -> {
                        onToggle()
                        true
                    }
                    Key.DirectionUp -> {
                        focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    Key.DirectionDown -> {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    else -> false
                }
            }
            .clickable { onToggle() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (hidden) MusicPalette.textMuted else MusicPalette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$status · $detail",
                color = MusicPalette.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (hidden) "—" else "≡",
            color = if (hidden) MusicPalette.textMuted else MusicPalette.accent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SettingsActionRow(title: String, detail: String, onClick: () -> Unit) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) MusicPalette.surfaceFocused else MusicPalette.surface.copy(alpha = 0.55f)
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MusicPalette.text else MusicPalette.border.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }
                    Key.DirectionUp -> {
                        focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    Key.DirectionDown -> {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = MusicPalette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                color = MusicPalette.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text("›", color = MusicPalette.textMuted, fontSize = 18.sp)
    }
}

@Composable
private fun SettingsCloseButton(onClick: () -> Unit, onDown: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color(0x33FFFFFF))
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onDown()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.action_close),
            tint = if (focused) MusicPalette.background else Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
