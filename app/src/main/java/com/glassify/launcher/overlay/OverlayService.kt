package com.glassify.launcher.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.glassify.launcher.R
import com.glassify.launcher.data.AppRepository
import com.glassify.launcher.data.GlassifySettings
import com.glassify.launcher.data.LauncherPrefs
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.GlassTier
import com.glassify.launcher.island.DynamicIsland
import com.glassify.launcher.island.IslandController
import com.glassify.launcher.notifications.NotificationCenter
import com.glassify.launcher.notifications.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hosts the glass layer that floats over the home screen.
 *
 * A foreground service because that is the only way an overlay survives more
 * than a few minutes — and on One UI, not even that is enough without the
 * battery-optimisation exemption the setup screen asks for.
 *
 * The dock and the clock are attached and detached as the user moves between the
 * home screen and other apps, so they are genuinely only on the home screen
 * rather than merely invisible elsewhere: an attached window still intercepts
 * touches in its bounds, which would make the bottom of every app unresponsive.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: LauncherPrefs
    private lateinit var island: IslandController
    private lateinit var apps: AppRepository

    private lateinit var islandHost: OverlayHost
    private lateinit var dockHost: OverlayHost
    private lateinit var clockHost: OverlayHost
    private lateinit var triggerHost: OverlayHost
    private lateinit var panelHost: OverlayHost

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = LauncherPrefs(this)
        island = IslandController(this, scope)
        apps = AppRepository(this, scope)

        islandHost = OverlayHost(this, windowManager)
        dockHost = OverlayHost(this, windowManager)
        clockHost = OverlayHost(this, windowManager)
        triggerHost = OverlayHost(this, windowManager)
        panelHost = OverlayHost(this, windowManager)

        startForeground()
        island.start()
        apps.start()

        scope.launch {
            combine(
                prefs.settings,
                HomeWatcher.onHomeScreen,
            ) { settings, onHome -> settings to onHome }
                .collect { (settings, onHome) -> sync(settings, onHome) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OPEN_NOTIFICATIONS) openPanel()
        // Restarted after being killed: everything is rebuilt from preferences
        // in onCreate, so there is no state to redeliver.
        return START_STICKY
    }

    override fun onDestroy() {
        island.stop()
        apps.stop()
        listOf(islandHost, dockHost, clockHost, triggerHost, panelHost).forEach { it.dismiss() }
        scope.cancel()
        super.onDestroy()
    }

    private fun sync(settings: GlassifySettings, onHomeScreen: Boolean) {
        if (!canDrawOverlays(this)) {
            listOf(islandHost, dockHost, clockHost, triggerHost).forEach { it.dismiss() }
            return
        }

        // Without the accessibility service there is no way to tell the home
        // screen from anything else, so the home-only pieces show everywhere
        // rather than never — the user chose to have them.
        val homeVisible = onHomeScreen || !HomeWatcher.isEnabled(this)

        syncIsland(settings)
        syncDock(settings, homeVisible)
        syncClock(settings, homeVisible)
        syncTrigger(settings)
    }

    /** The Island stays up everywhere, exactly as it does on an iPhone. */
    private fun syncIsland(settings: GlassifySettings) {
        if (!settings.islandEnabled) {
            islandHost.dismiss()
            return
        }
        if (islandHost.isShowing) return

        val offsetPx = (settings.islandTopOffsetDp * resources.displayMetrics.density).toInt()
        islandHost.show(OverlayWindows.island(offsetPx)) {
            GlassTheme(dark = settings.darkTheme, tier = tier(settings)) {
                val state by island.state.collectAsState()
                DynamicIsland(
                    state = state,
                    onTap = { openIslandTarget() },
                    onTogglePlayback = { island.togglePlayback() },
                )
            }
        }
    }

    private fun syncDock(settings: GlassifySettings, visible: Boolean) {
        if (!settings.dockEnabled || !visible) {
            dockHost.dismiss()
            return
        }
        if (dockHost.isShowing) return

        val bottomPx = (settings.dockBottomOffsetDp * resources.displayMetrics.density).toInt()
        dockHost.show(OverlayWindows.dock(bottomPx)) {
            GlassTheme(dark = settings.darkTheme, tier = tier(settings)) {
                val installed by apps.apps.collectAsState()
                val chosen = settings.dockKeys.mapNotNull { key ->
                    installed.firstOrNull { it.key == key }
                }
                GlassDock(
                    apps = chosen,
                    onLaunch = { app, bounds -> apps.launch(app, bounds) },
                )
            }
        }
    }

    private fun syncClock(settings: GlassifySettings, visible: Boolean) {
        if (!settings.clockEnabled || !visible) {
            clockHost.dismiss()
            return
        }
        if (clockHost.isShowing) return

        val topPx = (settings.clockTopOffsetDp * resources.displayMetrics.density).toInt()
        clockHost.show(OverlayWindows.clock(topPx)) {
            GlassTheme(dark = settings.darkTheme, tier = tier(settings)) {
                GlassClock()
            }
        }
    }

    /**
     * The invisible strip at the top-left that catches a downward swipe.
     *
     * It sits over the system's own quick-panel gesture area, which is why it is
     * confined to the left half and only 26dp tall: it takes the iOS gesture and
     * leaves the right half of the status bar to One UI.
     */
    private fun syncTrigger(settings: GlassifySettings) {
        if (!settings.notificationCenterEnabled || !settings.edgeTriggerEnabled) {
            triggerHost.dismiss()
            return
        }
        if (triggerHost.isShowing) return

        val density = resources.displayMetrics.density
        triggerHost.show(
            OverlayWindows.trigger(
                heightPx = (26 * density).toInt(),
                widthPx = resources.displayMetrics.widthPixels / 2,
            )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var travel = 0f
                        detectVerticalDragGestures(
                            onDragStart = { travel = 0f },
                            onDragEnd = { if (travel > TRIGGER_TRAVEL_PX) openPanel() },
                        ) { _, amount -> travel += amount }
                    }
            )
        }
    }

    private fun openPanel() {
        if (panelHost.isShowing || !canDrawOverlays(this)) return

        val blurRadius = (34 * resources.displayMetrics.density).toInt()
        panelHost.show(OverlayWindows.panel(blurRadius)) {
            GlassTheme(dark = true, tier = GlassTier.deviceMax(this)) {
                val notifications by NotificationRepository.notifications.collectAsState()
                NotificationCenter(
                    notifications = notifications,
                    onDismiss = { NotificationRepository.dismiss(it) },
                    onDismissAll = {
                        NotificationRepository.dismissAll()
                        panelHost.dismiss()
                    },
                    onOpen = { panelHost.dismiss() },
                    onClose = { panelHost.dismiss() },
                )
            }
        }
    }

    /** Tapping the Island opens whatever it is currently showing. */
    private fun openIslandTarget() {
        val target = island.currentPackage() ?: return
        val intent = packageManager.getLaunchIntentForPackage(target) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open $target", e)
        }
    }

    private fun tier(settings: GlassifySettings) = settings.resolveTier(GlassTier.deviceMax(this))

    private fun startForeground() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_service_channel),
                // MIN keeps it out of the shade's visible list; the service
                // still needs *a* notification, it just does not need to shout.
                NotificationManager.IMPORTANCE_MIN,
            )
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_service_title))
            .setContentText(getString(R.string.overlay_service_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "glassify-overlays"
        private const val NOTIFICATION_ID = 1001
        private const val TRIGGER_TRAVEL_PX = 40f

        const val ACTION_OPEN_NOTIFICATIONS = "com.glassify.launcher.OPEN_NOTIFICATIONS"

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /**
         * Starts or stops the service to match the permissions actually granted.
         *
         * Called whenever the app resumes, because the overlay permission can be
         * revoked from system settings while we are in the background and there
         * is no callback for that.
         */
        fun syncWithPermissions(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (canDrawOverlays(context)) start(context, intent) else context.stopService(intent)
        }

        fun openNotifications(context: Context) {
            if (!canDrawOverlays(context)) return
            start(
                context,
                Intent(context, OverlayService::class.java).setAction(ACTION_OPEN_NOTIFICATIONS),
            )
        }

        /**
         * Starts the service, tolerating the platform refusing.
         *
         * Since Android 12 a foreground service cannot be started while the app
         * is in the background, and the refusal is an exception rather than a
         * return value. Two call sites are exactly that case: the
         * `MY_PACKAGE_REPLACED` broadcast, which arrives right after install with
         * no exemption, and `BOOT_COMPLETED` on devices that dispatch it late.
         * Neither is worth taking the process down for — the overlays come back
         * the next time the app is opened.
         */
        private fun start(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Not allowed to start the overlay service right now", e)
            }
        }
    }
}
