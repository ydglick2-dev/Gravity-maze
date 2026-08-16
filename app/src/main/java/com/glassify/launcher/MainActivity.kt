package com.glassify.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.GlassTier
import com.glassify.launcher.settings.ControlPanel

/**
 * The app's own screen.
 *
 * Glassify is no longer a launcher — it does not replace the home screen, it
 * floats a glass layer over whichever one the phone already has. So this is an
 * ordinary activity: permissions, what to show, and where. All the visible work
 * happens in the overlay service, which the control panel starts and stops from
 * the settings themselves rather than from this activity's lifecycle.
 */
class MainActivity : ComponentActivity() {

    /** Set when the previous run crashed on startup; suppresses all normal work. */
    private var safeMode = false

    private val stableHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kept from the launcher version, where a startup crash became an
        // inescapable relaunch loop. It matters less now that the home screen
        // belongs to someone else, but a repeatedly crashing app still needs to
        // be able to say why on a device with no adb attached.
        val recovery = CrashReporter.beginRun(this)
        if (recovery == CrashReporter.Recovery.SAFE) {
            safeMode = true
            SafeModeView.show(
                this,
                CrashReporter.lastCrash(this) ?: CrashReporter.describeSilentFailure(),
            )
            return
        }
        stableHandler.postDelayed({ CrashReporter.markStable(this) }, STABLE_AFTER_MS)

        enableEdgeToEdge()

        setContent {
            GlassTheme(dark = true, tier = tierFor(recovery)) {
                // The reference design's backdrop, verbatim: a dark navy
                // radial falling to near-black, with three soft colour glows
                // drifting behind the glass. An activity cannot blur its own
                // content, so this backdrop is also what gives the panes'
                // bevel and specular something to exist against.
                DesignBackdrop { ControlPanel() }
            }
        }
    }

    override fun onDestroy() {
        stableHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** After repeated fast restarts the glass is dropped; a plain screen beats a loop. */
    private fun tierFor(recovery: CrashReporter.Recovery): GlassTier =
        if (recovery == CrashReporter.Recovery.PLAIN) {
            GlassTier.FLAT
        } else {
            GlassTier.deviceMax(this)
        }
}

/** How long a launch has to survive before it counts as healthy. */
private const val STABLE_AFTER_MS = 12_000L

/**
 * The reference design's background: `radial-gradient(120% 90% at 50% 0%,
 * #0d1424, #070a12)` plus three large blurred colour orbs (#ff5f6d, #22d3ee,
 * #a78bfa). The orbs are wide radial gradients fading to transparent — at this
 * softness a real blur pass would be indistinguishable and cost a filter.
 */
@Composable
private fun DesignBackdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF070A12))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF0D1424), Color(0xFF070A12)),
                        center = Offset(540f, 0f),
                        radius = 1900f,
                    )
                )
        )
        Orb(Color(0xFFFF5F6D), alpha = 0.30f, center = Offset(760f, 480f), radius = 620f)
        Orb(Color(0xFF22D3EE), alpha = 0.26f, center = Offset(150f, 1150f), radius = 660f)
        Orb(Color(0xFFA78BFA), alpha = 0.24f, center = Offset(520f, 2050f), radius = 720f)
        content()
    }
}

@Composable
private fun Orb(color: Color, alpha: Float, center: Offset, radius: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = radius,
                )
            )
    )
}
