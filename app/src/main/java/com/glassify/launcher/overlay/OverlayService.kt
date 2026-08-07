package com.glassify.launcher.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.glassify.launcher.MainActivity
import com.glassify.launcher.R
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the Dynamic Island and the Notification Centre alive over other apps.
 *
 * A foreground service because that is the only way an overlay survives more
 * than a few minutes — and on One UI in particular, not even that is enough
 * without the battery-optimisation exemption the setup wizard asks for.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: LauncherPrefs
    private lateinit var island: IslandController

    private lateinit var islandHost: OverlayHost
    private lateinit var triggerHost: OverlayHost
    private lateinit var panelHost: OverlayHost

    private val panelOpen = MutableStateFlow(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = LauncherPrefs(this)
        island = IslandController(this, scope)

        islandHost = OverlayHost(this, windowManager)
        triggerHost = OverlayHost(this, windowManager)
        panelHost = OverlayHost(this, windowManager)

        startForeground()
        island.start()

        scope.launch {
            prefs.settings.collect { settings ->
                syncIsland(settings.islandEnabled, settings.islandTopOffsetDp, settings.darkTheme)
                syncTrigger(settings.notificationCenterEnabled && settings.edgeTriggerEnabled)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OPEN_NOTIFICATIONS) openPanel(dark = true)
        // Restarted by the system after being killed: the overlays are rebuilt
        // from preferences in onCreate, so there is no state to redeliver.
        return START_STICKY
    }

    override fun onDestroy() {
        island.stop()
        islandHost.dismiss()
        triggerHost.dismiss()
        panelHost.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    private fun syncIsland(enabled: Boolean, offsetDp: Int, dark: Boolean) {
        if (!enabled || !canDrawOverlays(this)) {
            islandHost.dismiss()
            return
        }
        if (islandHost.isShowing) return

        val offsetPx = (offsetDp * resources.displayMetrics.density).toInt()
        islandHost.show(OverlayWindows.island(offsetPx)) {
            GlassTheme(dark = dark, tier = GlassTier.deviceMax(this)) {
                val state by island.state.collectAsState()
                DynamicIsland(
                    state = state,
                    onTap = { openHome() },
                    onTogglePlayback = { island.togglePlayback() },
                )
            }
        }
    }

    /**
     * The invisible strip at the top-left that catches a downward swipe.
     *
     * It sits over the system's own quick-panel gesture area, which is exactly
     * why it is confined to the left half and only 26dp tall: it takes the iOS
     * gesture and leaves the right half of the status bar to One UI. Users who
     * still find it intrusive can turn it off in settings.
     */
    private fun syncTrigger(enabled: Boolean) {
        if (!enabled || !canDrawOverlays(this)) {
            triggerHost.dismiss()
            return
        }
        if (triggerHost.isShowing) return

        val density = resources.displayMetrics.density
        val height = (26 * density).toInt()
        val width = resources.displayMetrics.widthPixels / 2

        triggerHost.show(OverlayWindows.trigger(height, width)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var travel = 0f
                        detectVerticalDragGestures(
                            onDragStart = { travel = 0f },
                            onDragEnd = {
                                if (travel > TRIGGER_TRAVEL_PX) openPanel(dark = true)
                            },
                        ) { _, amount -> travel += amount }
                    }
            )
        }
    }

    private fun openPanel(dark: Boolean) {
        if (panelHost.isShowing || !canDrawOverlays(this)) return

        val blurRadius = (34 * resources.displayMetrics.density).toInt()
        panelOpen.value = true

        panelHost.show(OverlayWindows.panel(blurRadius)) {
            GlassTheme(dark = dark, tier = GlassTier.deviceMax(this)) {
                val notifications by NotificationRepository.notifications.collectAsState()
                NotificationCenter(
                    notifications = notifications,
                    onDismiss = { NotificationRepository.dismiss(it) },
                    onDismissAll = {
                        NotificationRepository.dismissAll()
                        closePanel()
                    },
                    onOpen = { closePanel() },
                    onClose = { closePanel() },
                )
            }
        }
    }

    private fun closePanel() {
        panelOpen.value = false
        panelHost.dismiss()
    }

    private fun openHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun startForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
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
         * Called whenever the launcher resumes, because the overlay permission
         * can be revoked from system settings while we are in the background and
         * there is no callback for that.
         */
        fun syncWithPermissions(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (canDrawOverlays(context)) {
                start(context, intent)
            } else {
                context.stopService(intent)
            }
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
         * return value. Two of our call sites are exactly that case: the
         * `MY_PACKAGE_REPLACED` broadcast, which arrives immediately after
         * install with no exemption, and `BOOT_COMPLETED` on devices that
         * dispatch it later than the exemption window. Neither is worth taking
         * the process down for — the overlays come back the next time the
         * launcher is opened.
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
