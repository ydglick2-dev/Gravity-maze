package com.glassify.launcher.overlay

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether one of our own screens is in front.
 *
 * Needed because home-screen detection is not always available: Android blocks
 * accessibility services for sideloaded apps until the user lifts the
 * restriction, and it can be declined outright. When it is missing the overlays
 * fall back to showing everywhere — but "everywhere" must never include
 * Glassify's own control panel, where a floating clock lands squarely on top of
 * the settings the user came to change.
 *
 * This needs no permission at all: an app is always allowed to know about its
 * own activities.
 */
object ForegroundAppTracker {

    private val _inOurApp = MutableStateFlow(false)
    val inOurApp: StateFlow<Boolean> = _inOurApp.asStateFlow()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var resumed = 0

                override fun onActivityResumed(activity: Activity) {
                    resumed++
                    _inOurApp.value = true
                }

                override fun onActivityPaused(activity: Activity) {
                    resumed = (resumed - 1).coerceAtLeast(0)
                    _inOurApp.value = resumed > 0
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }
}
