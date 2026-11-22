package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.model.LiveTvSettings
import kotlinx.coroutines.delay

/**
 * The guide's ⚙: the same glass panel as the play bar's, with the switches for how the top of
 * the guide looks. Sits at the top right so you can see the guide change as you flip them.
 */
@Composable
internal fun GuideGearPanel(
    settings: LiveTvSettings,
    onChange: ((LiveTvSettings) -> LiveTvSettings) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }

    @Composable
    fun toggle(modifier: Modifier, title: String, note: String?, on: Boolean, action: () -> Unit) {
        GearRow(modifier, onClick = action) { focused ->
            Column(Modifier.weight(1f)) {
                LiveText(title, size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                note?.let { LiveText(it, size = 10.sp, color = gearText(focused, dim = true), maxLines = 1) }
            }
            Switch(on, focused)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 36.dp, top = 24.dp)
                .width(470.dp)
                .glass(glassColor("darker"), RoundedCornerShape(20.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                LiveText(stringResource(R.string.iptv_gg_title), size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                LiveText(stringResource(R.string.iptv_gg_hint), size = 11.sp, color = Color.White.copy(alpha = 0.55f))
            }
            Spacer(Modifier.height(6.dp))
            val hidden = BarPart.parse(settings.guideInfoHidden)
            fun flip(key: String) = onChange {
                val cur = BarPart.parse(it.guideInfoHidden)
                it.copy(guideInfoHidden = BarPart.join(if (key in cur) cur - key else cur + key))
            }
            @Composable
            fun part(modifier: Modifier, key: String, title: String, note: String? = null) =
                toggle(modifier, title, note, key !in hidden) { flip(key) }
            @Composable
            fun heading(text: String) = LiveText(
                text.uppercase(), size = 10.sp, weight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp)
            )
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                heading(stringResource(R.string.iptv_gg_info_panel))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toggle(Modifier.weight(1f).focusRequester(first), stringResource(R.string.iptv_gg_show_info), stringResource(R.string.iptv_gg_show_info_hint), settings.showProgramDetails) {
                        onChange { it.copy(showProgramDetails = !it.showProgramDetails) }
                    }
                    toggle(Modifier.weight(1f), stringResource(R.string.iptv_gg_poster), stringResource(R.string.iptv_gg_poster_hint), settings.showPosters) {
                        onChange { it.copy(showPosters = !it.showPosters) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.CHANNEL, stringResource(R.string.iptv_gg_channel))
                    part(Modifier.weight(1f), GuideInfoPart.TAGS, stringResource(R.string.iptv_gg_tags), stringResource(R.string.iptv_gg_tags_hint))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.TITLE, stringResource(R.string.iptv_settings_show_title))
                    part(Modifier.weight(1f), GuideInfoPart.TIME, stringResource(R.string.iptv_gg_start_end))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.LEFT, stringResource(R.string.iptv_gg_time_left))
                    toggle(Modifier.weight(1f), stringResource(R.string.iptv_gg_episode), null, settings.showEpisodeNumbers) {
                        onChange { it.copy(showEpisodeNumbers = !it.showEpisodeNumbers) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.CATEGORY, stringResource(R.string.iptv_gg_category))
                    toggle(Modifier.weight(1f), stringResource(R.string.iptv_gg_playlist), null, settings.showPlaylistInInfo) {
                        onChange { it.copy(showPlaylistInInfo = !it.showPlaylistInInfo) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.PROGRESS, stringResource(R.string.iptv_gg_progress))
                    part(Modifier.weight(1f), GuideInfoPart.DESCRIPTION, stringResource(R.string.iptv_guide_description))
                }
                heading(stringResource(R.string.iptv_gg_layout))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toggle(Modifier.weight(1f), stringResource(R.string.iptv_gg_preview), stringResource(R.string.iptv_gg_preview_hint), settings.showPreview) {
                        onChange { it.copy(showPreview = !it.showPreview) }
                    }
                    toggle(Modifier.weight(1f), stringResource(R.string.iptv_gg_smaller_top), stringResource(R.string.iptv_gg_smaller_top_hint), settings.smallHeader) {
                        onChange { it.copy(smallHeader = !it.smallHeader) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toggle(Modifier.weight(1f), stringResource(R.string.iptv_settings_24_hour_clock), null, settings.use24HourClock) {
                        onChange { it.copy(use24HourClock = !it.use24HourClock) }
                    }
                    GearRow(Modifier.weight(1f), onClick = {
                        onChange { it.copy(guideInfoBackground = when (it.guideInfoBackground) { "standard" -> "darker"; "darker" -> "solid"; else -> "standard" }) }
                    }) { focused ->
                        Column(Modifier.weight(1f)) {
                            LiveText(stringResource(R.string.iptv_gg_darkness), size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                        }
                        LiveText(
                            when (settings.guideInfoBackground) {
                                "darker" -> stringResource(R.string.iptv_gear_theme_standard)
                                "solid" -> stringResource(R.string.iptv_gear_theme_darkest)
                                else -> stringResource(R.string.iptv_gear_theme_lightest)
                            },
                            size = 12.sp, color = gearText(focused, dim = true)
                        )
                    }
                }
                GearRow(Modifier.fillMaxWidth(), onClick = {
                    onChange { it.copy(guideBackdrop = GuideBackdrops.next(it.guideBackdrop)) }
                }) { focused ->
                    Column(Modifier.weight(1f)) {
                        LiveText(stringResource(R.string.iptv_gg_background), size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                        LiveText(stringResource(R.string.iptv_gg_background_hint), size = 10.sp, color = gearText(focused, dim = true))
                    }
                    LiveText(
                        GuideBackdrops.label(settings.guideBackdrop),
                        size = 12.sp, color = gearText(focused, dim = true)
                    )
                }
            }
            Box(Modifier.padding(vertical = 6.dp, horizontal = 4.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
            GearRow(Modifier.fillMaxWidth(), onClick = onSettings) { focused ->
                LiveText(stringResource(R.string.iptv_gg_all_settings), size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                LiveText("›", size = 15.sp, color = gearText(focused, dim = true))
            }
        }
    }
}

/** The ⚙ in the guide's top bar: OK opens the Guide display panel, Down goes back to the channels. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun GuideGearButton(modifier: Modifier = Modifier, onOpen: () -> Unit, onDown: () -> Unit) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val cell = liveCellColors(focused, Color.White.copy(alpha = 0.10f))
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(shape)
            .background(cell.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) cell.border else GuideGlassEdge, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionDown) {
                    if (e.type == KeyEventType.KeyDown) onDown()
                    true
                } else false
            }
            .combinedClickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onOpen
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(
            androidx.compose.material.icons.Icons.Default.Settings,
            contentDescription = stringResource(R.string.iptv_gg_title),
            tint = focusedTextColor(focused),
            modifier = Modifier.size(18.dp)
        )
    }
}

/** Multiview uses the guide's existing TV focus treatment and Down-to-guide behavior. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun GuideMultiviewButton(modifier: Modifier = Modifier, onOpen: () -> Unit, onDown: () -> Unit) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val cell = liveCellColors(focused, Color.White.copy(alpha = 0.10f))
    val shape = RoundedCornerShape(10.dp)
    val tint = focusedTextColor(focused)
    val multiviewDescription = stringResource(R.string.iptv_gg_multiview)
    Box(
        modifier.size(34.dp).clip(shape).background(cell.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) cell.border else GuideGlassEdge, shape)
            .semantics { contentDescription = multiviewDescription }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionDown) {
                    if (event.type == KeyEventType.KeyDown) onDown()
                    true
                } else false
            }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onOpen
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val gap = 2.dp.toPx()
            val tile = (size.width - gap) / 2f
            for (row in 0..1) for (column in 0..1) {
                drawRect(tint, androidx.compose.ui.geometry.Offset(column * (tile + gap), row * (tile + gap)),
                    androidx.compose.ui.geometry.Size(tile, tile))
            }
        }
    }
}

/**
 * Recordings, beside the guide's ⚙: what's recorded and set to record, and Schedule a
 * Recording. A red dot while something is recording.
 */
@Composable
internal fun GuideRecordingsButton(modifier: Modifier = Modifier, onOpen: () -> Unit, onDown: () -> Unit) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val recorder = androidx.compose.runtime.remember {
        runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
            ).liveTvRecorder()
        }.getOrNull()
    }
    val recordingNow = recorder?.recordings?.collectAsState()?.value.orEmpty()
        .any { it.status == com.nuvio.tv.livetv.record.RecordingStatus.RECORDING }
    val cell = liveCellColors(focused, Color.White.copy(alpha = 0.10f))
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(shape)
            .background(cell.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) cell.border else GuideGlassEdge, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionDown) {
                    if (e.type == KeyEventType.KeyDown) onDown()
                    true
                } else false
            }
            .combinedClickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onOpen
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(
            androidx.compose.material.icons.Icons.Default.RadioButtonChecked,
            contentDescription = stringResource(R.string.iptv_guide_recordings),
            tint = if (recordingNow) LIVE_RED else focusedTextColor(focused),
            modifier = Modifier.size(18.dp)
        )
    }
}
