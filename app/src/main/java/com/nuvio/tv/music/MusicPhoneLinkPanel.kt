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
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
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
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode2

/**
 * A reusable "scan this on your phone" panel for the simpler integrations.
 *
 * The manual form stays on the screen underneath; this panel is only the phone shortcut.
 */
@Composable
internal fun MusicPhoneLinkPanel(
    target: MusicPhoneTarget,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val state by MusicPhoneSetup.state.collectAsState()
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(target) {
        MusicPhoneSetup.start(context, target)
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }
    DisposableEffect(Unit) {
        onDispose { MusicPhoneSetup.stop() }
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
                    tint = MusicPalette.accent,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_phone_link_target, target.label),
                        color = MusicPalette.text,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.music_phone_scan_help),
                        color = MusicPalette.textSecondary,
                        fontSize = 11.sp
                    )
                }
                PhoneLinkCloseButton(
                    modifier = Modifier.focusRequester(closeFocus),
                    onClick = onClose
                )
            }
            Spacer(Modifier.height(16.dp))
            val qr = rememberPhoneQr(state.url)
            Box(
                Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = stringResource(R.string.music_phone_qr),
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
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.url ?: state.error ?: stringResource(R.string.music_phone_starting),
                color = if (state.error != null) MusicPalette.error else MusicPalette.textMuted,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            state.progress?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, color = MusicPalette.accentTeal, fontSize = 11.sp)
            }
            state.result?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MusicPalette.accentTeal,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, color = MusicPalette.accentTeal, fontSize = 11.sp)
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MusicPalette.error,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.music_phone_wifi_help),
                color = MusicPalette.textMuted,
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PhoneLinkPill(
                    icon = Icons.Default.QrCode2,
                    label = if (state.error != null) stringResource(R.string.music_show_new_qr)
                    else stringResource(R.string.music_new_qr),
                    primary = state.error != null,
                    onClick = { MusicPhoneSetup.start(context, target) }
                )
                PhoneLinkPill(
                    icon = Icons.Default.Close,
                    label = stringResource(R.string.music_done),
                    onClick = onClose
                )
            }
        }
    }
}

@Composable
private fun PhoneLinkPill(
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
            .padding(horizontal = 14.dp, vertical = 8.dp),
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
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            color = when {
                focused -> MusicPalette.background
                primary -> MusicPalette.background
                else -> MusicPalette.text
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PhoneLinkCloseButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else MusicPalette.surfaceRaised)
            .border(1.dp, if (focused) Color.White else MusicPalette.border, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.action_close),
            tint = if (focused) MusicPalette.background else MusicPalette.textSecondary,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun rememberPhoneQr(content: String?): Bitmap? = remember(content) {
    content?.let { value ->
        runCatching {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640)
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
