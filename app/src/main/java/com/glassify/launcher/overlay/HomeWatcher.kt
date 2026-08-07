package com.glassify.launcher.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reports whether the home screen is the thing currently in front.
 *
 * The dock and the clock are meant to sit on the home screen and nowhere else,
 * and an overlay window has no idea what is underneath it. Android exposes the
 * foreground app to exactly one kind of component — an accessibility service —
 * so that is what this is. It reads nothing but the package name of whatever
 * window just came forward, and holds no other capability: the service declares
 * `typeWindowStateChanged` only, with no content retrieval, so it cannot see
 * text, fields or anything a user types.
 *
 * If the user declines the permission the overlays still work; they simply
 * cannot tell the home screen from anything else, and the settings screen offers
 * showing them everywhere instead.
 */
class HomeWatcher : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        state.connected.value = true
        state.refreshHomePackages(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        // Our own overlay windows report our package. Treating that as "left the
        // home screen" would make the dock tear itself down the moment it was
        // touched.
        if (packageName == packageName()) return

        state.onForeground(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        state.connected.value = false
        super.onDestroy()
    }

    private fun packageName() = applicationContext.packageName

    companion object {
        private val state = HomeWatcherState()

        /** True while the launcher is the foreground app. */
        val onHomeScreen: StateFlow<Boolean> get() = state.onHome.asStateFlow()

        val connected: StateFlow<Boolean> get() = state.connected.asStateFlow()

        /** Whether the user has switched the service on in system settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val component = ComponentName(context, HomeWatcher::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == component
            }
        }
    }
}

private class HomeWatcherState {
    val onHome = MutableStateFlow(true)
    val connected = MutableStateFlow(false)

    /**
     * Every package that can act as a home app.
     *
     * A set rather than a single package because a phone routinely has several —
     * the vendor launcher, Android's fallback, and whatever else is installed —
     * and the one in front is not always the default.
     */
    private var homePackages: Set<String> = emptySet()

    fun refreshHomePackages(context: Context) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homePackages = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }

    fun onForeground(packageName: String) {
        onHome.value = packageName in homePackages
    }
}
