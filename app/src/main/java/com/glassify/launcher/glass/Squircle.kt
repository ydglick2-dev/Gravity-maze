package com.glassify.launcher.glass

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The continuous-curvature rounded rectangle iOS uses everywhere — app icons,
 * cards, the dock, the notification stack.
 *
 * It matters more than it sounds. A plain rounded rectangle changes curvature
 * abruptly where the straight edge meets the arc, and against a grid of icons
 * that discontinuity is what makes an Android home screen read as Android even
 * after the icons have been recoloured. A superellipse corner has no such break.
 *
 * Each corner is a quarter superellipse `|x|^n + |y|^n = 1` sampled into the
 * path. At [SQUIRCLE_EXPONENT] `= 2` this degenerates into an ordinary circular
 * arc, which makes the exponent a straightforward dial rather than a magic
 * constant.
 */
class SquircleShape(
    private val radius: Dp,
    private val exponent: Float = SQUIRCLE_EXPONENT,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(
        squirclePath(size, with(density) { radius.toPx() }, exponent)
    )

    override fun equals(other: Any?): Boolean =
        other is SquircleShape && other.radius == radius && other.exponent == exponent

    override fun hashCode(): Int = radius.hashCode() * 31 + exponent.hashCode()
}

/** A squircle whose corner radius is a fraction of the shorter side. Icons use 0.5. */
class SquircleFractionShape(
    private val fraction: Float,
    private val exponent: Float = SQUIRCLE_EXPONENT,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(
        squirclePath(size, min(size.width, size.height) * fraction, exponent)
    )

    override fun equals(other: Any?): Boolean =
        other is SquircleFractionShape && other.fraction == fraction && other.exponent == exponent

    override fun hashCode(): Int = fraction.hashCode() * 31 + exponent.hashCode()
}

/**
 * Builds the outline. Corners run clockwise from the top-left; the straight
 * edges fall out of the sampling because consecutive corners are joined with
 * [Path.lineTo].
 */
fun squirclePath(
    size: Size,
    cornerRadius: Float,
    exponent: Float = SQUIRCLE_EXPONENT,
): Path {
    val w = size.width
    val h = size.height
    val r = cornerRadius.coerceIn(0f, min(w, h) / 2f)
    val path = Path()

    if (r <= 0f) {
        path.moveTo(0f, 0f)
        path.lineTo(w, 0f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        return path
    }

    fun cornerPoint(step: Int) = squircleCornerOffset(
        radius = r,
        progress = step.toFloat() / CORNER_STEPS,
        exponent = exponent,
    )

    // Each corner is traced clockwise from the edge it leaves to the edge it
    // rejoins. `x` runs r -> 0 and `y` runs 0 -> r over the sweep, so the pair
    // is mapped onto the corner's own two outward axes.

    // Top-left: down the left edge, round to the top edge.
    path.moveTo(0f, r)
    for (i in 0..CORNER_STEPS) {
        val (x, y) = cornerPoint(i)
        path.lineTo(r - x, r - y)
    }

    // Top-right.
    path.lineTo(w - r, 0f)
    for (i in 0..CORNER_STEPS) {
        val (x, y) = cornerPoint(i)
        path.lineTo(w - r + y, r - x)
    }

    // Bottom-right.
    path.lineTo(w, h - r)
    for (i in 0..CORNER_STEPS) {
        val (x, y) = cornerPoint(i)
        path.lineTo(w - r + x, h - r + y)
    }

    // Bottom-left.
    path.lineTo(r, h)
    for (i in 0..CORNER_STEPS) {
        val (x, y) = cornerPoint(i)
        path.lineTo(r - y, h - r + x)
    }

    path.close()
    return path
}

/**
 * One point on a quarter superellipse, as an offset from the corner's centre.
 *
 * [progress] runs 0..1 across the sweep. At 0 the returned pair is `(r, 0)` and
 * at 1 it is `(0, r)`, so the two components can be mapped onto whichever pair
 * of outward axes a given corner uses.
 */
fun squircleCornerOffset(
    radius: Float,
    progress: Float,
    exponent: Float = SQUIRCLE_EXPONENT,
): Pair<Float, Float> {
    val angle = progress.coerceIn(0f, 1f) * (PI / 2.0)
    val power = (2f / exponent).toDouble()
    return radius * cos(angle).pow(power).toFloat() to
        radius * sin(angle).pow(power).toFloat()
}

/**
 * 2 is a circle; higher is squarer with a longer, flatter corner transition.
 * 5 is the value that matches Apple's icon grid closely enough that mixed rows
 * of shaped and unshaped icons stop being distinguishable.
 */
const val SQUIRCLE_EXPONENT = 5f

private const val CORNER_STEPS = 20
