package com.nuvio.tv.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Logout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.youtube.YouTubeAccountState

/** The Music section's YouTube account panel, using the same TV device-code sign-in as YouTube. */
@Composable
internal fun MusicAccountScreen(
    account: YouTubeAccountState,
    onClose: () -> Unit,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSelectAccount: (Int) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: () -> Unit
) {
    BackHandler(onBack = onClose)

    val firstActionFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(MusicPalette.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AccountCloseButton(
                    onClick = onClose,
                    onDown = { runCatching { firstActionFocus.requestFocus() } }
                )
                Column {
                    Text(
                        text = "YouTube Music",
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 34.sp,
                        lineHeight = 37.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            account.code != null -> "Waiting for sign-in approval"
                            account.signedIn -> account.accountName ?: "Account"
                            else -> "Not signed in"
                        },
                        color = MusicPalette.textMuted,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            when {
                account.code != null -> SignInCodePanel(account, onCancelSignIn, firstActionFocus)
                account.signedIn -> AccountsPanel(
                    account = account,
                    onSelectAccount = onSelectAccount,
                    onAddAccount = onAddAccount,
                    onSignOut = onSignOut,
                    firstActionFocus = firstActionFocus
                )
                else -> SignedOutPanel(account.error, onSignIn, firstActionFocus)
            }
        }
    }
}

@Composable
private fun SignInCodePanel(
    account: YouTubeAccountState,
    onCancel: () -> Unit,
    firstActionFocus: FocusRequester
) {
    val qr = remember(account.activateLink) {
        account.activateLink?.let { link ->
            runCatching {
                QrCodeGenerator.generate(link, 480, margin = 1).asImageBitmap()
            }.getOrNull()
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstActionFocus.requestFocus() }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        qr?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.music_account_qr),
                modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "Sign in to YouTube Music",
                color = MusicPalette.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (qr != null) {
                    "Scan the code with your phone, or go to ${account.url} and enter:"
                } else {
                    "On your phone or computer, go to ${account.url} and enter:"
                },
                color = MusicPalette.textSecondary,
                fontSize = 15.sp
            )
            Text(
                text = account.code.orEmpty(),
                color = MusicPalette.text,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Waiting for you to approve it…",
                color = MusicPalette.textMuted,
                fontSize = 14.sp
            )
            AccountAction(
                icon = Icons.Default.Close,
                label = stringResource(R.string.action_cancel),
                firstFocus = firstActionFocus,
                onClick = onCancel
            )
        }
    }
}

@Composable
private fun AccountsPanel(
    account: YouTubeAccountState,
    onSelectAccount: (Int) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: () -> Unit,
    firstActionFocus: FocusRequester
) {
    LaunchedEffect(account.accounts.size, account.accountName) {
        withFrameNanos { }
        runCatching { firstActionFocus.requestFocus() }
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        account.accounts.forEachIndexed { index, item ->
            AccountAction(
                icon = if (item.selected) Icons.Default.Check else Icons.Default.AccountCircle,
                label = item.name,
                detail = item.detail,
                selected = item.selected,
                firstFocus = if (index == 0) firstActionFocus else null,
                onClick = { onSelectAccount(item.id) }
            )
        }
        Spacer(Modifier.height(8.dp))
        AccountAction(
            icon = Icons.Default.Add,
            label = stringResource(R.string.music_account_add),
            firstFocus = if (account.accounts.isEmpty()) firstActionFocus else null,
            onClick = onAddAccount
        )
        AccountAction(
            icon = Icons.Default.Logout,
            label = stringResource(R.string.music_account_sign_out_youtube),
            onClick = onSignOut
        )
    }
}

@Composable
private fun SignedOutPanel(
    error: String?,
    onSignIn: () -> Unit,
    firstActionFocus: FocusRequester
) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstActionFocus.requestFocus() }
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Sign in to YouTube Music",
            color = MusicPalette.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Your likes, playlists and personalised Home will follow the same Google " +
                "account you use for YouTube on this TV.",
            color = MusicPalette.textSecondary,
            fontSize = 15.sp
        )
        error?.let {
            Text(it, color = MusicPalette.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(4.dp))
        AccountAction(
            icon = Icons.Default.AccountCircle,
            label = stringResource(R.string.music_account_tv_code),
            firstFocus = firstActionFocus,
            onClick = onSignIn
        )
    }
}

/**
 * One focus target for a menu action. Up/Down are handled explicitly so the panel works on
 * Fire TV even though it lives inside a scroll container.
 */
@Composable
private fun AccountAction(
    icon: ImageVector,
    label: String,
    detail: String? = null,
    selected: Boolean = false,
    firstFocus: FocusRequester? = null,
    onClick: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) MusicPalette.surfaceRaised else Color.Transparent)
            .border(
                2.dp,
                when {
                    focused -> MusicPalette.text
                    selected -> MusicPalette.accent
                    else -> Color.Transparent
                },
                RoundedCornerShape(12.dp)
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
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected && !focused) MusicPalette.accent else MusicPalette.textSecondary,
            modifier = Modifier.size(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = MusicPalette.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = MusicPalette.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Text(
                text = "Current",
                color = MusicPalette.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AccountCloseButton(onClick: () -> Unit, onDown: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color(0x33FFFFFF))
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        onDown()
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.music_account_close),
            tint = if (focused) MusicPalette.background else Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}
