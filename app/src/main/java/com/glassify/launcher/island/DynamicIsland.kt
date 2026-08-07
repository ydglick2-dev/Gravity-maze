package com.glassify.launcher.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glassify.launcher.glass.GlassMotion
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.SquircleShape

/**
 * The Dynamic Island.
 *
 * A black pill that grows out of the camera cutout and morphs between states.
 * Two details do the heavy lifting:
 *
 *  - The pill is *opaque black*, not glass. On an OLED panel true black is
 *    indistinguishable from the cutout itself, which is the entire trick — the
 *    hardware hole and the software pill read as one object.
 *  - Width and height animate on a bouncy spring while the contents cross-fade.
 *    Morphing the container and swapping the contents at different rates is what
 *    makes it look like one thing changing shape rather than two views swapping.
 */
@Composable
fun DynamicIsland(
    state: IslandState,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onTogglePlayback: () -> Unit = {},
) {
    val width by animateDpAsState(
        targetValue = when (state) {
            is IslandState.Idle -> 126.dp
            is IslandState.Charging -> 186.dp
            is IslandState.Alert -> 268.dp
            is IslandState.Media -> 292.dp
        },
        animationSpec = GlassMotion.gooey(),
        label = "island-width",
    )
    val height by animateDpAsState(
        targetValue = when (state) {
            is IslandState.Idle -> 34.dp
            is IslandState.Charging -> 42.dp
            is IslandState.Alert -> 62.dp
            is IslandState.Media -> 68.dp
        },
        animationSpec = GlassMotion.gooey(),
        label = "island-height",
    )

    Box(
        modifier
            .width(width)
            .height(height)
            // A pill at idle, softening into a squircle as it grows — matching
            // the corner radius to the height keeps the ends fully round at
            // every size.
            .clip(SquircleShape(radius = height / 2))
            .background(Color.Black)
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onTogglePlayback() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(180, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
            label = "island-content",
        ) { current ->
            when (current) {
                is IslandState.Idle -> Box(Modifier.fillMaxSize())
                is IslandState.Media -> MediaContent(current)
                is IslandState.Charging -> ChargingContent(current)
                is IslandState.Alert -> AlertContent(current)
            }
        }
    }
}

@Composable
private fun MediaContent(state: IslandState.Media) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(GlassShapes.icon)
                .background(Color.White.copy(alpha = 0.10f)),
        ) {
            state.artwork?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = state.title,
                style = GlassTheme.type.footnote,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.artist.isNotBlank()) {
                Text(
                    text = state.artist,
                    style = GlassTheme.type.caption,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Waveform(playing = state.playing)
    }
}

/**
 * Four bars breathing at different rates.
 *
 * Not a real spectrum — reading the audio stream would need a recording
 * permission that no launcher has any business asking for. Offsetting each bar's
 * period so they never resynchronise is what stops it from reading as a loop.
 */
@Composable
private fun Waveform(playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val periods = listOf(420, 560, 330, 500)
    val heights = periods.mapIndexed { index, period ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = period, delayMillis = index * 40),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar-$index",
        )
    }

    Canvas(Modifier.size(width = 22.dp, height = 20.dp)) {
        val gap = size.width * 0.16f
        val barWidth = (size.width - gap * 3) / 4f
        heights.forEachIndexed { index, animated ->
            val fraction = if (playing) animated.value else 0.22f
            val barHeight = size.height * fraction
            drawRoundRect(
                color = Color(0xFF30D158),
                topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

@Composable
private fun ChargingContent(state: IslandState.Charging) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(GlassTheme.colors.positive)
        )
        Text(
            text = "${state.percent}%",
            style = GlassTheme.type.footnote,
            color = Color.White,
        )
        Box(Modifier.weight(1f))
        Box(
            Modifier
                .height(4.dp)
                .width(56.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White.copy(alpha = 0.18f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.percent / 100f)
                    .fillMaxSize()
                    .background(GlassTheme.colors.positive)
            )
        }
    }
}

@Composable
private fun AlertContent(state: IslandState.Alert) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(GlassShapes.icon)
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.notification.appLabel.take(1).uppercase(),
                style = GlassTheme.type.footnote,
                color = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = state.notification.title.ifBlank { state.notification.appLabel },
                style = GlassTheme.type.caption,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.notification.text,
                style = GlassTheme.type.caption,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
