package com.glassify.launcher.island

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.glassify.launcher.notifications.GlassNotificationListener
import com.glassify.launcher.notifications.GlassNotification
import com.glassify.launcher.notifications.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * What the Island is currently showing.
 *
 * Modelled as one closed set of states rather than a pile of booleans, because
 * the interesting behaviour is the *transitions* — a notification arriving while
 * music plays has to flash and then fall back to the music, and that is a
 * property of the state machine, not of any single flag.
 */
sealed interface IslandState {

    /** Collapsed to the camera cutout. */
    data object Idle : IslandState

    data class Media(
        val title: String,
        val artist: String,
        val artwork: Bitmap?,
        val playing: Boolean,
        val packageName: String,
    ) : IslandState

    data class Charging(val percent: Int) : IslandState

    /** A transient flash for an arriving notification. */
    data class Alert(val notification: GlassNotification) : IslandState

    val expanded: Boolean get() = this !is Idle
}

/**
 * Drives the Island.
 *
 * Priority runs alert > charging > media > idle: a flash always wins for its few
 * seconds, then the display falls back to whatever persistent state still
 * applies. Plugging in while music plays therefore shows the charge, then
 * returns to the track rather than dropping to nothing.
 */
class IslandController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<IslandState>(IslandState.Idle)
    val state: StateFlow<IslandState> = _state.asStateFlow()

    private var media: IslandState.Media? = null
    private var charging: IslandState.Charging? = null
    private var alert: IslandState.Alert? = null
    private var alertJob: Job? = null

    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private var controllers: List<MediaController> = emptyList()

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMedia()
        override fun onSessionDestroyed() = refreshMedia()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener {
        bindSessions(it)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    charging = IslandState.Charging(batteryPercent())
                    publish()
                    // The charge readout is a moment of feedback, not a
                    // permanent indicator — it clears itself.
                    scope.launch {
                        delay(CHARGE_DISPLAY_MS)
                        charging = null
                        publish()
                    }
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    charging = null
                    publish()
                }
            }
        }
    }

    fun start() {
        val component = ComponentName(context, GlassNotificationListener::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsChanged, component)
            bindSessions(sessionManager?.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Notification access has not been granted yet; the Island still
            // works for alerts once it is.
            Log.i(TAG, "Media sessions unavailable without notification access")
        }

        ContextCompat.registerReceiver(
            context,
            batteryReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // drop(1) skips the list that is already on screen when we attach, so
        // connecting the service does not flash every existing notification.
        scope.launch {
            NotificationRepository.notifications.drop(1).collect { list ->
                val newest = list.firstOrNull { !it.ongoing } ?: return@collect
                if (newest.key == lastAlertKey) return@collect
                lastAlertKey = newest.key
                flash(newest)
            }
        }
    }

    fun stop() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (e: Exception) {
            // Never registered.
        }
        controllers.forEach { it.unregisterCallback(mediaCallback) }
        controllers = emptyList()
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered.
        }
        alertJob?.cancel()
    }

    private var lastAlertKey: String? = null

    private fun flash(notification: GlassNotification) {
        alertJob?.cancel()
        alert = IslandState.Alert(notification)
        publish()
        alertJob = scope.launch {
            delay(ALERT_DISPLAY_MS)
            alert = null
            publish()
        }
    }

    private fun bindSessions(sessions: List<MediaController>?) {
        controllers.forEach { it.unregisterCallback(mediaCallback) }
        controllers = sessions.orEmpty()
        controllers.forEach { it.registerCallback(mediaCallback) }
        refreshMedia()
    }

    private fun refreshMedia() {
        // Whichever session is actually playing wins; a paused session that
        // still exists should not hold the Island open.
        val active = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PAUSED
        }

        media = active?.let { controller ->
            val metadata = controller.metadata
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            if (title.isBlank()) return@let null
            IslandState.Media(
                title = title,
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                packageName = controller.packageName,
            )
        }
        publish()
    }

    /** The app the Island is currently about, so tapping it can open that app. */
    fun currentPackage(): String? = when (val current = _state.value) {
        is IslandState.Media -> current.packageName
        is IslandState.Alert -> current.notification.packageName
        else -> null
    }

    /** Media transport, wired to whichever session the Island is showing. */
    fun togglePlayback() {
        val target = controllers.firstOrNull { it.packageName == media?.packageName } ?: return
        if (target.playbackState?.state == PlaybackState.STATE_PLAYING) {
            target.transportControls.pause()
        } else {
            target.transportControls.play()
        }
    }

    fun skipNext() {
        controllers.firstOrNull { it.packageName == media?.packageName }
            ?.transportControls?.skipToNext()
    }

    private fun batteryPercent(): Int {
        val manager = context.getSystemService(BatteryManager::class.java)
        return manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
    }

    private fun publish() {
        _state.value = alert ?: charging ?: media ?: IslandState.Idle
    }

    private companion object {
        const val TAG = "IslandController"
        const val ALERT_DISPLAY_MS = 3800L
        const val CHARGE_DISPLAY_MS = 3000L
    }
}
