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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.music.bitchord.data.model.Song

/** Browse and stream a WebDAV music collection. */
@Composable
internal fun MusicWebDavScreen(
    onClose: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit
) {
    val context = LocalContext.current
    val config by MusicWebDav.config.collectAsState()
    val closeFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    var browseUrl by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WebDavEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        MusicWebDav.init(context)
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }

    LaunchedEffect(config.configured) {
        if (!config.configured) {
            editing = true
            browseUrl = ""
        } else {
            editing = false
            if (browseUrl.isBlank()) browseUrl = config.url
        }
    }

    LaunchedEffect(config.configured, browseUrl, reload) {
        if (!config.configured || browseUrl.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        MusicWebDav.list(context, browseUrl).fold(
            onSuccess = { entries = it },
            onFailure = { error = it.message ?: "Couldn't read that folder." }
        )
        loading = false
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(MusicPalette.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                WebDavCloseButton(
                    modifier = Modifier.focusRequester(closeFocus),
                    onClick = onClose
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_integrations_webdav),
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 34.sp,
                        lineHeight = 37.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.music_webdav_subtitle),
                        color = MusicPalette.textMuted,
                        fontSize = 13.sp
                    )
                }
                if (config.configured && !editing) {
                    WebDavPill(
                        icon = Icons.Default.Refresh,
                        label = stringResource(R.string.music_refresh),
                        onClick = { reload++ }
                    )
                    WebDavPill(
                        icon = Icons.Default.Settings,
                        label = stringResource(R.string.music_server),
                        onClick = { editing = true }
                    )
                } else {
                    WebDavPill(
                        icon = Icons.Default.QrCode2,
                        label = stringResource(R.string.music_link_with_phone),
                        primary = true,
                        onClick = { showPhone = true }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (editing || !config.configured) {
                WebDavConnectForm(
                    current = config,
                    onCancel = if (config.configured) {
                        { editing = false }
                    } else {
                        null
                    },
                    onSave = { url, user, password ->
                        MusicWebDav.save(context, url, user, password)
                        browseUrl = if (url.trim().endsWith("/")) url.trim() else url.trim() + "/"
                        editing = false
                    }
                )
            } else {
                WebDavBrowser(
                    entries = entries,
                    loading = loading,
                    error = error,
                    browseUrl = browseUrl,
                    rootUrl = config.url,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onBack = {
                        browseUrl = parentUrl(browseUrl.takeIf { it != config.url } ?: config.url)
                            .takeIf { it.length >= config.url.length } ?: config.url
                    },
                    onFolder = { browseUrl = it.url },
                    onFile = { entry ->
                        val audio = entries.filter { !it.isDirectory && MusicWebDav.isAudio(it.name) }
                        val index = audio.indexOfFirst { it.url == entry.url }
                        if (index >= 0) onPlay(audio.map(::webDavSong), index)
                    },
                    onPlayAll = {
                        val audio = entries.filter { !it.isDirectory && MusicWebDav.isAudio(it.name) }
                        if (audio.isNotEmpty()) onPlay(audio.map(::webDavSong), 0)
                    },
                    onRetry = { reload++ }
                )
            }
        }
        if (showPhone) {
            MusicPhoneLinkPanel(
                target = MusicPhoneTarget.WEBDAV,
                onClose = { showPhone = false }
            )
        }
    }
}

