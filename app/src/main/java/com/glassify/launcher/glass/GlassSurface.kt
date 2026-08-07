package com.glassify.launcher.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Everything a glass panel needs to know about what is behind it.
 *
 * A single layer is recorded once per frame for the whole screen and every panel
 * samples out of it, rather than each panel capturing its own copy. That is the
 * difference between one backdrop render and a dozen.
 */
@Immutable
class GlassBackdrop internal constructor(
    internal val layer: GraphicsLayer?,
    internal val rootPosition: () -> Offset,
)

private val NoBackdrop = GlassBackdrop(layer = null, rootPosition = { Offset.Zero })

val LocalGlassBackdrop = staticCompositionLocalOf { NoBackdrop }

/**
 * Records its content into a layer that [glass] panels can refract.
 *
 * Put the wallpaper — and anything else that should show through the glass —
 * inside [background]. Panels go in [content]. Anything drawn in `content` is
 * *not* part of the backdrop, which is what stops a panel from sampling itself
 * into an infinite regress.
 */
@Composable
fun GlassBackdropScope(
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val tier = LocalGlassTier.current
    val layer = rememberGraphicsLayer()
    var rootOffset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .onGloballyPositioned { rootOffset = it.positionInRoot() }
                .drawWithContent {
                    if (tier.blursBackdrop) {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    } else {
                        drawContent()
                    }
                },
            content = background,
        )

        androidx.compose.runtime.CompositionLocalProvider(
            LocalGlassBackdrop provides remember(layer) {
                GlassBackdrop(layer = layer, rootPosition = { rootOffset })
            },
        ) {
            Box(Modifier.matchParentSize(), content = content)
        }
    }
}

/** Tunables for one panel. Defaults are the values used by the dock and cards. */
@Immutable
data class GlassSpec(
    /** How far the backdrop is blurred, in dp. */
    val blur: Dp = 26.dp,
    /** Depth of the bevel, in dp. Wider reads as thicker glass. */
    val thickness: Dp = 12.dp,
    /** Peak inward pull at the rim, in dp. 0 disables refraction. */
    val refraction: Dp = 9.dp,
    /** Per-channel spread of the refraction, as a fraction. 0 disables fringing. */
    val aberration: Float = 0.16f,
    /** Strength of the tilt-driven highlight. */
    val specular: Float = 0.5f,
    /** How much of [tint] is mixed into the sampled backdrop. */
    val tintAmount: Float = 0.18f,
    val tint: Color = Color.Unspecified,
    /** Overall opacity when there is no backdrop to sample (overlay windows). */
    val surfaceAlpha: Float = 0.72f,
)

/**
 * Paints this element as a pane of liquid glass.
 *
 * Draws behind the element's own content, so text and icons placed inside stay
 * crisp — only the backdrop is bent.
 *
 * Falls back cleanly: without a RuntimeShader the backdrop is still blurred and
 * the rim is drawn with gradients; without a backdrop at all it becomes a tinted
 * translucent sheet. The layout never changes between tiers.
 */
