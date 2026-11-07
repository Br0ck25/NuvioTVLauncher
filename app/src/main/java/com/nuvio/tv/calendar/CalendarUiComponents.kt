package com.nuvio.tv.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val LIVE_RED = Color(0xFFE53935)

private val TextDropShadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 1.5f),
    blurRadius = 3f
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 15.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = NuvioTheme.colors.TextPrimary,
    maxLines: Int = 1,
    align: TextAlign? = null,
    shadow: Boolean = false,
    marquee: Boolean = false
) {
    val m = if (marquee && maxLines == 1) {
        modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1600, initialDelayMillis = 600)
    } else {
        modifier
    }
    Text(
        text = text,
        modifier = m,
        style = TextStyle(
            fontSize = size,
            fontWeight = weight,
            color = color,
            textAlign = align ?: TextAlign.Unspecified,
            shadow = if (shadow) TextDropShadow else null
        ),
        maxLines = maxLines,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis
    )
}

@Composable
internal fun LiveTag(
    text: String,
    bg: Color = Color.White.copy(alpha = 0.14f),
    fg: Color = Color.White
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        LiveText(text = text, size = 10.sp, weight = FontWeight.Bold, color = fg)
    }
}

internal data class LiveCellColors(val background: Color, val border: Color, val text: Color) {
    val bg: Color get() = background
}

@Composable
internal fun liveCellColors(focused: Boolean, idle: Color, idleText: Color = NuvioTheme.colors.TextPrimary): LiveCellColors {
    if (!focused) return LiveCellColors(idle, Color.Transparent, idleText)
    return LiveCellColors(NuvioTheme.colors.FocusBackground, NuvioTheme.colors.FocusRing, NuvioTheme.colors.TextPrimary)
}

@Composable
internal fun liveCellColors(focused: Boolean, selected: Boolean = false, baseAlpha: Float = 0.55f): LiveCellColors {
    val c = NuvioTheme.colors
    return when {
        focused -> LiveCellColors(c.FocusBackground, c.FocusRing, c.TextPrimary)
        selected -> LiveCellColors(c.SurfaceVariant.copy(alpha = 0.85f), c.FocusRing.copy(alpha = 0.6f), c.TextPrimary)
        else -> LiveCellColors(c.BackgroundCard.copy(alpha = baseAlpha), c.Border.copy(alpha = 0.45f), c.TextPrimary)
    }
}

@Composable
internal fun focusedTextColor(@Suppress("UNUSED_PARAMETER") focused: Boolean, normal: Color = NuvioTheme.colors.TextPrimary): Color =
    normal

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveFocusRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    corner: Dp = 8.dp,
    baseAlpha: Float = 0.55f,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, selected, baseAlpha)
    val shape = RoundedCornerShape(corner)
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.bg)
            .border(if (focused) 2.dp else 1.dp, colors.border, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        content(focused)
    }
}

@Composable
internal fun LiveDialog(
    onDismiss: () -> Unit,
    width: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val openedAt = remember { System.currentTimeMillis() }
    var armed by remember { mutableStateOf(false) }
    val dialogBg = Color(0xFF0A0D12).copy(alpha = 0.90f)
    val dialogBorder = Color.White.copy(alpha = 0.16f)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { ev ->
                    val k = ev.key
                    val isConfirm = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
                    if (isConfirm && !armed) {
                        if (ev.type == KeyEventType.KeyDown && System.currentTimeMillis() - openedAt > 250L) {
                            armed = true
                            false
                        } else if (System.currentTimeMillis() - openedAt < 900L) {
                            true
                        } else {
                            armed = true
                            false
                        }
                    } else false
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = width)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(dialogBg)
                    .border(1.5.dp, dialogBorder, shape)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

/** One line in a Calendar menu. [hint]: a quieter second line. */
internal data class MenuAction(val text: String, val hint: String? = null, val onClick: () -> Unit)

/** A Calendar menu in a dark glass dialog: a heading, then [actions] (the first one highlighted). */
@Composable
internal fun CalendarMenu(
    kicker: String?,
    title: String,
    subtitle: String?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LiveDialog(onDismiss = onDismiss, width = 560.dp) {
        kicker?.let { LiveText(it, size = 11.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary) }
        LiveText(title, size = 21.sp, weight = FontWeight.Bold, maxLines = 2)
        subtitle?.let { LiveText(it, size = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2) }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 430.dp)) {
            itemsIndexed(actions) { i, a ->
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    onClick = a.onClick
                ) { focused ->
                    Column {
                        LiveText(a.text, size = 15.sp, weight = if (focused) FontWeight.SemiBold else FontWeight.Medium, color = focusedTextColor(focused))
                        a.hint?.let { LiveText(it, size = 12.sp, color = focusedTextColor(focused).copy(alpha = 0.65f), maxLines = 2) }
                    }
                }
            }
            if (footer != null) item { footer() }
        }
    }
}

/** A small on/off switch. */
@Composable
internal fun SwitchMark(on: Boolean) {
    Box(
        Modifier.size(width = 34.dp, height = 19.dp).clip(RoundedCornerShape(50))
            .background(if (on) Color(0xFF2E7D4F) else Color.White.copy(alpha = 0.22f)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) { Box(Modifier.padding(2.dp).size(15.dp).clip(RoundedCornerShape(50)).background(Color.White)) }
}

internal fun formatClock(ms: Long, use24h: Boolean): String {
    val pattern = if (use24h) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

/**
 * A small message bar at the bottom of the screen for Calendar actions (ringing/unringing the bell,
 * hiding a show). Fades away after a few seconds on its own.
 */
object CalendarNotice {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()

    fun show(message: String) {
        _text.value = message
    }

    fun clear() {
        _text.value = null
    }
}

@Composable
fun CalendarNoticeHost() {
    val message by CalendarNotice.text.collectAsState()
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_500)
            CalendarNotice.clear()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
            val m = message ?: return@AnimatedVisibility
            Row(
                modifier = Modifier
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEE10141C))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(LIVE_RED))
                Text(m, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
