package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.youtube.YtTab

/**
 * Puts the YouTube tabs in the order you want. It handles the remote itself and keeps it until you press
 * Back: Up and Down choose a tab, OK picks it up, Up and Down then move it, and OK puts it down.
 */
@Composable
internal fun TabOrderEditor(
    order: List<YtTab>,
    hidden: Set<String>,
    onChange: (List<YtTab>) -> Unit,
    onDone: () -> Unit
) {
    val focus = remember { FocusRequester() }
    var selected by remember { mutableIntStateOf(0) }
    var grabbed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDone() }

    Column(
        Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                val handled = when (e.key) {
                    Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight,
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> true
                    else -> false
                }
                if (handled && e.type == KeyEventType.KeyDown) {
                    val last = order.lastIndex
                    when (e.key) {
                        Key.DirectionDown -> if (selected < last) {
                            if (grabbed) onChange(order.toMutableList().also { it.add(selected + 1, it.removeAt(selected)) })
                            selected += 1
                        }
                        Key.DirectionUp -> if (selected > 0) {
                            if (grabbed) onChange(order.toMutableList().also { it.add(selected - 1, it.removeAt(selected)) })
                            selected -= 1
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> grabbed = !grabbed
                        else -> Unit
                    }
                }
                handled
            }
            .padding(vertical = NuvioTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Up and Down choose a tab. Press OK to pick it up, move it, then OK to put it down. Back when you're done.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        order.forEachIndexed { index, tab ->
            val isSelected = index == selected
            val isGrabbed = isSelected && grabbed
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isGrabbed) Color(0xFF3A2A33) else Color(0xFF1C1C1E))
                    .border(2.dp, if (isSelected) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${index + 1}", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (tab.name in hidden) {
                        stringResource(R.string.onetube_tab_hidden, stringResource(tab.titleRes))
                    } else {
                        stringResource(tab.titleRes)
                    },
                    color = if (tab.name in hidden) Color.Gray else NuvioTheme.colors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isGrabbed) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (isGrabbed) Text("▲ ▼", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
