package com.nuvio.tv.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Music sources.
 *
 * Addons are plain HTTP JSON servers: Nuvio reads their manifest, asks `/search` for tracks and
 * `/stream/{id}` for audio. Nothing an addon serves is downloaded or executed as code.
 */
@Composable
internal fun MusicSourcesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val addons by MusicAddonRegistry.configs.collectAsState()
    val lossless by MusicLosslessOutput.state.collectAsState()
    val preferAddons by MusicSettings.preferAddonSources.collectAsState()
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }

    var url by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        MusicAddonRegistry.init(context)
        MusicLosslessOutput.init(context)
        MusicLosslessOutput.refresh()
        MusicSettings.init(context)
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }
    BackHandler(onBack = onClose)

    fun report(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    Box(Modifier.fillMaxSize().background(MusicPalette.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SourceCloseButton(
                    modifier = Modifier.focusRequester(closeFocus),
                    onClick = onClose
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_integrations_sources),
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 34.sp,
                        lineHeight = 37.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.music_sources_subtitle),
                        color = MusicPalette.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = if (addons.isEmpty()) stringResource(R.string.music_sources_none_added)
                    else stringResource(R.string.music_sources_added_count, addons.size),
                    color = MusicPalette.textMuted,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = lossless.label,
                color = if (lossless.capable) MusicPalette.accentTeal else MusicPalette.textMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SourceCard(
                    icon = Icons.Default.Add,
                    tint = MusicPalette.accent,
                    title = stringResource(R.string.music_sources_add_title),
                    subtitle = stringResource(R.string.music_sources_add_subtitle)
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MusicTextField(
                            label = stringResource(R.string.music_sources_addon_url),
                            value = url,
                            placeholder = "https://example.com/addon/",
                            onValueChange = { url = it },
                            modifier = Modifier.weight(1f),
                            downFocus = addFocus
                        )
                        SourcePill(
                            icon = Icons.Default.Add,
                            label = if (working) stringResource(R.string.music_sources_checking)
                            else stringResource(R.string.music_sources_test_add),
                            modifier = Modifier.focusRequester(addFocus),
                            primary = true,
                            enabled = !working,
                            onClick = {
                                if (url.isBlank()) {
                                    report(context.getString(R.string.music_sources_paste_url_first), error = true)
                                } else if (!working) {
                                    working = true
                                    report("")
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            MusicAddonRegistry.add(url)
                                        }
                                        result.fold(
                                            onSuccess = { addon ->
                                                url = ""
                                                report(context.getString(R.string.music_sources_added_name, addon.name))
                                            },
                                            onFailure = { error ->
                                                report(
                                                    error.message ?: context.getString(R.string.music_sources_add_failed),
                                                    error = true
                                                )
                                            }
                                        )
                                        working = false
                                    }
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.music_sources_protocol_help),
                        color = MusicPalette.textMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                SourceCard(
                    icon = Icons.Default.PlayArrow,
                    tint = MusicPalette.accentTeal,
                    title = stringResource(R.string.music_sources_prefer_title),
                    subtitle = if (preferAddons) {
                        stringResource(R.string.music_sources_prefer_on_subtitle)
                    } else {
                        stringResource(R.string.music_sources_prefer_off_subtitle)
                    }
                ) {
                    SourcePill(
                        icon = Icons.Default.PlayArrow,
                        label = if (preferAddons) stringResource(R.string.music_sources_on)
                        else stringResource(R.string.music_sources_off),
                        primary = preferAddons,
                        onClick = {
                            MusicSettings.setPreferAddonSources(!preferAddons)
                            report(
                                context.getString(
                                    if (preferAddons) R.string.music_sources_substitution_off
                                    else R.string.music_sources_substitution_on
                                )
                            )
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.music_sources_prefer_help),
                        color = MusicPalette.textMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                if (addons.isEmpty()) {
                    SourceCard(
                        icon = Icons.Default.Cloud,
                        tint = MusicPalette.accentTeal,
                        title = stringResource(R.string.music_sources_none_title),
                        subtitle = stringResource(R.string.music_sources_none_subtitle)
                    ) {
                        Text(
                            text = stringResource(R.string.music_sources_none_help),
                            color = MusicPalette.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                } else {
                    addons.forEach { addon ->
                        SourceCard(
                            icon = Icons.Default.MusicNote,
                            tint = if (addon.enabled) MusicPalette.accentTeal else MusicPalette.textMuted,
                            title = addon.name,
                            subtitle = addon.baseUrl
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                addon.qualities.takeIf { it.size > 1 }?.let { options ->
                                    val currentIndex = options
                                        .indexOfFirst { it.value == addon.quality }
                                        .coerceAtLeast(0)
                                    SourcePill(
                                        icon = Icons.Default.MusicNote,
                                        label = stringResource(R.string.music_sources_quality, options[currentIndex].label),
                                        onClick = {
                                            val next = options[(currentIndex + 1) % options.size]
                                            MusicAddonRegistry.setQuality(addon.id, next.value)
                                            report(
                                                context.getString(
                                                    R.string.music_sources_quality_ask,
                                                    addon.name,
                                                    next.label
                                                )
                                            )
                                        }
                                    )
                                }
                                SourcePill(
                                    icon = Icons.Default.Cloud,
                                    label = if (testingId == addon.id) stringResource(R.string.music_sources_checking)
                                    else stringResource(R.string.plugin_test_btn),
                                    enabled = testingId == null,
                                    onClick = {
                                        testingId = addon.id
                                        report("")
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                MusicAddonRegistry.test(addon.baseUrl)
                                            }
                                            result.fold(
                                                onSuccess = { manifest ->
                                                    report(
                                                        if (manifest.version.isBlank()) {
                                                            context.getString(
                                                                R.string.music_sources_answered,
                                                                manifest.displayName
                                                            )
                                                        } else {
                                                            context.getString(
                                                                R.string.music_sources_answered_version,
                                                                manifest.displayName,
                                                                manifest.version
                                                            )
                                                        }
                                                    )
                                                },
                                                onFailure = { error ->
                                                    report(
                                                        error.message ?: context.getString(R.string.music_sources_no_answer),
                                                        error = true
                                                    )
                                                }
                                            )
                                            testingId = null
                                        }
                                    }
                                )
                                SourcePill(
                                    icon = Icons.Default.MusicNote,
                                    label = if (addon.enabled) stringResource(R.string.sentry_turn_off)
                                    else stringResource(R.string.sentry_turn_on),
                                    primary = !addon.enabled,
                                    onClick = {
                                        MusicAddonRegistry.setEnabled(addon.id, !addon.enabled)
                                        report(
                                            context.getString(
                                                if (addon.enabled) R.string.music_sources_is_off
                                                else R.string.music_sources_is_on,
                                                addon.name
                                            )
                                        )
                                    }
                                )
                                SourcePill(
                                    icon = Icons.Default.Delete,
                                    label = stringResource(R.string.web_btn_remove),
                                    onClick = {
                                        MusicAddonRegistry.remove(addon.id)
                                        report(context.getString(R.string.music_sources_removed, addon.name))
                                    }
                                )
                            }
                            if (!addon.enabled) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.music_sources_skipped),
                                    color = MusicPalette.textMuted,
                                    fontSize = 10.sp
                                )
                            }
                            if (addon.requiresLossless) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (lossless.capable) {
                                        stringResource(R.string.music_sources_lossless_only)
                                    } else {
                                        stringResource(R.string.music_sources_lossless_needed)
                                    },
                                    color = if (lossless.capable) MusicPalette.textMuted
                                    else MusicPalette.error,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                message?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        color = if (messageIsError) MusicPalette.error else MusicPalette.accentTeal,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun SourceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MusicPalette.surface)
            .border(1.dp, MusicPalette.border, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MusicPalette.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = MusicPalette.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
internal fun SourcePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused && enabled -> MusicPalette.text
                    primary -> MusicPalette.accent
                    else -> MusicPalette.surfaceRaised
                }
            )
            .border(
                2.dp,
                if (focused && enabled) MusicPalette.text else MusicPalette.border,
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> MusicPalette.textMuted
                focused -> MusicPalette.background
                primary -> MusicPalette.background
                else -> MusicPalette.text
            },
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = when {
                !enabled -> MusicPalette.textMuted
                focused -> MusicPalette.background
                primary -> MusicPalette.background
                else -> MusicPalette.text
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SourceCloseButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else MusicPalette.surfaceRaised)
            .border(
                2.dp,
                if (focused) Color.White else MusicPalette.border,
                CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_close),
            tint = if (focused) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(20.dp)
        )
    }
}
