package com.glassify.launcher

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.glassify.launcher.data.LauncherPrefs
import com.glassify.launcher.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The off switch, as an icon that lives on the home screen.
 *
 * Glassify's whole output is windows floating over other apps, which makes
 * "turn it off" a genuinely awkward thing to reach: by the time the overlays are
 * in the way, opening a settings screen to dismiss them is the long way round.
 * So it is a separate launcher icon — one tap, from the home screen, with no
 * interface of its own.
 *
 * It shows nothing and finishes immediately. The paused flag it writes is
 * persistent, so the overlays stay down across reboots and app updates until the
 * user turns them back on from the control panel. A tap here that could be
 * undone by the next restart would not be an off switch.
 */
class StopActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Stop the windows first, so they disappear as the icon is tapped rather
        // than after a round trip through disk.
        stopService(android.content.Intent(this, OverlayService::class.java))

        // Deliberately not the activity's own scope: the activity is about to
        // finish, and the write has to complete regardless.
        val appContext = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            LauncherPrefs(appContext).update { it.copy(overlaysPaused = true) }
        }

        Toast.makeText(this, R.string.stop_confirmation, Toast.LENGTH_SHORT).show()
        finish()
    }
}
