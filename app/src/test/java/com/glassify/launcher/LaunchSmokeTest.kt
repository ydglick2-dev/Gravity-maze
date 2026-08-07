package com.glassify.launcher

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launches the real activity and renders the real composition.
 *
 * A launcher that throws during composition is relaunched by the system
 * immediately and crash-loops, which is unrecoverable from the device's side —
 * there is no home screen left to press Home on. So the composition getting
 * through a first frame is the single most important thing to check, and with
 * no emulator available this is the only way to check it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `the launcher composes its first frame`() {
        compose.waitForIdle()
    }
}
