package com.glassify.launcher.glass

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws glass panels that float over whatever else is on screen.
 *
 * There is only one way to do this from an overlay window, and it is not the
 * obvious one. Another app's pixels are not ours to read — sampling them would
 * mean holding a screen recording open, with the permanent capture indicator
 * that implies — so the panel cannot bend its background the way real glass
 * does. What it can do is ask the compositor to blur behind its *window*
 * (`OverlayWindows.panel`, via `FLAG_BLUR_BEHIND`) and then paint the glass
 * surface itself on top: rim light, specular, tint, inner shadow.
 *
 * An earlier version captured the backdrop into a GraphicsLayer and ran a
 * blur -> AGSL chain over it, which produced genuine edge refraction. That only
 * ever worked inside our own window, and it is what crash-looped on device. It
 * is gone: over someone else's home screen there is nothing to capture, so the
 * capability bought nothing and cost everything.
 */

/** Tunables for one panel. */
@Immutable
data class GlassSpec(
    /** Depth of the bevel, in dp. Wider reads as thicker glass. */
    val thickness: Dp = 14.dp,
    /** Strength of the tilt-driven highlight. */
    val specular: Float = 0.6f,
    /**
     * Opacity of the flat interior, before the rim adds its own.
     *
     * Low on purpose. Seeing through the panel is the whole point of the
     * material, and every increment here trades that away for nothing — the
     * sense of a surface comes from the rim, not from the fill.
     */
    val surfaceAlpha: Float = 0.16f,
    /** Noise amplitude. Kills the banding a large blur leaves behind. */
    val grain: Float = 0.022f,
    /** Drop shadow under the panel, which is what makes it read as floating. */
    val elevation: Dp = 16.dp,
    val tint: Color = Color.Unspecified,
)

/**
 * Paints this element as a pane of liquid glass.
 *
 * Draws behind the element's own content, so text and icons inside stay crisp.
 * Degrades to a painted gradient when there is no shader — and permanently, for
 * the whole process, if the shader path ever throws while drawing.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = GlassShapes.card,
    spec: GlassSpec = GlassSpec(),
    cornerRadius: Dp = 22.dp,
    tiltLight: State<Offset>? = null,
): Modifier {
    val tier = LocalGlassTier.current
    val colors = GlassTheme.colors
    val light = tiltLight ?: rememberTiltLight(enabled = tier == GlassTier.FULL)
    val tint = if (spec.tint != Color.Unspecified) spec.tint else colors.glassTint

    val shadersAvailable = tier == GlassTier.FULL &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val shader = remember(shadersAvailable) {
        // AGSL is compiled by the driver, so a program that is valid everywhere
        // it can be tested may still be rejected by a particular GPU.
        if (shadersAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(LiquidGlassShaderPrograms.SURFACE_ONLY) }
                .onFailure { Log.e(TAG, "The glass shader was rejected; falling back", it) }
                .getOrNull()
        } else {
            null
        }
    }

    val painted = shader == null || GlassRenderer.failed

    return this
        // Shadow first, so it falls outside the shape rather than being clipped
        // by it. Glass that sits flat against its background stops reading as a
        // separate object however good the surface is.
        .shadow(
            elevation = spec.elevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black,
            spotColor = Color.Black,
        )
        .clip(shape)
        .then(if (painted) Modifier.paintedGlass(tint, spec) else Modifier)
        .drawBehind {
            if (painted) return@drawBehind

            // A throw inside a draw pass repeats on every frame, so it has to
            // degrade rather than propagate.
            try {
                drawSurfaceGlass(
                    shader = shader,
                    cornerPx = cornerRadius.toPx(),
                    thicknessPx = spec.thickness.toPx(),
                    specular = spec.specular,
                    surfaceAlpha = spec.surfaceAlpha,
                    grain = spec.grain,
                    tint = tint,
                    light = light.value,
                )
            } catch (e: Throwable) {
                GlassRenderer.disable(e)
            }
        }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawSurfaceGlass(
    shader: RuntimeShader,
    cornerPx: Float,
    thicknessPx: Float,
    specular: Float,
    surfaceAlpha: Float,
    grain: Float,
    tint: Color,
    light: Offset,
) {
    if (size.width <= 0f || size.height <= 0f) return

    shader.setFloatUniform("uSize", size.width, size.height)
    shader.setFloatUniform("uCorner", cornerPx)
    shader.setFloatUniform("uThickness", thicknessPx)
    shader.setFloatUniform("uSpecular", specular)
    shader.setFloatUniform("uBaseAlpha", surfaceAlpha)
    shader.setFloatUniform("uGrain", grain)
    shader.setFloatUniform("uLight", light.x, light.y)
    shader.setColorUniform("uTintColor", tint.toArgb())

    drawRect(brush = ShaderBrush(shader))
}

/**
 * The fallback sheet for tiers with no shader.
 *
 * Carries more tint than the shader path, and deliberately so: with no rim, no
 * specular and no Fresnel to define the surface, opacity is the only thing left
 * that says something is there.
 */
private fun Modifier.paintedGlass(tint: Color, spec: GlassSpec): Modifier = background(
    Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = 0.16f),
            tint.copy(alpha = (spec.surfaceAlpha + 0.35f).coerceAtMost(0.6f)),
        )
    )
)

/**
 * One-way switch that turns the shader path off for the rest of the process once
 * it has thrown.
 *
 * Global rather than per-panel: whatever breaks one pane breaks them all, and
 * retrying on each would only multiply the failure across the frame. Backed by
 * Compose state so flipping it invalidates the compositions that chose their
 * style from it.
 */
internal object GlassRenderer {

    private val failedState = mutableStateOf(false)

    val failed: Boolean get() = failedState.value

    fun disable(cause: Throwable) {
        if (failedState.value) return
        failedState.value = true
        Log.e(TAG, "Glass rendering failed; falling back to the painted style", cause)
    }
}

private const val TAG = "LiquidGlass"
