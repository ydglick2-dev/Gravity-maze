package com.glassify.launcher

import androidx.compose.ui.geometry.Size
import com.glassify.launcher.glass.SQUIRCLE_EXPONENT
import com.glassify.launcher.glass.squircleCornerOffset
import com.glassify.launcher.glass.squirclePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The squircle is easy to get subtly wrong — a corner traced backwards still
 * closes into a plausible-looking path, and the defect only surfaces as pinched
 * icons on a device. These assertions pin the geometry instead.
 */
@RunWith(RobolectricTestRunner::class)
class SquircleTest {

    @Test
    fun `the corner sweep starts and ends on the straight edges`() {
        val (startX, startY) = squircleCornerOffset(radius = 40f, progress = 0f)
        val (endX, endY) = squircleCornerOffset(radius = 40f, progress = 1f)

        assertEquals(40f, startX, TOLERANCE)
        assertEquals(0f, startY, TOLERANCE)
        assertEquals(0f, endX, TOLERANCE)
        assertEquals(40f, endY, TOLERANCE)
    }

    @Test
    fun `exponent 2 reproduces a circular arc exactly`() {
        val radius = 50f
        val (x, y) = squircleCornerOffset(radius, progress = 0.5f, exponent = 2f)

        // Halfway round a circle's quarter is 45 degrees, where both components
        // are r/sqrt(2).
        assertEquals(radius / sqrt(2f), x, TOLERANCE)
        assertEquals(radius / sqrt(2f), y, TOLERANCE)
        assertEquals(radius, hypot(x, y), TOLERANCE)
    }

    @Test
    fun `the squircle corner is fuller than the circular arc it replaces`() {
        // This is the entire point of the shape. At 45 degrees the superellipse
        // reaches further into the corner than a circle does, which is what
        // removes the curvature break where the arc meets the straight edge.
        val radius = 50f
        val (squircleX, squircleY) = squircleCornerOffset(radius, 0.5f, SQUIRCLE_EXPONENT)
        val (circleX, circleY) = squircleCornerOffset(radius, 0.5f, exponent = 2f)

        // Distance from the rectangle's actual corner, which sits at (r, r)
        // relative to the corner centre.
        val squircleGap = hypot(radius - squircleX, radius - squircleY)
        val circleGap = hypot(radius - circleX, radius - circleY)

        assertTrue(
            "squircle gap $squircleGap should be smaller than circle gap $circleGap",
            squircleGap < circleGap,
        )
    }

    @Test
    fun `a higher exponent is monotonically squarer`() {
        val radius = 50f
        val gaps = listOf(2f, 3f, 5f, 8f).map { exponent ->
            val (x, y) = squircleCornerOffset(radius, 0.5f, exponent)
            hypot(radius - x, radius - y)
        }

        assertEquals(gaps.sortedDescending(), gaps)
    }

    @Test
    fun `the path spans exactly the requested bounds`() {
        val path = squirclePath(Size(200f, 120f), cornerRadius = 30f)
        val bounds = path.getBounds()

        assertEquals(0f, bounds.left, TOLERANCE)
        assertEquals(0f, bounds.top, TOLERANCE)
        assertEquals(200f, bounds.right, TOLERANCE)
        assertEquals(120f, bounds.bottom, TOLERANCE)
    }

    @Test
    fun `a zero radius degenerates into the full rectangle`() {
        val bounds = squirclePath(Size(100f, 100f), cornerRadius = 0f).getBounds()

        assertEquals(100f, bounds.width, TOLERANCE)
        assertEquals(100f, bounds.height, TOLERANCE)
    }

    @Test
    fun `an oversized radius is clamped rather than inverting the shape`() {
        // Half the shorter side is the largest meaningful radius; asking for ten
        // times that must not fold the path in on itself.
        val bounds = squirclePath(Size(80f, 80f), cornerRadius = 400f).getBounds()

        assertEquals(0f, bounds.left, TOLERANCE)
        assertEquals(80f, bounds.right, TOLERANCE)
        assertEquals(80f, bounds.bottom, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
