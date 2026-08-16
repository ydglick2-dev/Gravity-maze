package com.glassify.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.glassify.launcher.glass.GlassSwitch
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.thumbPadding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the 1.6 crash.
 *
 * The switch thumb animates on a bouncy spring, and a bouncy spring returning
 * to zero undershoots below it. That negative value was fed straight into
 * `padding`, which throws on negatives — during recomposition, which takes the
 * whole activity down.
 *
 * The crash lived on the animation's *intermediate* frames, and sampling those
 * from a test is timing-dependent — a frame-stepping run was tried and failed
 * to reach the trough even against the broken code. So the guarantee is tested
 * where it is deterministic: the clamp function every frame goes through.
 * The animation test remains as a broad smoke over the real composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `toggling off survives the spring's undershoot below zero`() {
        var checked by mutableStateOf(true)

        compose.mainClock.autoAdvance = false
        compose.setContent {
            GlassTheme(dark = true) {
                GlassSwitch(checked = checked, onChange = { checked = it })
            }
        }
        compose.mainClock.advanceTimeBy(600)

        // Off is the direction that undershoots: the spring dips below zero as
        // it settles back at the start position.
        checked = false

        // Step in frame-sized increments so intermediate spring values are
        // composed at all. (Frame timing cannot be trusted to land in the
        // trough — that is why the clamp has its own direct test below.)
        repeat(120) { compose.mainClock.advanceTimeBy(16) }

        // And back on, for the overshoot past the far end.
        checked = true
        repeat(120) { compose.mainClock.advanceTimeBy(16) }
    }

    @Test
    fun `the padding clamp holds for any spring value`() {
        // The spring's undershoot for a 0.5-damped return from full travel is
        // ~16%, i.e. about -3.3dp — just past the -3dp of track padding, which
        // is why the crash was intermittent on device. Check far beyond it.
        assertEquals(0.dp, thumbPadding((-3).dp))
        assertEquals(0.dp, thumbPadding((-50).dp))
        assertTrue(thumbPadding((-0.5).dp) >= 0.dp)

        // The clamp must not distort legitimate values.
        assertEquals(3.dp, thumbPadding(0.dp))
        assertEquals(23.dp, thumbPadding(20.dp))
    }
}
