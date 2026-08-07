package com.glassify.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glassify.launcher.data.LauncherPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The home screen off switch.
 *
 * The one property that matters is that it sticks. Three separate things can
 * bring the overlay service back — START_STICKY after a kill, the boot receiver,
 * and the control panel being opened — so "off" has to live in storage rather
 * than in the service's own state, or the user ends up arguing with their phone.
 */
@RunWith(RobolectricTestRunner::class)
class PausedOverlaysTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `pausing survives being read back through a fresh instance`() = runBlocking {
        // A fresh LauncherPrefs stands in for the next process: StopActivity
        // writes the flag, and something else entirely reads it later.
        LauncherPrefs(context).update { it.copy(overlaysPaused = true) }

        assertTrue(LauncherPrefs(context).settings.first().overlaysPaused)
    }

    @Test
    fun `resuming clears it again`() = runBlocking {
        val prefs = LauncherPrefs(context)
        prefs.update { it.copy(overlaysPaused = true) }
        prefs.update { it.copy(overlaysPaused = false) }

        assertFalse(prefs.settings.first().overlaysPaused)
    }

    @Test
    fun `pausing leaves the other settings alone`() = runBlocking {
        // The off switch is a pause, not a reset: everything the user set up has
        // to still be there when they switch it back on.
        val prefs = LauncherPrefs(context)
        prefs.update {
            it.copy(
                dockKeys = listOf("a/a", "b/b"),
                islandTopOffsetDp = 24,
                clockEnabled = true,
            )
        }

        prefs.update { it.copy(overlaysPaused = true) }

        val settings = prefs.settings.first()
        assertTrue(settings.overlaysPaused)
        assertTrue(settings.clockEnabled)
        org.junit.Assert.assertEquals(listOf("a/a", "b/b"), settings.dockKeys)
        org.junit.Assert.assertEquals(24, settings.islandTopOffsetDp)
    }

    @Test
    fun `overlays are not paused by default`() = runBlocking {
        // A fresh install must not start switched off.
        assertFalse(com.glassify.launcher.data.GlassifySettings().overlaysPaused)
    }
}
