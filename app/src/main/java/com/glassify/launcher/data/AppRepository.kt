package com.glassify.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import com.glassify.launcher.icons.IconShaper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The installed-app list, reshaped and cached.
 *
 * Reshaping every icon costs real time — a few milliseconds each across a couple
 * of hundred apps — and a launcher that takes a second to paint after every Home
 * press is unusable. So shaped icons are written to disk keyed by the app's
 * version, and a cold start reads bitmaps back instead of re-rendering them.
 * Only apps whose version changed are re-shaped.
 */
class AppRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val iconSizePx: Int = DEFAULT_ICON_PX,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val shaper = IconShaper(iconSizePx)
    private val cacheDir = File(context.cacheDir, "shaped-icons").apply { mkdirs() }
    private val diskLock = Mutex()

    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refreshLater()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refreshLater()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refreshLater()
        override fun onPackagesAvailable(
            packageNames: Array<out String>?,
            user: UserHandle,
            replacing: Boolean,
        ) = refreshLater()

        override fun onPackagesUnavailable(
            packageNames: Array<out String>?,
            user: UserHandle,
            replacing: Boolean,
        ) = refreshLater()
    }

    fun start() {
        launcherApps?.registerCallback(callback)
        refreshLater()
    }

    fun stop() {
        launcherApps?.unregisterCallback(callback)
    }

    private fun refreshLater() {
        scope.launch { refresh() }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val activities = try {
            launcherApps?.getActivityList(null, Process.myUserHandle()).orEmpty()
        } catch (e: SecurityException) {
            // Thrown while the device is locked; the callback will fire again.
            Log.w(TAG, "Cannot read the app list yet", e)
            return@withContext
        }

        val loaded = activities
            .map { it.toLaunchableApp() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

        _apps.value = loaded
        _loading.value = false
        pruneCache(loaded)
    }

    private suspend fun LauncherActivityInfo.toLaunchableApp(): LaunchableApp {
        val key = LaunchableApp.keyOf(componentName.packageName, componentName.className)
        val version = try {
            context.packageManager.getPackageInfo(componentName.packageName, 0).longVersionCode
        } catch (e: Exception) {
            0L
        }

        val icon = loadOrShapeIcon(key, version) {
            // density 0 asks for the highest-density icon available, which is
            // what we want since we are about to redraw it at our own size.
            getIcon(0)?.let { shaper.shape(it) }
        }

        return LaunchableApp(
            key = key,
            packageName = componentName.packageName,
            activityName = componentName.className,
            label = label?.toString().orEmpty().ifBlank { componentName.packageName },
            icon = icon?.asImageBitmap(),
            category = AppCategorizer.categorize(componentName.packageName, applicationInfo),
        )
    }

    private suspend fun loadOrShapeIcon(
        key: String,
        version: Long,
        shape: () -> Bitmap?,
    ): Bitmap? {
        val file = File(cacheDir, cacheName(key, version))
        diskLock.withLock {
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
            }
        }

        val shaped = try {
            shape()
        } catch (e: Exception) {
            Log.w(TAG, "Could not shape the icon for $key", e)
            null
        } ?: return null

        diskLock.withLock {
            try {
                file.outputStream().use { shaped.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Exception) {
                // A full cache directory is not worth failing a launcher draw over.
                Log.w(TAG, "Could not cache the icon for $key", e)
            }
        }
        return shaped
    }

    /** Drops cache entries for apps that were uninstalled or updated. */
    private suspend fun pruneCache(current: List<LaunchableApp>) = diskLock.withLock {
        val live = current.mapTo(mutableSetOf()) { app ->
            val version = try {
                context.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            } catch (e: Exception) {
                0L
            }
            cacheName(app.key, version)
        }
        cacheDir.listFiles()?.forEach { file ->
            if (file.name !in live) file.delete()
        }
    }

    private fun cacheName(key: String, version: Long) =
        "${key.hashCode().toUInt().toString(16)}_${version}_$iconSizePx.png"

    /** Launches an app, animating out of the icon the user actually tapped. */
    fun launch(app: LaunchableApp, sourceBounds: Rect? = null) {
        try {
            launcherApps?.startMainActivity(
                ComponentName(app.packageName, app.activityName),
                Process.myUserHandle(),
                sourceBounds,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch ${app.key}", e)
        }
    }

    fun openAppInfo(app: LaunchableApp, sourceBounds: Rect? = null) {
        try {
            launcherApps?.startAppDetailsActivity(
                ComponentName(app.packageName, app.activityName),
                Process.myUserHandle(),
                sourceBounds,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open app info for ${app.key}", e)
        }
    }

    private companion object {
        const val TAG = "AppRepository"

        /**
         * Shaped once at a size comfortably above the largest grid cell, so the
         * same bitmap serves the home screen, the dock, folders and the App
         * Library's 2x2 previews without re-rendering.
         */
        const val DEFAULT_ICON_PX = 192
    }
}
