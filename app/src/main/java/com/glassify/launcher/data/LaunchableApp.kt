package com.glassify.launcher.data

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * One launchable activity, already reshaped for the home screen.
 *
 * [key] is `package/activity`, which is what the saved layout stores. It stays
 * stable across app updates, so reordering the home screen survives an update
 * while a bare package name would not distinguish an app's multiple launcher
 * entries (Settings shortcuts, dual-messenger clones, and so on).
 */
@Immutable
data class LaunchableApp(
    val key: String,
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: ImageBitmap?,
    val category: AppCategory,
) {
    companion object {
        fun keyOf(packageName: String, activityName: String) = "$packageName/$activityName"
    }
}

/**
 * The App Library's shelves.
 *
 * Android hands us `ApplicationInfo.category`, but developers set it rarely and
 * carelessly, so it is only ever a hint here — [AppCategorizer] falls back to
 * matching the package name, which in practice sorts far more apps correctly.
 */
enum class AppCategory {
    SOCIAL,
    ENTERTAINMENT,
    CREATIVITY,
    PRODUCTIVITY,
    UTILITIES,
    INFORMATION,
    TRAVEL,
    SHOPPING,
    FINANCE,
    HEALTH,
    GAMES,
    OTHER,
}
