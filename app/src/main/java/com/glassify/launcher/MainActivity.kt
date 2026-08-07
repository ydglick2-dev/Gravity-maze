package com.glassify.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.GlassTier
import com.glassify.launcher.overlay.OverlayService
import com.glassify.launcher.settings.ControlPanel

/**
 * The app's own screen.
 *
 * Glassify is no longer a launcher — it does not replace the home screen, it
 * floats a glass layer over whichever one the phone already has. So this is an
 * ordinary activity: permissions, what to show, and where. All the visible work
 * happens in [OverlayService].
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
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B0B0F))
                ) {
                    ControlPanel()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (safeMode) return
        OverlayService.syncWithPermissions(this)
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
