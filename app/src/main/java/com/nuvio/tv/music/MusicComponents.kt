package com.nuvio.tv.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.nuvio.tv.R
import kotlin.math.abs
import kotlin.math.max

/** Nuvio One's Music palette: an ink canvas with album-derived violet/cyan light. */
internal object MusicPalette {
    val background = Color(0xFF08070B)
    val backgroundTop = Color(0xFF0E0C14)
    val surface = Color(0xFF14111C)
    val surfaceRaised = Color(0xFF1C1826)
    val surfaceFocused = Color(0xFF271F35)
    val border = Color(0xFF2A2436)
    val text = Color(0xFFF8F6FF)
    val textSecondary = Color(0xFFA9A4B8)
    val textMuted = Color(0xFF6D677E)
    val accent = Color(0xFFA78BFA)
    val accentPurple = Color(0xFF7C3AED)
    val accentSoft = Color(0xFF231A33)
    val accentTeal = Color(0xFF5EEAD4)
    val accentCoral = Color(0xFFFB7185)
    val error = Color(0xFFFF6B6B)
}

/** Schibsted Grotesk: the Music section's single sans family, close to the streaming apps. */
internal val MusicSans = FontFamily(
    Font(R.font.schibsted_grotesk_regular, FontWeight.Normal),
    Font(R.font.schibsted_grotesk_medium, FontWeight.Medium),
    Font(R.font.schibsted_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.schibsted_grotesk_bold, FontWeight.Bold)
)

/** The display face is the same family, so headings and lyrics share one voice. */
internal val MusicDisplay = MusicSans

private val MusicAuroraPairs = listOf(
    Color(0xFF7C3AED) to Color(0xFF22D3EE),
    Color(0xFFFB7185) to Color(0xFFF59E0B),
    Color(0xFF5EEAD4) to Color(0xFF3B82F6),
    Color(0xFFA78BFA) to Color(0xFFF472B6),
    Color(0xFFF59E0B) to Color(0xFFEF4444),
    Color(0xFFA3E635) to Color(0xFF06B6D4)
)

/** Deterministic album colours, so a shelf always wears the same light. */
internal fun musicAuroraColors(seed: String?): Pair<Color, Color> {
    val index = abs((seed ?: "nuvio").hashCode()) % MusicAuroraPairs.size
    return MusicAuroraPairs[index]
}

/** The spinning record that peeks out from behind the artwork. */
@Composable
internal fun MusicVinyl(
    colors: Pair<Color, Color>,
    modifier: Modifier = Modifier,
    spinning: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "musicVinyl")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(26000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "musicVinylAngle"
    )
    Canvas(
        modifier.graphicsLayer {
            rotationZ = if (spinning) angle else 0f
        }
    ) {
        val radius = size.minDimension / 2f
        drawCircle(Color(0xFF0A0A0C), radius)
        for (ring in 1..18) {
            drawCircle(
                color = Color.White.copy(alpha = 0.025f + (ring % 3) * 0.012f),
                radius = radius * (ring / 20f),
                style = Stroke(width = 1.1f)
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.first, colors.second),
                center = center,
                radius = radius * 0.36f
            ),
            radius = radius * 0.36f
        )
        drawCircle(Color(0xFF0B0B0D), radius = radius * 0.075f)
    }
}

/** A soft two-colour wash for the hero and the Now Playing screen. */
@Composable
internal fun MusicAuroraBackground(
    seed: String?,
    modifier: Modifier = Modifier
) {
    val (first, second) = musicAuroraColors(seed)
    Box(
        modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    first.copy(alpha = 0.20f),
                    MusicPalette.background,
                    second.copy(alpha = 0.13f)
                )
            )
        )
    )
}

/**
 * Service metadata often arrives as "Playlist • YouTube Music". The app is called Nuvio,
 * so strip the provider name from descriptive lines without changing song or album titles.
 */
internal fun String.withoutMusicServiceBrand(): String = replace(
    Regex("""(?i)\s*[•·|]\s*YouTube Music"""),
    ""
)
    .replace("YouTube Music", "Music", ignoreCase = true)
    .replace("YouTube", "Music", ignoreCase = true)
    .trim()
    .trim('•', '·', '|', '-')
    .trim()

@Composable
internal fun MusicTag(text: String, color: Color = MusicPalette.textSecondary) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MusicPalette.surfaceRaised.copy(alpha = 0.8f))
            .border(1.dp, MusicPalette.border, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text.uppercase(),
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            maxLines = 1
        )
    }
}