@Composable
fun Modifier.liquidGlass(
    shape: androidx.compose.ui.graphics.Shape = GlassShapes.card,
    spec: GlassSpec = GlassSpec(),
    cornerRadius: Dp = 22.dp,
    tiltLight: State<Offset>? = null,
): Modifier {
    val tier = LocalGlassTier.current
    val backdrop = LocalGlassBackdrop.current
    val colors = GlassTheme.colors
    val density = LocalDensity.current
    val light = tiltLight ?: rememberTiltLight(enabled = tier == GlassTier.FULL)
    val glassLayer = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }

    val tint = if (spec.tint.isSpecified) spec.tint else colors.glassTint
    val shadersAvailable = tier == GlassTier.FULL &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val hasBackdrop = backdrop.layer != null

    // Two programs, chosen by whether there are real pixels behind us to bend.
    // Inside the launcher there are; in an overlay window over another app there
    // are not, and the system's blur-behind stands in for them.
    val shader = remember(shadersAvailable, hasBackdrop) {
        // AGSL is compiled by the driver, so a program that is valid everywhere
        // we can test it could still be rejected by some particular GPU. This is
        // a home screen the user cannot press Home to escape, so a rejected
        // shader has to degrade to the painted tier rather than throw.
        // The version check is repeated here rather than only inside
        // `shadersAvailable` so it is the guard lint can actually see.
        if (shadersAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { compileGlassShader(hasBackdrop) }
                .onFailure { Log.e(TAG, "The glass shader was rejected; falling back", it) }
                .getOrNull()
        } else {
            null
        }
    }

    return this
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawBehind {
            // Draw-time failures are uniquely dangerous here. A throw inside a
            // draw pass repeats on every frame, and on a home screen the system
            // relaunches after each one — the app crash-loops about once a
            // second with no way in. Layer recording, RenderEffect chaining and
            // shader execution all depend on GPU and platform behaviour we
            // cannot exercise off-device, so the whole path degrades to the
            // painted rim instead of taking the launcher down.
            if (GlassRenderer.failed) {
                return@drawBehind
            }

            val local = position - backdrop.rootPosition()
            val blurPx = with(density) { spec.blur.toPx() }
            val cornerPx = with(density) { cornerRadius.toPx() }
            val thicknessPx = with(density) { spec.thickness.toPx() }

            try {
            when {
                shader != null && backdrop.layer != null ->
                    drawRefractedGlass(
                        shader = shader,
                        glassLayer = glassLayer,
                        backdrop = backdrop.layer,
                        origin = local,
                        blurPx = blurPx,
                        cornerPx = cornerPx,
                        thicknessPx = thicknessPx,
                        refractionPx = with(density) { spec.refraction.toPx() },
                        aberration = spec.aberration,
                        specular = spec.specular,
                        tintAmount = spec.tintAmount,
                        tint = tint,
                        light = light.value,
                    )

                shader != null ->
                    drawSurfaceGlass(
                        shader = shader,
                        cornerPx = cornerPx,
                        thicknessPx = thicknessPx,
                        specular = spec.specular,
                        surfaceAlpha = spec.surfaceAlpha,
                        tint = tint,
                        light = light.value,
                    )

                backdrop.layer != null && tier.blursBackdrop ->
                    drawBlurredGlass(
                        glassLayer = glassLayer,
                        backdrop = backdrop.layer,
                        origin = local,
                        blurPx = blurPx,
                        tint = tint,
                        tintAmount = spec.tintAmount,
                    )
            }
            } catch (e: Throwable) {
                GlassRenderer.disable(e)
            }
        }
        // The painted rim is the fallback for the tiers without a shader, and
        // for every tier once the renderer has given up.
        .drawGlassRim(shape, painted = shader == null || GlassRenderer.failed, tint, spec)
        .clip(shape)
}

/**
 * One-way switch that turns the hardware glass path off for the rest of the
 * process once it has thrown.
 *
 * Deliberately global rather than per-panel: whatever breaks the dock will break
 * every other pane too, and retrying it on each of them would just multiply the
 * failure across the frame.
 */
internal object GlassRenderer {

    // Compose state rather than a plain flag: the fallback is chosen during
    // composition, so flipping this has to invalidate it. A plain boolean would
    // leave every panel drawing nothing at all until something else happened to
    // recompose.
    private val failedState = mutableStateOf(false)

    val failed: Boolean get() = failedState.value

