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
import androidx.compose.ui.unit.dp
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
            spotifyWebMessage = "Spotify connected."
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
                        text = "Integrations",
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 34.sp,
                        lineHeight = 37.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Scrobbling, playlist import and your own server.",
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
                    title = "Navidrome",
                    subtitle = if (navidrome.configured) {
                        "Connected to ${navidrome.url}"
                    } else {
                        "Link a Navidrome / Subsonic music server."
                    }
                ) {
                    IntegrationPill(
                        icon = Icons.Default.Album,
                        label = if (navidrome.configured) "Open Navidrome" else "Link Navidrome",
                        primary = !navidrome.configured,
                        onClick = onOpenNavidrome
                    )
                }

                IntegrationCard(
                    icon = Icons.Default.Favorite,
                    tint = MusicPalette.accentCoral,
                    title = "Last.fm",
                    subtitle = when {
                        lastFm.configured && lastFm.enabled -> "Scrobbling as ${lastFm.username}."
                        lastFm.configured -> "Signed in as ${lastFm.username}, currently off."
                        !lastFm.available -> "Last.fm sign-in isn't configured in this build."
                        else -> "Log in with your Last.fm account to scrobble."
                    }
                ) {
                    if (lastFm.configured) {
                        Text(
                            text = "Signed in as ${lastFm.username}.",
                            color = MusicPalette.textSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            IntegrationPill(
                                icon = Icons.Default.Favorite,
                                label = if (lastFm.enabled) "Turn scrobbling off" else "Turn scrobbling on",
                                primary = !lastFm.enabled,
                                onClick = {
                                    MusicScrobbler.setEnabled(context, !lastFm.enabled)
                                    lastFmMessage = if (lastFm.enabled) {
                                        "Scrobbling paused."
                                    } else {
                                        "Scrobbling is on."
                                    }
                                }
                            )
                            IntegrationPill(
                                icon = Icons.Default.Favorite,
                                label = "Sign out",
                                onClick = {
                                    MusicScrobbler.logout(context)
                                    lastFmUsername = ""
                                    lastFmPassword = ""
                                    lastFmMessage = "Signed out of Last.fm."
                                    lastFmError = null
                                }
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IntegrationField(
                                label = "Username",
                                value = lastFmUsername,
                                placeholder = "Last.fm username",
                                onValueChange = { lastFmUsername = it },
                                modifier = Modifier.weight(1f)
                            )
                            IntegrationField(
                                label = "Password",
                                value = lastFmPassword,
                                placeholder = "Last.fm password",
                                onValueChange = { lastFmPassword = it },
                                password = true,
                                modifier = Modifier.weight(1f),
                                downFocus = lastFmLoginFocus
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Your password is sent once to Last.fm and never stored. " +
                                "Only the session key stays on this TV.",
                            color = MusicPalette.textMuted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            IntegrationPill(
                                icon = Icons.Default.Favorite,
                                label = if (lastFmSigningIn) "Signing in..." else "Log in",
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
                                                        lastFmMessage = "Signed in as $account. Scrobbling is on."
                                                        lastFmSigningIn = false
                                                    },
                                                    onFailure = { error ->
                                                        lastFmError = error.message
                                                            ?: "Last.fm rejected the sign-in."
                                                        lastFmSigningIn = false
                                                    }
                                                )
                                        }
                                    }
                                }
                            )
                            IntegrationPill(
                                icon = Icons.Default.QrCode2,
                                label = "Link with phone",
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
                    title = "Music sources",
                    subtitle = when {
                        addons.isEmpty() -> "Add BitChord-style addons to search their catalogues."
                        addons.count { it.enabled } == 0 -> "${addons.size} addon(s), all turned off."
                        else -> "${addons.count { it.enabled }} addon(s) connected."
                    }
                ) {
                    IntegrationPill(
                        icon = Icons.Default.Cloud,
                        label = if (addons.isEmpty()) "Add a source" else "Manage sources",
                        primary = addons.isEmpty(),
                        onClick = onOpenSources
                    )
                }

                IntegrationCard(
                    icon = Icons.Default.MusicNote,
                    tint = MusicPalette.accentTeal,
                    title = "Spotify",
                    subtitle = when {
                        spotifyWeb.connected ->
                            "Connected as ${spotifyWeb.accountName ?: "your Spotify account"}."
                        else -> "Sign in on this TV, or import a public playlist."
                    }
                ) {
                    if (spotifyWeb.connected) {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            IntegrationPill(
                                icon = Icons.Default.Sync,
                                label = if (webSync.busy) "Syncing…" else "Sync playlists & Liked Songs",
                                primary = true,
                                // Keep it focusable while syncing: disabling it moved focus to Last.fm.
                                enabled = true,
                                onClick = { if (!webSync.busy) MusicSpotifyWebSync.sync(context) }
                            )
                            IntegrationPill(
                                icon = Icons.Default.Login,
                                label = "Re-link",
                                enabled = !webSync.busy,
                                onClick = {
                                    spotifyLoginLauncher.launch(
                                        android.content.Intent(context, SpotifyLoginActivity::class.java)
                                    )
                                }
                            )
                            IntegrationPill(
                                icon = Icons.Default.Logout,
                                label = "Disconnect",
                                enabled = !webSync.busy,
                                onClick = {
                                    MusicSpotifyWebSession.clear(context)
                                    MusicSpotifyWebSync.reset()
                                    spotifyWebMessage = "Spotify disconnected."
                                }
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            IntegrationPill(
                                icon = Icons.Default.Login,
                                label = "Sign in to Spotify on this TV",
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
                            text = "The TV opens Spotify's real sign-in page. The session cookie stays encrypted on this TV.",
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
                        text = "Or import a public playlist",
                        color = MusicPalette.textMuted,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    IntegrationField(
                        label = "Playlist link",
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
                            label = if (importing) "Importing…" else "Import playlist",
                            modifier = Modifier.focusRequester(spotifyImportFocus),
                            primary = true,
                            enabled = !importing,
                            onClick = {
                                if (spotifyUrl.isBlank()) {
                                    importError = "Paste a Spotify playlist link first."
                                } else if (!importing) {
                                    importing = true
                                    importProgress = "Reading playlist…"
                                    importResult = null
                                    importError = null
                                    scope.launch {
                                        MusicSpotifyImport.import(
                                            context,
                                            spotifyUrl,
                                            onProgress = { done, total ->
                                                withContext(Dispatchers.Main) {
                                                    importProgress = "Matched $done of $total tracks…"
                                                }
                                            }
                                        ).fold(
                                            onSuccess = { result ->
                                                importProgress = null
                                                importResult = "Imported ${result.imported} of " +
                                                    "${result.total} tracks into " +
                                                    "“${result.playlistName}”."
                                                importing = false
                                            },
                                            onFailure = { error ->
                                                importProgress = null
                                                importError = error.message
                                                    ?: "The playlist couldn't be imported."
                                                importing = false
                                            }
                                        )
                                    }
                                }
                            }
                        )
                        IntegrationPill(
                            icon = Icons.Default.QrCode2,
                            label = "Link with phone",
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
                    title = "WebDAV",
                    subtitle = if (webDav.configured) {
                        "Connected to ${webDav.url}"
                    } else {
                        "Stream a music folder from your own server."
                    }
                ) {
                    IntegrationPill(
                        icon = Icons.Default.Cloud,
                        label = if (webDav.configured) "Open WebDAV" else "Set up WebDAV",
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
            contentDescription = "Close integrations",
            tint = if (focused) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(20.dp)
        )
    }
}