/**
 * The shared frame measurements come straight from the 1280x720 design canvas.
 * The whole section runs at [MusicScreen]'s density scale, so these keep the
 * mockup's 1.5x television proportions rather than its CSS pixel values.
 */
internal val MusicScreenPadding = PaddingValues(horizontal = 26.dp)
internal val MusicCardWidth = 154.dp

@Composable
internal fun MusicArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    iconSize: Dp = 30.dp,
    artworkPx: Int = 480
) {
    val shape = RoundedCornerShape(cornerRadius)
    val (first, second) = musicAuroraColors(url)
    // YouTube serves every size from the same image URL. Ask for a sharp source instead of
    // the 60-120px shelf hint, otherwise the hero and Now Playing upscale a thumbnail.
    val imageUrl = url?.artworkAt(artworkPx)
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        first.copy(alpha = 0.62f),
                        second.copy(alpha = 0.40f),
                        Color(0xFF101015)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl.isNullOrBlank()) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MusicPalette.text.copy(alpha = 0.75f),
                modifier = Modifier.size(iconSize)
            )
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
internal fun MusicCard(
    item: ShelfItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.035f else 1f,
        animationSpec = tween(140),
        label = "musicCardScale"
    )
    val artShape = RoundedCornerShape(12.dp)
    val cardShape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .width(MusicCardWidth)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(cardShape)
            .background(if (focused) MusicPalette.surfaceRaised else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = cardShape
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (item.isVideo) 16f / 9f else 1f)
                .clip(artShape)
        ) {
            MusicArtwork(
                url = item.thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 12.dp
            )
            // Drawn after the artwork, which otherwise covers a border on the container.
            if (focused) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(2.dp, Color.White.copy(alpha = 0.85f), artShape)
                )
            }
            // The mockup only reveals the play affordance on the focused card.
            if (item.videoId != null && focused) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(29.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC08070B))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            color = MusicPalette.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = item.subtitle.withoutMusicServiceBrand()
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = MusicPalette.textSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun MusicShelfRow(
    shelf: HomeShelf,
    onItemClick: (ShelfItem, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 26.dp)) {
            Text(
                text = shelf.title,
                color = MusicPalette.text,
                fontFamily = MusicDisplay,
                fontSize = 21.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold
            )
            val shelfSubtitle = shelf.subtitle.withoutMusicServiceBrand()
            if (shelfSubtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = shelfSubtitle,
                    color = MusicPalette.textSecondary,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 27.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(
                items = shelf.items,
                key = { index, item ->
                    "${item.videoId ?: item.browseId ?: item.title}#$index"
                }
            ) { index, item ->
                MusicCard(item = item, onClick = { onItemClick(item, index) })
            }
        }
    }
}

@Composable
internal fun MusicTrackRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArtwork: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = when {
            focused -> MusicPalette.surfaceFocused
            isCurrent -> MusicPalette.accentSoft
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "musicTrackRowBackground"
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MusicPalette.accent,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = (index + 1).toString(),
                    color = if (focused) MusicPalette.textSecondary else MusicPalette.textMuted,
                    fontSize = 13.sp
                )
            }
        }
        if (showArtwork) {
            MusicArtwork(
                url = song.thumbnailUrl,
                modifier = Modifier.size(44.dp),
                cornerRadius = 7.dp,
                iconSize = 18.dp
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = if (isCurrent) MusicPalette.accent else MusicPalette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(song.artist)
                    if (!song.albumName.isNullOrBlank() && song.albumName != song.title) {
                        if (isNotEmpty()) append(" · ")
                        append(song.albumName)
                    }
                },
                color = MusicPalette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        song.durationText?.takeIf { it.isNotBlank() }?.let { duration ->
            Text(
                text = duration,
                color = MusicPalette.textMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
internal fun MusicBrowseRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    type: BrowseType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MusicPalette.surfaceFocused else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        MusicArtwork(
            url = thumbnailUrl,
            modifier = Modifier
                .size(48.dp)
                .clip(if (type == BrowseType.ARTIST) CircleShape else RoundedCornerShape(8.dp)),
            cornerRadius = if (type == BrowseType.ARTIST) 24.dp else 8.dp,
            iconSize = 18.dp
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = MusicPalette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val cleanSubtitle = subtitle.withoutMusicServiceBrand()
            if (cleanSubtitle.isNotBlank()) {
                Text(
                    text = cleanSubtitle,
                    color = MusicPalette.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = when (type) {
                BrowseType.ALBUM -> "Album"
                BrowseType.ARTIST -> "Artist"
                BrowseType.PLAYLIST -> "Playlist"
                BrowseType.OTHER -> ""
            },
            color = MusicPalette.textMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
internal fun MusicPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(140),
        label = "musicPillScale"
    )
    val background by animateColorAsState(
        targetValue = when {
            selected -> MusicPalette.text
            focused -> MusicPalette.surfaceFocused
            else -> MusicPalette.surface
        },
        animationSpec = tween(140),
        label = "musicPillBackground"
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(
                width = 2.dp,
                color = when {
                    !focused -> Color.Transparent
                    selected -> MusicPalette.accent
                    else -> MusicPalette.text
                },
                shape = RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 11.dp else 20.dp,
                vertical = if (compact) 7.dp else 9.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) MusicPalette.background else MusicPalette.text,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun MusicSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
        Text(
            text = title,
            color = MusicPalette.text,
            fontFamily = MusicDisplay,
            fontSize = 21.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = MusicPalette.textSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun MusicLoading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            color = MusicPalette.accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(38.dp)
        )
    }
}

@Composable
internal fun MusicErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Something went wrong",
            color = MusicPalette.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = MusicPalette.textSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (focused) MusicPalette.text else MusicPalette.surfaceRaised)
                .border(
                    2.dp,
                    if (focused) MusicPalette.text else MusicPalette.border,
                    RoundedCornerShape(50)
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onRetry)
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = if (focused) MusicPalette.background else MusicPalette.text,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Try again",
                color = if (focused) MusicPalette.background else MusicPalette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
internal fun MusicEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MusicPalette.surfaceRaised),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MusicPalette.textSecondary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            color = MusicPalette.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            color = MusicPalette.textSecondary,
            fontSize = 14.sp
        )
    }
}

