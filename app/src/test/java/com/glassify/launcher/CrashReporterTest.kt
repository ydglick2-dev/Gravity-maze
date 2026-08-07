package com.glassify.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The recovery ladder.
 *
 * Worth testing carefully because it is what decides whether a user with a
 * crash-looping home screen can get back to a usable phone. Getting it wrong in
 * either direction is bad: too eager and a healthy launcher loses its glass
 * after an unrelated restart, too reluctant and the loop never breaks.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReporterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashReporter.clear(context)
    }

    @Test
    fun `a first launch runs normally`() {
        assertEquals(CrashReporter.Recovery.NORMAL, CrashReporter.beginRun(context))
    }

    @Test
    fun `a launch after a healthy run also runs normally`() {
        CrashReporter.beginRun(context)
        CrashReporter.markStable(context)

        assertEquals(CrashReporter.Recovery.NORMAL, CrashReporter.beginRun(context))
    }

    @Test
    fun `repeated fast restarts drop the glass, then give up`() {
        // Each call without an intervening markStable is a run that did not
        // survive its startup window.
        assertEquals(CrashReporter.Recovery.NORMAL, CrashReporter.beginRun(context))
        assertEquals(CrashReporter.Recovery.NORMAL, CrashReporter.beginRun(context))
        assertEquals(CrashReporter.Recovery.PLAIN, CrashReporter.beginRun(context))
        assertEquals(CrashReporter.Recovery.PLAIN, CrashReporter.beginRun(context))
        assertEquals(CrashReporter.Recovery.SAFE, CrashReporter.beginRun(context))
    }

    @Test
    fun `surviving the startup window resets the ladder`() {
        CrashReporter.beginRun(context)
        CrashReporter.beginRun(context)
        assertEquals(CrashReporter.Recovery.PLAIN, CrashReporter.beginRun(context))

        // The launcher stayed up, so the next crash starts from scratch rather
        // than from a degraded state it never recovers from.
        CrashReporter.markStable(context)

        assertEquals(CrashReporter.Recovery.NORMAL, CrashReporter.beginRun(context))
    }

    @Test
    fun `clearing resets everything`() {
        repeat(5) { CrashReporter.beginRun(context) }
        CrashReporter.clear(context)

        assertEquals(CrashReporter.Recovery.NORMAL, CrashReporter.beginRun(context))
    }

    @Test
    fun `a corrupt state file does not break startup`() {
        java.io.File(context.filesDir, "startup-state.txt").writeText("not a timestamp")

        assertEquals(CrashReporter.Recovery.NORMAL, CrashReporter.beginRun(context))
    }

    @Test
    fun `the silent failure text is offered when nothing was caught`() {
        // Native crashes leave no Java trace, and the safe screen still has to
        // say something useful rather than render blank.
        assertEquals(null, CrashReporter.lastCrash(context))
        assert(CrashReporter.describeSilentFailure().isNotBlank())
    }
}
