package com.glassify.launcher.launcher

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glassify.launcher.data.LaunchableApp
import com.glassify.launcher.glass.GlassMotion
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassTheme

/**
 * One tile on the home screen: artwork, label, and the tap feel.
 *
 * The press animation is the whole point of doing this by hand rather than with
 * a ripple. Material presses grow a coloured circle out from the touch point;
 * iOS instead dips the icon slightly and fades it, and swapping that one
 * behaviour changes the feel of every tap on the device.
 */
@Composable
fun AppTile(
    label: String,
    icon: ImageBitmap?,
    showLabel: Boolean,
    jiggling: Boolean,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onRemove: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = GlassMotion.snappy(),
        label = "icon-press",
    )

    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .jiggle(jiggling)
                .scale(scale),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(GlassShapes.icon)
                    // In edit mode the tile belongs to the drag handler above it.
                    // Installing a tap detector here as well would swallow the
                    // gesture before the drag ever saw it, since children get
                    // first refusal on pointer events.
                    .then(
                        if (jiggling) {
                            Modifier
                        } else {
                            Modifier.pointerInput(onClick, onLongPress) {
                                detectTapGestures(
                                    onPress = {
                                        pressed = true
                                        tryAwaitRelease()
                                        pressed = false
                                    },
                                    onTap = { onClick() },
                                    onLongPress = { onLongPress() },
                                )
                            }
                        }
                    ),
            ) {
                when {
                    content != null -> content()
                    icon != null -> Image(
                        bitmap = icon,
                        contentDescription = label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    else -> Box(Modifier.fillMaxSize().background(GlassTheme.colors.fill))
                }
            }

            if (badge > 0) NotificationBadge(badge, Modifier.align(Alignment.TopEnd))

            // The remove button rides the same jiggle as the icon, offset just
            // outside its top-left corner exactly as on iOS.
            if (jiggling && onRemove != null) {
                RemoveButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 0.dp)
                        .size(22.dp),
                )
            }
        }

        if (showLabel) {
            Text(
                text = label,
                // A wide, soft shadow rather than a hard one: it keeps the label
                // readable over a bright wallpaper while staying invisible over
                // a dark one, which a solid backing plate would not.
                style = GlassTheme.type.iconLabel.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        offset = Offset(0f, 1f),
                        blurRadius = 5f,
                    ),
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The edit-mode wobble.
 *
 * Two things move, not one: a small rotation and a smaller translation, at
 * slightly different rates. A pure rotation reads as mechanical; the offset is
 * what makes the grid look nervous rather than clockwork.
 */
@Composable
private fun Modifier.jiggle(enabled: Boolean): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "jiggle")
    val angle by transition.animateFloat(
        initialValue = -1.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 190),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "jiggle-angle",
    )
    val bob by transition.animateFloat(
        initialValue = -1.1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 230),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "jiggle-bob",
    )
    return graphicsLayer {
        rotationZ = angle
        translationY = bob
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(0xFFE9E9EB))
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        // A minus sign, drawn rather than pulled from an icon font so it keeps
        // the same weight at every density.
        Box(
            Modifier
                .size(width = 11.dp, height = 2.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
                .background(Color(0xFF1C1C1E))
        )
    }
}

@Composable
private fun NotificationBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 1.dp, end = 1.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(GlassTheme.colors.destructive)
            .padding(horizontal = if (count > 9) 5.dp else 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = GlassTheme.type.caption,
            color = Color.White,
        )
    }
}

/**
 * A folder tile: up to nine icons on a blurred plate, laid out 3x3.
 *
 * The plate is deliberately not glass — a folder sits *among* icons rather than
 * over them, and a refracting tile in the middle of the grid draws far more
 * attention than iOS's flat translucent square.
 */
@Composable
fun FolderTile(
    name: String,
    apps: List<LaunchableApp>,
    showLabel: Boolean,
    jiggling: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    AppTile(
        label = name,
        icon = null,
        showLabel = showLabel,
        jiggling = jiggling,
        modifier = modifier,
        onClick = onClick,
        onLongPress = onLongPress,
        content = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(GlassShapes.icon)
                    .background(Color.White.copy(alpha = 0.18f))
                    .padding(6.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    apps.take(9).chunked(3).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            row.forEach { app ->
                                Box(Modifier.weight(1f).fillMaxSize()) {
                                    app.icon?.let {
                                        Image(
                                            bitmap = it,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize().clip(GlassShapes.icon),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                            }
                            repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        },
    )
}

/** Where a tile sits on screen, used to animate a folder open from its icon. */
data class TileBounds(val topLeft: Offset, val size: androidx.compose.ui.geometry.Size)
