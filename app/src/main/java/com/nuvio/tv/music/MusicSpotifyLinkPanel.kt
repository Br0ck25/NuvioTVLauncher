package com.nuvio.tv.music

import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode2
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/**
 * The QR side of the Spotify account link. The TV keeps the PKCE verifier; the QR only carries
 * the public callback URL, state, challenge and client ID.
 */
@Composable
internal fun MusicSpotifyLinkPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val state by MusicSpotifyLink.state.collectAsState()
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        MusicSpotifyLink.start(context)
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }
    DisposableEffect(Unit) {
        onDispose { MusicSpotifyLink.stop() }
    }
    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MusicPalette.surface)
                .border(1.dp, MusicPalette.border, RoundedCornerShape(22.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.QrCode2,
                    contentDescription = null,
                    tint = MusicPalette.accentTeal,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (state.connected) "Spotify linked" else "Link Spotify with QR",
                        color = MusicPalette.text,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.connected) {
                            "Connected as ${state.displayName ?: "your Spotify account"}."
                        } else {
                            "Scan the code with your phone camera, then approve access on Spotify."
                        },
                        color = MusicPalette.textSecondary,
                        fontSize = 11.sp
                    )
                }
                SpotifyLinkCloseButton(
                    modifier = Modifier.focusRequester(closeFocus),
                    onClick = onClose
                )
            }

            Spacer(Modifier.height(16.dp))
            if (state.connected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MusicPalette.accentTeal,
                    modifier = Modifier.size(88.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Use “Sync playlists & Liked Songs” in Integrations to bring them into Music.",
                    color = MusicPalette.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            } else {
                val qr = rememberSpotifyQr(state.url)
                Box(
                    Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "Spotify link QR code",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = MusicPalette.background,
                            modifier = Modifier.size(84.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when {
                        state.error != null -> state.error.orEmpty()
                        state.busy -> "Finishing the Spotify link…"
                        state.running -> "Waiting for Spotify…"
                        else -> "Preparing the QR code…"
                    },
                    color = if (state.error != null) MusicPalette.error else MusicPalette.textMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Keep the TV and phone on the same Wi-Fi. The QR expires after five minutes.",
                    color = MusicPalette.textMuted,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (state.connected) {
                    SpotifyLinkPill(
                        icon = Icons.Default.CheckCircle,
                        label = "Done",
                        primary = true,
                        onClick = onClose
                    )
                } else {
                    SpotifyLinkPill(
                        icon = Icons.Default.QrCode2,
                        label = if (state.error != null) "Show new QR" else "New QR",
                        primary = state.error != null,
                        onClick = { MusicSpotifyLink.start(context) }
                    )
                    SpotifyLinkPill(
                        icon = Icons.Default.Close,
                        label = "Cancel",
                        onClick = onClose
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotifyLinkPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> MusicPalette.text
                    primary -> MusicPalette.text
                    else -> MusicPalette.surfaceRaised
                }
            )
            .border(
                1.dp,
                if (focused) Color.White else MusicPalette.border,
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused || primary) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = if (focused || primary) MusicPalette.background else MusicPalette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SpotifyLinkCloseButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
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
            Icons.Default.Close,
            contentDescription = "Close Spotify link",
            tint = if (focused) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun rememberSpotifyQr(content: String?): Bitmap? = remember(content) {
    content?.let { value ->
        runCatching {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 720, 720)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height) { index ->
                if (matrix.get(index % width, index / width)) 0xFF000000.toInt()
                else 0xFFFFFFFF.toInt()
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
}
