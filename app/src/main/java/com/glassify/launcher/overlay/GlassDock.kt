package com.glassify.launcher.overlay

import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.glassify.launcher.data.LaunchableApp
import com.glassify.launcher.glass.GlassMotion
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.liquidGlass
import kotlin.math.roundToInt

/**
 * A glass dock that floats over the home screen.
 *
 * The compositor blurs whatever is behind the window, so this genuinely
 * refracts — well, blurs — the wallpaper and any icons underneath it, rather
 * than faking depth with a gradient. Placed at the bottom it covers the vendor
 * dock, which is as close as an overlay can get to replacing it: the icons
 * around it stay exactly as the real launcher drew them, because nothing short
 * of being the launcher can change those.
 */
@Composable
fun GlassDock(
    apps: List<LaunchableApp>,
    opacity: Float,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) return

    Row(
        modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = GlassShapes.card,
                cornerRadius = 28.dp,
                spec = GlassSpec(surfaceAlpha = opacity),
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        apps.take(MAX_SLOTS).forEach { app ->
            DockIcon(app, Modifier.weight(1f), onLaunch)
        }
    }
}

@Composable
private fun DockIcon(
    app: LaunchableApp,
    modifier: Modifier = Modifier,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember(app.key) { mutableStateOf<Rect?>(null) }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = GlassMotion.snappy(),
        label = "dock-press",
    )

    Box(
        modifier
            .padding(horizontal = 6.dp)
            .aspectRatio(1f)
            .scale(scale)
            .clip(GlassShapes.icon)
            .onGloballyPositioned { coordinates ->
                // Gives the system a rectangle to grow the opening app out of,
                // so launching from here animates the way it does elsewhere.
                val position = coordinates.positionInWindow()
                bounds = Rect(
                    position.x.roundToInt(),
                    position.y.roundToInt(),
                    (position.x + coordinates.size.width).roundToInt(),
                    (position.y + coordinates.size.height).roundToInt(),
                )
            }
            .pointerInput(app.key) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onLaunch(app, bounds) },
                )
            },
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = app.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize().background(GlassTheme.colors.fill))
        }
    }
}

/** Four, like the iOS dock. A fifth would not fit at this icon size. */
private const val MAX_SLOTS = 4
