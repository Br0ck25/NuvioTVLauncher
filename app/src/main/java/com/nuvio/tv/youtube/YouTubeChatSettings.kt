package com.nuvio.tv.youtube

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.R

/** How the in-player live chat looks. Kept in [YouTubeSettings], applied as the viewer changes it. */
data class ChatAppearance(
    val position: String = "center_end",
    val size: String = "medium",
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val nameColor: Int = 0xFF9BB8FF.toInt(),
    val backgroundOpacity: Int = 60
)

private val POSITIONS = listOf(
    "top_start", "top_center", "top_end",
    "center_start", "center", "center_end",
    "bottom_start", "bottom_center", "bottom_end"
)
private val SIZES = listOf("small", "medium", "large")
private val COLORS = listOf(
    0xFFFFFFFF.toInt(),
    0xFFD0D0D0.toInt(),
    0xFFFFE066.toInt(),
    0xFF7FE3FF.toInt(),
    0xFF8CFF9E.toInt(),
    0xFFFF9B9B.toInt()
)
private val OPACITIES = listOf(0, 25, 50, 75, 90)

fun loadChatAppearance(context: Context) = ChatAppearance(
    position = YouTubeSettings.chatPosition(context),
    size = YouTubeSettings.chatSize(context),
    textColor = YouTubeSettings.chatTextColor(context),
    nameColor = YouTubeSettings.chatNameColor(context),
    backgroundOpacity = YouTubeSettings.chatBackgroundOpacity(context)
)

fun saveChatAppearance(appearance: ChatAppearance, context: Context) {
    YouTubeSettings.setChatPosition(context, appearance.position)
    YouTubeSettings.setChatSize(context, appearance.size)
    YouTubeSettings.setChatTextColor(context, appearance.textColor)
    YouTubeSettings.setChatNameColor(context, appearance.nameColor)
    YouTubeSettings.setChatBackgroundOpacity(context, appearance.backgroundOpacity)
}

@Composable
fun chatPositionLabel(value: String): String = when (value) {
    "top_start" -> stringResource(R.string.onetube_pos_top_left)
    "top_center" -> stringResource(R.string.onetube_pos_top_center)
    "top_end" -> stringResource(R.string.onetube_pos_top_right)
    "center_start" -> stringResource(R.string.onetube_pos_middle_left)
    "center" -> stringResource(R.string.onetube_pos_center)
    "center_end" -> stringResource(R.string.onetube_pos_middle_right)
    "bottom_start" -> stringResource(R.string.onetube_pos_bottom_left)
    "bottom_center" -> stringResource(R.string.onetube_pos_bottom_center)
    else -> stringResource(R.string.onetube_pos_bottom_right)
}

@Composable
fun chatSizeLabel(value: String): String = when (value) {
    "small" -> stringResource(R.string.onetube_size_small)
    "large" -> stringResource(R.string.onetube_size_large)
    else -> stringResource(R.string.onetube_size_medium)
}

@Composable
fun chatColorLabel(value: Int): String = when (value) {
    COLORS[0] -> stringResource(R.string.onetube_colour_white)
    COLORS[1] -> stringResource(R.string.onetube_colour_light_grey)
    COLORS[2] -> stringResource(R.string.onetube_colour_yellow)
    COLORS[3] -> stringResource(R.string.onetube_colour_cyan)
    COLORS[4] -> stringResource(R.string.onetube_colour_green)
    else -> stringResource(R.string.onetube_colour_red)
}

private fun nextPosition(value: String) = POSITIONS[(POSITIONS.indexOf(value).coerceAtLeast(0) + 1) % POSITIONS.size]
private fun nextSize(value: String) = SIZES[(SIZES.indexOf(value).coerceAtLeast(0) + 1) % SIZES.size]
private fun nextColor(value: Int) = COLORS[(COLORS.indexOf(value).coerceAtLeast(0) + 1) % COLORS.size]
private fun nextOpacity(value: Int) = OPACITIES[(OPACITIES.indexOf(value).coerceAtLeast(0) + 1) % OPACITIES.size]

/**
 * Chat appearance settings shown over the player, with the chat itself still visible so every
 * change is seen immediately. Each row cycles when pressed, which keeps it to one remote action.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun YouTubeChatSettingsPanel(
    appearance: ChatAppearance,
    onChange: (ChatAppearance) -> Unit,
    onClose: () -> Unit
) {
    NuvioDialog(
        onDismiss = onClose,
        title = stringResource(R.string.onetube_live_chat),
        subtitle = stringResource(R.string.onetube_changes_now),
        containerBrush = androidx.compose.ui.graphics.SolidColor(
            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.70f)
        ),
        containerBorderColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CycleRow(stringResource(R.string.onetube_position), chatPositionLabel(appearance.position)) {
                onChange(appearance.copy(position = nextPosition(appearance.position)))
            }
            CycleRow(stringResource(R.string.onetube_size), chatSizeLabel(appearance.size)) {
                onChange(appearance.copy(size = nextSize(appearance.size)))
            }
            CycleRow(stringResource(R.string.onetube_text_colour), chatColorLabel(appearance.textColor)) {
                onChange(appearance.copy(textColor = nextColor(appearance.textColor)))
            }
            CycleRow(stringResource(R.string.onetube_name_colour), chatColorLabel(appearance.nameColor)) {
                onChange(appearance.copy(nameColor = nextColor(appearance.nameColor)))
            }
            CycleRow(stringResource(R.string.onetube_background), "${appearance.backgroundOpacity}%") {
                onChange(appearance.copy(backgroundOpacity = nextOpacity(appearance.backgroundOpacity)))
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(44.dp)) { Text(stringResource(R.string.onetube_done)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CycleRow(label: String, value: String, onCycle: () -> Unit) {
    Button(onClick = onCycle, modifier = Modifier.fillMaxWidth().height(44.dp)) {
        Text("$label: $value", maxLines = 1)
    }
}
