package com.glassify.launcher

import android.app.Application

/**
 * Installs the crash recorder before anything else can fail.
 *
 * It has to be here rather than in the activity: a crash in the overlay service,
 * the notification listener or the boot receiver never reaches an activity, and
 * those run without one.
 */
class GlassifyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