    fun disable(cause: Throwable) {
        if (failedState.value) return
        failedState.value = true
        Log.e(TAG, "Glass rendering failed; falling back to the painted style", cause)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun compileGlassShader(hasBackdrop: Boolean) = RuntimeShader(
    if (hasBackdrop) {
        LiquidGlassShaderPrograms.REFRACTING
    } else {
        LiquidGlassShaderPrograms.SURFACE_ONLY
    }
)

private const val TAG = "LiquidGlass"

/**
 * The full effect. The panel's slice of the backdrop is re-recorded into its own
 * layer so the blur and the shader apply to that slice alone.
 *
 * The slice is padded on all sides: a blur samples beyond its input's bounds and
 * would otherwise fade to transparent at the panel's edge — the one place where
 * the glass most needs real pixels to bend.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawRefractedGlass(
    shader: RuntimeShader,
    glassLayer: GraphicsLayer,
    backdrop: GraphicsLayer,
    origin: Offset,
    blurPx: Float,
    cornerPx: Float,
    thicknessPx: Float,
    refractionPx: Float,
    aberration: Float,
    specular: Float,
    tintAmount: Float,
    tint: Color,
    light: Offset,
) {
    if (size.width <= 0f || size.height <= 0f) return

    val pad = (blurPx * 1.5f + refractionPx).roundToInt().coerceAtLeast(1)
    val padded = IntSize(
        (size.width.roundToInt() + pad * 2).coerceAtLeast(1),
        (size.height.roundToInt() + pad * 2).coerceAtLeast(1),
    )

    shader.setFloatUniform("uSize", size.width, size.height)
    shader.setFloatUniform("uPad", pad.toFloat(), pad.toFloat())
    shader.setFloatUniform("uCorner", cornerPx)
    shader.setFloatUniform("uThickness", thicknessPx)
    shader.setFloatUniform("uRefraction", refractionPx)
    shader.setFloatUniform("uAberration", aberration)
    shader.setFloatUniform("uSpecular", specular)
    shader.setFloatUniform("uTintAmount", tintAmount)
    shader.setFloatUniform("uLight", light.x, light.y)
    shader.setColorUniform("uTintColor", tint.toArgb())

    // Blur first, then bend the blurred result: bending sharp pixels and then
    // blurring them would smear the refraction away.
    val blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
    glassLayer.renderEffect = RenderEffect
        .createChainEffect(
            RenderEffect.createRuntimeShaderEffect(shader, "content"),
            blur,
        )
        .asComposeRenderEffect()

    glassLayer.record(padded) {
        translate(-origin.x + pad, -origin.y + pad) {
            drawLayer(backdrop)
        }
    }

    translate(-pad.toFloat(), -pad.toFloat()) {
        drawLayer(glassLayer)
    }
}

/**
 * Overlay windows: the glass surface with no backdrop to bend.
 *
 * Painted straight into the draw scope with the shader as a brush, since there
 * is no input layer for a `RenderEffect` to filter. What sits behind is blurred
 * by the compositor instead — see `OverlayWindows.panel`.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawSurfaceGlass(
    shader: RuntimeShader,
    cornerPx: Float,
    thicknessPx: Float,
    specular: Float,
    surfaceAlpha: Float,
    tint: Color,
    light: Offset,
) {
    if (size.width <= 0f || size.height <= 0f) return

    shader.setFloatUniform("uSize", size.width, size.height)
    shader.setFloatUniform("uPad", 0f, 0f)
    shader.setFloatUniform("uCorner", cornerPx)
    shader.setFloatUniform("uThickness", thicknessPx)
    shader.setFloatUniform("uSpecular", specular)
    shader.setFloatUniform("uBaseAlpha", surfaceAlpha)
    shader.setFloatUniform("uTintAmount", 1f)
    shader.setFloatUniform("uLight", light.x, light.y)
    shader.setColorUniform("uTintColor", tint.toArgb())

    drawRect(brush = ShaderBrush(shader))
}

/** API 31-32, or a user downgrade: blurred backdrop, no bending. */
private fun DrawScope.drawBlurredGlass(
    glassLayer: GraphicsLayer,
    backdrop: GraphicsLayer,
    origin: Offset,
    blurPx: Float,
    tint: Color,
    tintAmount: Float,
) {
    if (size.width <= 0f || size.height <= 0f) return

    val pad = (blurPx * 1.5f).roundToInt().coerceAtLeast(1)
    val padded = IntSize(
        (size.width.roundToInt() + pad * 2).coerceAtLeast(1),
        (size.height.roundToInt() + pad * 2).coerceAtLeast(1),
    )

    glassLayer.renderEffect = RenderEffect
        .createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        .asComposeRenderEffect()

    glassLayer.record(padded) {
        translate(-origin.x + pad, -origin.y + pad) {
            drawLayer(backdrop)
        }
    }

    translate(-pad.toFloat(), -pad.toFloat()) {
        drawLayer(glassLayer)
    }
    drawRect(tint.copy(alpha = tintAmount))
}

/**
 * The painted part of the material: a fill when there is nothing to sample, plus
 * a light top edge and a dark bottom edge. Those two hairlines do a
 * disproportionate amount of the work — they are what stops a translucent
 * rectangle from looking like a translucent rectangle.
 */
private fun Modifier.drawGlassRim(
    shape: androidx.compose.ui.graphics.Shape,
    painted: Boolean,
    tint: Color,
    spec: GlassSpec,
): Modifier {
    if (!painted) return this
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    tint.copy(alpha = spec.surfaceAlpha),
                    tint.copy(alpha = spec.surfaceAlpha * 0.88f),
                )
            )
        )
        .drawBehind {
            val stroke = 1.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.30f),
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.10f),
                ),
                size = Size(size.width, stroke),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.10f)),
                ),
                topLeft = Offset(0f, size.height - stroke),
                size = Size(size.width, stroke),
            )
        }
}

private val Color.isSpecified: Boolean get() = this != Color.Unspecified
