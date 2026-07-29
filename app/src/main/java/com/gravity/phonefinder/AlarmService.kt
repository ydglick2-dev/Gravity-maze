package com.gravity.phonefinder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Owns the siren. Kept separate from [FinderService] on purpose: this service is a
 * media-playback foreground service, which — unlike a microphone one — Android lets
 * the app start from the background. That is what allows a chat message to set the
 * phone off after a reboot, when the listening service is not running yet.
 */
class AlarmService : Service() {

    private lateinit var alarm: Alarm
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        alarm = Alarm(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_ALARM, Notifications.alarm(this), type)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!alarm.isPlaying) {
            playing = true
            acquireWakeLock()
            alarm.start()
            showAlarmScreen()
            handler.postDelayed({ stopSelf() }, ALARM_TIMEOUT_MS)
            FinderService.onAlarmStateChanged()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        playing = false
        handler.removeCallbacksAndMessages(null)
        alarm.stop()
        AlarmActivity.dismiss()
        releaseWakeLock()
        FinderService.onAlarmStateChanged()
        super.onDestroy()
    }

    /**
     * The alarm notification carries a full-screen intent, which is the supported way
     * to light up a locked screen. Android 14 hands out USE_FULL_SCREEN_INTENT
     * sparingly, so try launching the screen directly as well — permitted while a
     * foreground service is running or the app is exempt from battery optimisation.
     */
    private fun showAlarmScreen() {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION,
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "alarm screen blocked: ${it.message}") }
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(ALARM_TIMEOUT_MS + 10_000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        const val TAG = "PhoneFinder/Alarm"
        const val ACTION_START = "com.gravity.phonefinder.ALARM_START"
        const val ACTION_STOP = "com.gravity.phonefinder.ALARM_STOP"

        private const val WAKE_LOCK_TAG = "PhoneFinder::alarm"
        private const val ALARM_TIMEOUT_MS = 2 * 60 * 1000L

        @Volatile
        private var playing = false

        val isPlaying: Boolean get() = playing

        fun start(context: Context) {
            if (playing) return
            val intent = Intent(context, AlarmService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(TAG, "could not start alarm: ${it.message}") }
        }

        fun stop(context: Context) {
            if (!playing) return
            val intent = Intent(context, AlarmService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "could not stop alarm: ${it.message}") }
        }
    }
}