/** "3:07" / "1:02:44"; negative or unknown lengths become "0:00". */
internal fun formatMusicTime(milliseconds: Long): String {
    val totalSeconds = max(0L, milliseconds / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * A TV text field that does not open the keyboard just because it received focus. The remote
 * lands on the field first, and Center starts editing.
 */
/** The three dancing bars in the now-playing chip. Static when playback is paused. */
@Composable
internal fun MusicPlayingIndicator(playing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "nowPlayingIndicator")
    val fast by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "indicatorFast"
    )
    val medium by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(360, delayMillis = 70, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "indicatorMedium"
    )
    val slow by transition.animateFloat(
        initialValue = 0.38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, delayMillis = 140, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "indicatorSlow"
    )
    val bars = if (playing) listOf(fast, medium, slow) else listOf(0.42f, 0.72f, 0.52f)
    Row(
        modifier = modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        bars.forEach { fraction ->
            Box(
                Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(fraction.coerceIn(0.2f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(MusicPalette.accentTeal)
            )
        }
    }
}

@Composable
internal fun MusicTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    fontSize: TextUnit = 13.sp,
    onDone: () -> Unit = {},
    /** Where D-pad Down goes when the field is focused but not editing. */
    downFocus: FocusRequester? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Column(modifier) {
        Text(
            text = label,
            color = MusicPalette.textMuted,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MusicPalette.backgroundTop)
                .border(
                    2.dp,
                    if (focused) MusicPalette.accent else MusicPalette.border,
                    RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                readOnly = !editing,
                textStyle = TextStyle(
                    color = MusicPalette.text,
                    fontSize = fontSize,
                    fontFamily = MusicSans
                ),
                cursorBrush = SolidColor(MusicPalette.accent),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    editing = false
                    onDone()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) {
                            editing = false
                            keyboard?.hide()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when {
                            !editing && (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                                editing = true
                                keyboard?.show()
                                true
                            }
                            // A read-only text field still consumes D-pad directions. Intercept
                            // them until editing starts so Down reaches the next control instead
                            // of trapping focus on the field.
                            !editing && event.key == Key.DirectionUp -> {
                                focusManager.moveFocus(FocusDirection.Up)
                                true
                            }
                            !editing && event.key == Key.DirectionDown -> {
                                val moved = downFocus != null &&
                                    runCatching { downFocus.requestFocus() }.getOrDefault(false)
                                if (!moved) {
                                    focusManager.moveFocus(FocusDirection.Down)
                                }
                                true
                            }
                            !editing && event.key == Key.DirectionLeft -> {
                                focusManager.moveFocus(FocusDirection.Left)
                                true
                            }
                            !editing && event.key == Key.DirectionRight -> {
                                focusManager.moveFocus(FocusDirection.Right)
                                true
                            }
                            editing && event.key == Key.Back -> {
                                editing = false
                                keyboard?.hide()
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = MusicPalette.textMuted,
                            fontSize = 12.sp
                        )
                    }
                    inner()
                }
            )
        }
    }
}