@Composable
private fun WebDavConnectForm(
    current: WebDavConfig,
    onCancel: (() -> Unit)?,
    onSave: (String, String, String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var url by remember(current.url) { mutableStateOf(current.url) }
    var user by remember(current.username) { mutableStateOf(current.username) }
    var password by remember(current.password) { mutableStateOf(current.password) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier
                .width(720.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MusicPalette.surface)
                .border(1.dp, MusicPalette.border, RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MusicPalette.accent,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.music_webdav_connect),
                    color = MusicPalette.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.music_webdav_help),
                color = MusicPalette.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(18.dp))
            WebDavField(
                label = stringResource(R.string.music_server_url),
                value = url,
                placeholder = "https://example.com/music/",
                onValueChange = { url = it },
                onDone = { keyboard?.hide() }
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WebDavField(
                    label = stringResource(R.string.music_integrations_username),
                    value = user,
                    placeholder = "optional",
                    onValueChange = { user = it },
                    onDone = { keyboard?.hide() },
                    modifier = Modifier.weight(1f)
                )
                WebDavField(
                    label = stringResource(R.string.music_integrations_password),
                    value = password,
                    placeholder = "optional",
                    onValueChange = { password = it },
                    onDone = { keyboard?.hide() },
                    password = true,
                    modifier = Modifier.weight(1f)
                )
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(text = it, color = MusicPalette.error, fontSize = 11.sp)
            }
            val urlError = stringResource(R.string.music_webdav_url_error)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WebDavPill(
                    icon = Icons.Default.Cloud,
                    label = if (current.configured) stringResource(R.string.music_webdav_save_changes)
                    else stringResource(R.string.music_webdav_connect_button),
                    primary = true,
                    onClick = {
                        keyboard?.hide()
                        if (!url.trim().startsWith("http://") &&
                            !url.trim().startsWith("https://")
                        ) {
                            error = urlError
                        } else {
                            error = null
                            onSave(url, user, password)
                        }
                    }
                )
                if (onCancel != null) {
                    WebDavPill(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = stringResource(R.string.action_cancel),
                        onClick = {
                            keyboard?.hide()
                            onCancel()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WebDavField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false
) {
    MusicTextField(
        label = label,
        value = value,
        placeholder = placeholder,
        onValueChange = onValueChange,
        modifier = modifier,
        password = password,
        fontSize = 14.sp,
        onDone = onDone
    )
}

@Composable
private fun WebDavBrowser(
    entries: List<WebDavEntry>,
    loading: Boolean,
    error: String?,
    browseUrl: String,
    rootUrl: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onFolder: (WebDavEntry) -> Unit,
    onFile: (WebDavEntry) -> Unit,
    onPlayAll: () -> Unit,
    onRetry: () -> Unit
) {
    val audio = entries.filter { !it.isDirectory && MusicWebDav.isAudio(it.name) }
    Column(modifier) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (browseUrl != rootUrl) {
            WebDavPill(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Back",
                onClick = onBack
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = folderLabel(browseUrl, rootUrl),
                color = MusicPalette.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${entries.count { it.isDirectory }} folders · ${audio.size} tracks",
                color = MusicPalette.textMuted,
                fontSize = 11.sp
            )
        }
        if (audio.isNotEmpty()) {
            WebDavPill(
                icon = Icons.Default.PlayArrow,
                label = stringResource(R.string.music_play_folder),
                primary = true,
                onClick = onPlayAll
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    when {
        loading -> MusicLoading(Modifier.fillMaxWidth().height(220.dp))
        error != null -> MusicErrorState(
            message = error,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth().height(260.dp)
        )
        entries.isEmpty() -> Box(
            Modifier.fillMaxWidth().height(220.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.music_webdav_no_files),
                color = MusicPalette.textSecondary,
                fontSize = 13.sp
            )
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(
                items = entries,
                key = { _, entry -> entry.url }
            ) { _, entry ->
                WebDavRow(
                    entry = entry,
                    onClick = {
                        if (entry.isDirectory) onFolder(entry) else onFile(entry)
                    }
                )
            }
        }
    }
    }
}

@Composable
private fun WebDavRow(entry: WebDavEntry, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (focused) MusicPalette.surfaceFocused else MusicPalette.surface)
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(11.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.Default.MusicNote,
            contentDescription = null,
            tint = if (entry.isDirectory) MusicPalette.accent else MusicPalette.accentTeal,
            modifier = Modifier.size(20.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = if (entry.isDirectory) entry.name else entry.name.substringBeforeLast('.'),
                color = MusicPalette.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (entry.isDirectory) "Folder" else formatDownloadBytes(entry.size),
                color = MusicPalette.textMuted,
                fontSize = 10.sp
            )
        }
        Text(
            text = if (entry.isDirectory) "Open" else "Play",
            color = MusicPalette.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun WebDavPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> MusicPalette.text
                    primary -> MusicPalette.accent
                    else -> MusicPalette.surfaceRaised
                }
            )
            .border(
                2.dp,
                if (focused) MusicPalette.text else MusicPalette.border,
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                focused -> MusicPalette.background
                primary -> MusicPalette.background
                else -> MusicPalette.text
            },
            modifier = Modifier.size(17.dp)
        )
        Text(
            text = label,
            color = when {
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
private fun WebDavCloseButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
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

private fun webDavSong(entry: WebDavEntry): Song = Song(
    videoId = "webdav:${entry.url}",
    title = entry.name.substringBeforeLast('.').ifBlank { entry.name },
    artist = "WebDAV",
    thumbnailUrl = null,
    localUri = entry.url
)

private fun folderLabel(url: String, rootUrl: String): String {
    if (url == rootUrl) return "Music library"
    val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    return decoded.trimEnd('/').substringAfterLast('/').ifBlank { "Folder" }
}

private fun parentUrl(url: String): String {
    val trimmed = url.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    return if (cut > 8) trimmed.substring(0, cut + 1) else url
}
