package com.glassify.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Recovers from startup crashes, and records why they happened.
 *
 * A launcher is uniquely bad at failing. When any other app crashes the user is
 * dropped back to the home screen; when the *home screen* crashes the system
 * relaunches it immediately, so a fault during startup becomes a loop that
 * restarts about once a second and leaves no way to reach Settings — and on a
 * sideloaded build with no adb attached, no way to find out why either.
 *
 * Recovery is graduated rather than all-or-nothing, because the most likely
 * culprit is also the most optional part of the app. Repeated fast restarts
 * first drop the hardware glass, which is the code most dependent on GPU
 * behaviour that cannot be exercised off-device; only if that still fails does
 * the app give up and show the trace.
 *
 * Counting restarts rather than only catching exceptions is deliberate: a
 * driver-level crash in the shader path would kill the process natively, where
 * no Kotlin `catch` and no uncaught-exception handler ever runs. The restart
 * count sees it anyway.
 */
object CrashReporter {

    /** How far the app has been degraded to get past a crash. */
    enum class Recovery {
        /** Nothing wrong; run normally. */
        NORMAL,

        /** Restarted too fast, too often. Run with the glass effects off. */
        PLAIN,

        /** Still failing with the glass off. Show the trace and stop. */
        SAFE,
    }

    private const val TAG = "Glassify"
    private const val CRASH_FILE = "last-crash.txt"
    private const val STATE_FILE = "startup-state.txt"

    /**
     * A run shorter than this counts as a startup failure. Long enough to cover
     * a slow cold start on a busy device, short enough that a crash the user hit
     * after using the launcher for a while does not degrade the next launch.
     */
    private const val STARTUP_WINDOW_MS = 12_000L

    private const val DEGRADE_AFTER = 2
    private const val GIVE_UP_AFTER = 4

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Call once at the start of each launch. Returns how far to degrade.
     *
     * Reading and writing the counter here — before anything else can fail — is
     * what makes it survive a process death that no handler sees.
     */
    fun beginRun(context: Context): Recovery {
        val state = readState(context)
        val consecutive = if (state.startedAt > 0 &&
            System.currentTimeMillis() - state.startedAt < STARTUP_WINDOW_MS
        ) {
            // The previous run began less than a startup window ago and we are
            // starting again, so it did not survive.
            state.consecutiveFailures + 1
        } else {
            0
        }

        writeState(context, State(startedAt = System.currentTimeMillis(), consecutiveFailures = consecutive))

        return when {
            consecutive >= GIVE_UP_AFTER -> Recovery.SAFE
            consecutive >= DEGRADE_AFTER -> Recovery.PLAIN
            else -> Recovery.NORMAL
        }
    }

    /**
     * Call once the app has been up long enough to be considered healthy, which
     * clears the counter so a later crash starts from zero.
     */
    fun markStable(context: Context) {
        writeState(context, State(startedAt = 0L, consecutiveFailures = 0))
    }

    fun lastCrash(context: Context): String? =
        File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText()

    /** Resets everything so the next launch runs normally again. */
    fun clear(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
        File(context.filesDir, STATE_FILE).delete()
    }

    /** What to show on the safe screen when nothing was caught in Kotlin. */
    fun describeSilentFailure(): String = """
        No Java exception was recorded.

        The process died without an uncaught exception, which usually means a
        native crash — most often in the graphics driver while rendering the
        glass effects — or that the system killed the process.

        Starting with the glass turned off did not help either.
    """.trimIndent()

    private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("Glassify ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                "Android ${android.os.Build.VERSION.SDK_INT} · " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )
            appendLine("thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        Log.e(TAG, "Uncaught exception", error)
        File(context.filesDir, CRASH_FILE).writeText(report)
    }

    private data class State(val startedAt: Long, val consecutiveFailures: Int)

    private fun readState(context: Context): State {
        val file = File(context.filesDir, STATE_FILE)
        if (!file.exists()) return State(0L, 0)
        return runCatching {
            val parts = file.readText().split(':')
            State(parts[0].toLong(), parts[1].toInt())
        }.getOrElse { State(0L, 0) }
    }

    private fun writeState(context: Context, state: State) {
        runCatching {
            File(context.filesDir, STATE_FILE)
                .writeText("${state.startedAt}:${state.consecutiveFailures}")
        }
    }
}
