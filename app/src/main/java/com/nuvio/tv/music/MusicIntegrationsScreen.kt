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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCode2
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Last.fm scrobbling, Spotify playlist import and WebDAV in one place. */
@Composable
internal fun MusicIntegrationsScreen(
    onClose: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenNavidrome: () -> Unit,
    onOpenSources: () -> Unit
) {
    val context = LocalContext.current
    val lastFm by MusicScrobbler.config.collectAsState()
    val webDav by MusicWebDav.config.collectAsState()
    val navidrome by MusicNavidrome.config.collectAsState()
    val addons by MusicAddonRegistry.configs.collectAsState()
    val spotifyWeb by MusicSpotifyWebSession.state.collectAsState()
    val webSync by MusicSpotifyWebSync.state.collectAsState()
    var spotifyWebMessage by remember { mutableStateOf<String?>(null) }
    val spotifyLoginLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            spotifyWebMessage = context.getString(R.string.music_integrations_spotify_connected)
        }
    }
    val closeFocus = remember { FocusRequester() }
    val lastFmLoginFocus = remember { FocusRequester() }
    val spotifyImportFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    var lastFmUsername by remember { mutableStateOf("") }
    var lastFmPassword by remember { mutableStateOf("") }
    var lastFmSigningIn by remember { mutableStateOf(false) }
    var lastFmMessage by remember { mutableStateOf<String?>(null) }
    var lastFmError by remember { mutableStateOf<String?>(null) }

    var spotifyUrl by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<String?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var phoneTarget by remember { mutableStateOf<MusicPhoneTarget?>(null) }

    LaunchedEffect(Unit) {
        MusicWebDav.init(context)
        MusicNavidrome.init(context)
        MusicScrobbler.init(context)
        MusicAddonRegistry.init(context)
        MusicSpotifyWebSession.init(context)
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(MusicPalette.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IntegrationCloseButton(
                    modifier = Modifier.focusRequester(closeFocus),
                    onClick = onClose
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_integrations_title),
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 34.sp,
                        lineHeight = 37.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.music_integrations_subtitle),
                        color = MusicPalette.textMuted,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IntegrationCard(
                    icon = Icons.Default.Album,
                    tint = MusicPalette.accentTeal,
                    title = stringResource(R.string.music_integrations_navidrome),
                    subtitle = if (navidrome.configured) {
                        stringResource(R.string.music_integrations_connected_to, navidrome.url)
                    } else {
                        stringResource(R.string.music_integrations_navidrome_help)
                    }
                ) {
                    IntegrationPill(
                        icon = Icons.Default.Album,
                        label = if (navidrome.configured) stringResource(R.string.music_integrations_open_navidrome)
                        else stringResource(R.string.music_integrations_link_navidrome),
                        primary = !navidrome.configured,
                        onClick = onOpenNavidrome
                    )
                }

                IntegrationCard(
                    icon = Icons.Default.Favorite,
                    tint = MusicPalette.accentCoral,
                    title = stringResource(R.string.music_integrations_lastfm),
                    subtitle = when {
                        lastFm.configured && lastFm.enabled -> context.getString(R.string.music_integrations_scrobbling_as, lastFm.username)
                        lastFm.configured -> context.getString(R.string.music_integrations_signed_in_off, lastFm.username)
                        !lastFm.available -> stringResource(R.string.music_integrations_lastfm_unavailable)
                        else -> stringResource(R.string.music_integrations_lastfm_help)
                    }
                ) {
                    if (lastFm.configured) {
                        Text(
                            text = stringResource(R.string.music_integrations_signed_in_as, lastFm.username),
                            color = MusicPalette.textSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            IntegrationPill(
                                icon = Icons.Default.Favorite,
                                label = if (lastFm.enabled) stringResource(R.string.music_integrations_turn_scrobbling_off)
                                else stringResource(R.string.music_integrations_turn_scrobbling_on),
                                primary = !lastFm.enabled,
                                onClick = {
                                    MusicScrobbler.setEnabled(context, !lastFm.enabled)
                                    lastFmMessage = if (lastFm.enabled) {
                                        context.getString(R.string.music_integrations_scrobbling_paused)
                                    } else {
                                        context.getString(R.string.music_integrations_scrobbling_on)
                                    }
                                }
                            )
                            IntegrationPill(
                                icon = Icons.Default.Favorite,
                                label = stringResource(R.string.music_account_sign_out),
                                onClick = {
                                    MusicScrobbler.logout(context)
                                    lastFmUsername = ""
                                    lastFmPassword = ""
                                    lastFmMessage = context.getString(R.string.music_integrations_signed_out)
                                    lastFmError = null
                                }
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IntegrationField(
                                label = stringResource(R.string.music_integrations_username),
                                value = lastFmUsername,
                                placeholder = stringResource(R.string.music_integrations_lastfm_username),
                                onValueChange = { lastFmUsername = it },
                                modifier = Modifier.weight(1f)
                            )
                            IntegrationField(
                                label = stringResource(R.string.music_integrations_password),
                                value = lastFmPassword,
                                placeholder = stringResource(R.string.music_integrations_lastfm_password),
                                onValueChange = { lastFmPassword = it },
                                password = true,
                                modifier = Modifier.weight(1f),
                                downFocus = lastFmLoginFocus
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.music_integrations_lastfm_privacy),
                            color = MusicPalette.textMuted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            IntegrationPill(
                                icon = Icons.Default.Favorite,
                                label = if (lastFmSigningIn) stringResource(R.string.auth_email_signing_in)
                                else stringResource(R.string.music_integrations_log_in),
                                modifier = Modifier.focusRequester(lastFmLoginFocus),
                                primary = true,
                                enabled = !lastFmSigningIn && lastFm.available,
                                onClick = {
                                    if (!lastFmSigningIn) {
                                        lastFmSigningIn = true
                                        lastFmError = null
                                        lastFmMessage = null
                                        scope.launch {
                                            MusicScrobbler.login(context, lastFmUsername, lastFmPassword)
                                                .fold(
                                                    onSuccess = { account ->
                                                        lastFmPassword = ""
                                                        lastFmMessage = context.getString(R.string.music_integrations_signed_in_scrobbling, account)
                                                        lastFmSigningIn = false
                                                    },
                                                    onFailure = { error ->
                                                        lastFmError = error.message
                                                            ?: context.getString(R.string.music_integrations_lastfm_rejected)
                                                        lastFmSigningIn = false
                                                    }
                                                )
                                        }
                                    }
                                }
                            )
                            IntegrationPill(
                                icon = Icons.Default.QrCode2,
                                label = stringResource(R.string.music_link_with_phone),
                                onClick = { phoneTarget = MusicPhoneTarget.LASTFM }
                            )
                        }
                    }
                    lastFmMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, color = MusicPalette.accentTeal, fontSize = 11.sp)
                    }
                    lastFmError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, color = MusicPalette.error, fontSize = 11.sp)
                    }
                }

                IntegrationCard(
                    icon = Icons.Default.Cloud,
                    tint = MusicPalette.accent,
                    title = stringResource(R.string.music_integrations_sources),
                    subtitle = when {
                        addons.isEmpty() -> stringResource(R.string.music_integrations_addons_help)
                        addons.count { it.enabled } == 0 -> context.getString(R.string.music_integrations_addons_off, addons.size)
                        else -> context.getString(R.string.music_integrations_addons_connected, addons.count { it.enabled })
                    }
                ) {
                    IntegrationPill(
                        icon = Icons.Default.Cloud,
                        label = if (addons.isEmpty()) stringResource(R.string.music_integrations_add_source)
                        else stringResource(R.string.music_integrations_manage_sources),
                        primary = addons.isEmpty(),
                        onClick = onOpenSources
                    )
                }

                IntegrationCard(
                    icon = Icons.Default.MusicNote,
                    tint = MusicPalette.accentTeal,
                    title = stringResource(R.string.music_integrations_spotify),
                    subtitle = when {
                        spotifyWeb.connected ->
                            stringResource(
                                R.string.music_p2_connected_as,
                                spotifyWeb.accountName
                                    ?: stringResource(R.string.music_integrations_spotify_account)
                            )
                        else -> stringResource(R.string.music_integrations_spotify_subtitle)
                    }
                ) {
                    if (spotifyWeb.connected) {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            IntegrationPill(
                                icon = Icons.Default.Sync,
                                label = if (webSync.busy) stringResource(R.string.music_integrations_syncing)
                                else stringResource(R.string.music_integrations_sync_spotify),
                                primary = true,
                                // Keep it focusable while syncing: disabling it moved focus to Last.fm.
                                enabled = true,
                                onClick = { if (!webSync.busy) MusicSpotifyWebSync.sync(context) }
                            )
                            IntegrationPill(
                                icon = Icons.Default.Login,
                                label = stringResource(R.string.music_integrations_relink),
                                enabled = !webSync.busy,
                                onClick = {
                                    spotifyLoginLauncher.launch(
                                        android.content.Intent(context, SpotifyLoginActivity::class.java)
                                    )
                                }
                            )
                            IntegrationPill(
                                icon = Icons.Default.Logout,
                                label = stringResource(R.string.music_p2_disconnect),
                                enabled = !webSync.busy,
                                onClick = {
                                    MusicSpotifyWebSession.clear(context)
                                    MusicSpotifyWebSync.reset()
                                    spotifyWebMessage = context.getString(R.string.music_integrations_spotify_disconnected)
                                }
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            IntegrationPill(
                                icon = Icons.Default.Login,
                                label = stringResource(R.string.music_integrations_spotify_sign_in_tv),
                                primary = true,
                                onClick = {
                                    spotifyLoginLauncher.launch(
                                        android.content.Intent(context, SpotifyLoginActivity::class.java)
                                    )
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.music_integrations_spotify_sign_in_help),
                            color = MusicPalette.textMuted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                    spotifyWebMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, color = MusicPalette.accentTeal, fontSize = 11.sp)
                    }
                    webSync.progress?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, color = MusicPalette.textSecondary, fontSize = 11.sp)
                    }
                    webSync.result?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, color = MusicPalette.accentTeal, fontSize = 11.sp)
                    }
                    webSync.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, color = MusicPalette.error, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.music_integrations_or_import),
                        color = MusicPalette.textMuted,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    IntegrationField(
                        label = stringResource(R.string.music_integrations_playlist_link),
                        value = spotifyUrl,
                        placeholder = "https://open.spotify.com/playlist/…",
                        onValueChange = { spotifyUrl = it },
                        downFocus = spotifyImportFocus
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IntegrationPill(
                            icon = Icons.Default.MusicNote,
                            label = if (importing) stringResource(R.string.music_integrations_importing)
                            else stringResource(R.string.music_integrations_import_playlist),
                            modifier = Modifier.focusRequester(spotifyImportFocus),
                            primary = true,
                            enabled = !importing,
                            onClick = {
                                if (spotifyUrl.isBlank()) {
                                    importError = context.getString(R.string.music_integrations_paste_spotify_link)
                                } else if (!importing) {
                                    importing = true
                                    importProgress = context.getString(R.string.music_integrations_reading_playlist)
                                    importResult = null
                                    importError = null
                                    scope.launch {
                                        MusicSpotifyImport.import(
                                            context,
                                            spotifyUrl,
                                            onProgress = { done, total ->
                                                withContext(Dispatchers.Main) {
                                                    importProgress = context.getString(R.string.music_integrations_matched_tracks, done, total)
                                                }
                                            }
                                        ).fold(
                                            onSuccess = { result ->
                                                importProgress = null
                                                importResult = context.getString(
                                                    R.string.music_integrations_imported_tracks,
                                                    result.imported,
                                                    result.total,
                                                    result.playlistName
                                                )
                                                importing = false
                                            },
                                            onFailure = { error ->
                                                importProgress = null
                                                importError = error.message
                                                    ?: context.getString(R.string.music_integrations_import_failed)
                                                importing = false
                                            }
                                        )
                                    }
                                }
                            }
                        )
                        IntegrationPill(
                            icon = Icons.Default.QrCode2,
                            label = stringResource(R.string.music_link_with_phone),
                            onClick = { phoneTarget = MusicPhoneTarget.SPOTIFY }
                        )
                    }
                    importProgress?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, color = MusicPalette.textSecondary, fontSize = 11.sp)
                    }
                    importResult?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, color = MusicPalette.accentTeal, fontSize = 11.sp)
                    }
                    importError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, color = MusicPalette.error, fontSize = 11.sp)
                    }
                }

                IntegrationCard(
                    icon = Icons.Default.Cloud,
                    tint = MusicPalette.accent,
                    title = stringResource(R.string.music_integrations_webdav),
                    subtitle = if (webDav.configured) {
                        stringResource(R.string.music_integrations_connected_to, webDav.url)
                    } else {
                        stringResource(R.string.music_integrations_webdav_help)
                    }
                ) {
                    IntegrationPill(
                        icon = Icons.Default.Cloud,
                        label = if (webDav.configured) stringResource(R.string.music_integrations_open_webdav)
                        else stringResource(R.string.music_integrations_setup_webdav),
                        onClick = onOpenWebDav
                    )
                }
            }
        }
        phoneTarget?.let {
            MusicPhoneLinkPanel(target = it, onClose = { phoneTarget = null })
        }
    }
}

@Composable
private fun IntegrationCard(
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
            Column {
                Text(
                    text = title,
                    color = MusicPalette.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = subtitle, color = MusicPalette.textSecondary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun IntegrationField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    downFocus: FocusRequester? = null
) {
    MusicTextField(
        label = label,
        value = value,
        placeholder = placeholder,
        onValueChange = onValueChange,
        modifier = modifier,
        password = password,
        downFocus = downFocus
    )
}

@Composable
private fun IntegrationPill(
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
private fun IntegrationCloseButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
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
            contentDescription = stringResource(R.string.music_integrations_close),
            tint = if (focused) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(20.dp)
        )
    }
}
