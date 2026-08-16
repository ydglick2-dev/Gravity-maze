package il.kolan.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import il.kolan.KolanApp
import il.kolan.MainActivity
import il.kolan.R
import il.kolan.audio.EngineMode
import il.kolan.data.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the microphone alive while the app is not in the foreground.
 *
 * Android will kill microphone access the moment the process drops out of the foreground unless
 * a service of type `microphone` is running, so LIVE MONITOR and LOUDSPEAKER both depend on
 * this. The notification carries stop and next-preset actions so the user can work the app
 * without unlocking back into it.
 */
class VoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    private val app: KolanApp get() = application as KolanApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = intent.getStringExtra(EXTRA_MODE)?.let { EngineMode.valueOf(it) }
                    ?: EngineMode.LIVE_MONITOR
                startEngine(mode)
            }

            ACTION_STOP -> {
                stopEngine()
                return START_NOT_STICKY
            }

            ACTION_NEXT_PRESET -> scope.launch { cyclePreset() }

            ACTION_TOGGLE_BYPASS -> {
                val controller = app.engineController
                controller.setBypassed(!controller.isBypassed())
                scope.launch { refreshNotification() }
            }
        }
        return START_STICKY
    }

    private fun startEngine(mode: EngineMode) {
        // The notification has to be posted before the engine opens the microphone, otherwise
        // the platform treats the capture as a background one and silences it.
        scope.launch {
            val preset = app.presetRepository.selectedPreset.first()
            startForegroundWith(buildNotification(preset, mode))

            if (!app.engineController.start(mode)) {
                stopEngine()
                return@launch
            }
            observePresetChanges(mode)
        }
    }

    private fun startForegroundWith(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun observePresetChanges(mode: EngineMode) {
        observerJob?.cancel()
        observerJob = scope.launch {
            combine(
                app.presetRepository.selectedPreset,
                app.engineController.routeState,
            ) { preset, _ -> preset }.collect { preset ->
                startForegroundWith(buildNotification(preset, mode))
            }
        }
    }

    private suspend fun refreshNotification() {
        val preset = app.presetRepository.selectedPreset.first()
        startForegroundWith(buildNotification(preset, app.engineController.mode.value))
    }

    private suspend fun cyclePreset() {
        val presets = app.presetRepository.allPresets.first()
        if (presets.isEmpty()) return
        val currentId = app.presetRepository.selectedPresetId.first()
        val index = presets.indexOfFirst { it.id == currentId }
        val next = presets[(index + 1).mod(presets.size)]
        app.presetRepository.selectPreset(next)
    }

    private fun stopEngine() {
        observerJob?.cancel()
        app.engineController.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(preset: Preset, mode: EngineMode): Notification {
        val presetName = when (preset) {
            is il.kolan.data.BuiltInPreset -> getString(preset.nameRes)
            is il.kolan.data.CustomPreset -> preset.name
        }

        val modeLabel = when (mode) {
            EngineMode.LOUDSPEAKER -> getString(R.string.mode_loudspeaker)
            EngineMode.CALL -> getString(R.string.mode_call)
            else -> getString(R.string.mode_live)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, KolanApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title, modeLabel))
            .setContentText(getString(R.string.notification_preset, presetName))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_skip,
                getString(R.string.notification_next_preset),
                servicePendingIntent(ACTION_NEXT_PRESET, 1),
            )
            .addAction(
                R.drawable.ic_compare,
                getString(R.string.notification_toggle_bypass),
                servicePendingIntent(ACTION_TOGGLE_BYPASS, 2),
            )
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.notification_stop),
                servicePendingIntent(ACTION_STOP, 3),
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, VoiceService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "il.kolan.action.START"
        const val ACTION_STOP = "il.kolan.action.STOP"
        const val ACTION_NEXT_PRESET = "il.kolan.action.NEXT_PRESET"
        const val ACTION_TOGGLE_BYPASS = "il.kolan.action.TOGGLE_BYPASS"
        private const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: EngineMode) {
            val intent = Intent(context, VoiceService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
