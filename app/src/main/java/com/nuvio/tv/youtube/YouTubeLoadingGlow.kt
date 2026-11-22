package com.nuvio.tv.youtube

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small glowing light that runs around a thumbnail's edge while its stream is looked up.
 *
 * The thumbnail's own outline is the track: a faint line follows it, and a hot white/yellow ball
 * with a short ember trail races along it like the lit fuse of a firecracker. Only the one card
 * being opened composes this, and it is one Canvas of flat circles and two small radial gradients,
 * so the cost stays low.
 */
@Composable
fun YouTubeLoadingGlow(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thumbnail-loading")
    // Kept as a State and read inside the draw pass, so the animation does not recompose the card.
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1700, easing = LinearEasing)),
        label = "glow-progress"
    )
    Canvas(modifier) {
        val corner = 10.dp.toPx()
        // The ball rolls on a line just inside the rim, so most of its glow stays on the picture.
        val inset = 5.dp.toPx()
        val width = size.width - inset * 2
        val height = size.height - inset * 2
        val radius = corner.coerceAtMost(minOf(width, height) * 0.5f)

        fun pathAt(padding: Float) = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(padding, padding, size.width - padding, size.height - padding),
                    topLeft = CornerRadius(corner),
                    topRight = CornerRadius(corner),
                    bottomRight = CornerRadius.Zero,
                    bottomLeft = CornerRadius.Zero
                )
            )
        }

        // Dim the picture a little so the running light reads on bright thumbnails.
        drawPath(pathAt(0f), Color.Black.copy(alpha = 0.38f))
        // The track: the edge of the thumbnail, drawn as a thin rail.
        drawPath(
            pathAt(inset),
            Color.White.copy(alpha = 0.24f),
            style = Stroke(1.dp.toPx(), cap = StrokeCap.Round)
        )

        /**
         * A point at [t] (0..1) along the rail, clockwise from the top-left corner's arc. The shape
         * has rounded top corners and square bottom corners, matching the card.
         */
        fun pointAt(t: Float): Offset {
            val arc = (PI / 2).toFloat() * radius
            val top = (width - 2 * radius).coerceAtLeast(0f)
            val side = (height - radius).coerceAtLeast(0f)
            val total = top + arc + side + width + side + arc
            var d = ((t % 1f + 1f) % 1f) * total
            val left = inset
            val topY = inset

            if (d <= top) return Offset(left + radius + d, topY)
            d -= top
            if (d <= arc) {
                val a = -PI.toFloat() / 2 + (d / arc) * (PI.toFloat() / 2)
                return Offset(left + width - radius + radius * cos(a), topY + radius + radius * sin(a))
            }
            d -= arc
            if (d <= side) return Offset(left + width, topY + radius + d)
            d -= side
            if (d <= width) return Offset(left + width - d, topY + height)
            d -= width
            if (d <= side) return Offset(left, topY + height - d)
            d -= side
            val a = PI.toFloat() + (d / arc) * (PI.toFloat() / 2)
            return Offset(left + radius + radius * cos(a), topY + radius + radius * sin(a))
        }

        val head = progress.value
        // The ember trail: dots fading out behind the head, like a fuse burning down.
        val trailDots = 20
        val trailSpan = 0.12f
        for (i in trailDots downTo 1) {
            val fraction = 1f - i / trailDots.toFloat()
            val dot = pointAt(head - trailSpan * (i / trailDots.toFloat()))
            drawCircle(
                color = Color(0xFFFFF2E4).copy(alpha = 0.42f * fraction),
                radius = (2.4.dp.toPx() * fraction + 0.4.dp.toPx()),
                center = dot
            )
        }

        val ball = pointAt(head)
        // Soft white halo first, then the hot core.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.62f),
                    Color(0xFFE8F1FF).copy(alpha = 0.26f),
                    Color.Transparent
                ),
                center = ball,
                radius = 12.dp.toPx()
            ),
            radius = 12.dp.toPx(),
            center = ball
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color(0xFFF2F7FF), Color.Transparent),
                center = ball,
                radius = 5.5.dp.toPx()
            ),
            radius = 5.5.dp.toPx(),
            center = ball
        )
        drawCircle(Color.White, radius = 2.dp.toPx(), center = ball)
    }
}
