package com.glassify.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
                // A gradient rather than flat black. The glass panels in this
                // screen have nothing behind them to blur — an activity window
                // cannot blur its own content — so without some tonal variation
                // underneath, the rim and specular have nothing to catch and the
                // panels read as grey rectangles. This is also the first thing
                // anyone sees of the material.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                0f to Color(0xFF06070C),
                                0.4f to Color(0xFF141A33),
                                0.75f to Color(0xFF2A1B44),
                                1f to Color(0xFF07080E),
                                start = Offset(0f, 0f),
                                end = Offset(900f, 2400f),
                            )
                        )
                ) {
                    ControlPanel()
                }
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
