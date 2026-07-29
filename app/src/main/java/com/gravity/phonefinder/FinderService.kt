package com.gravity.phonefinder

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * The always-on listening half of the app.
 *
 * It is a microphone-type foreground service, which Android only allows an app to
 * create while it has a visible activity — so it is started once from [MainActivity]
 * and then simply stays alive. Screen on/off never recreates it; it only decides
 * whether [VoiceListener] is actively recording, which is what the user asked for:
 * listen while the screen is off (the state a lost phone is in) and stay quiet
 * otherwise.
 */
class FinderService : Service() {

    private lateinit var voice: VoiceListener
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenOff = false
    private var started = false

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        voice = VoiceListener(this) { phrase -> onPhrase(phrase) }
        registerScreenReceiver()
        screenOff = !isScreenOn()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            if (!goForeground(listening = false)) {
                // Android refused the microphone service — see goForeground.
                stopSelf()
                return START_NOT_STICKY
            }
            started = true
        }
        if (intent?.action == ACTION_STOP_SERVICE) {
            AlarmService.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        syncListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        voice.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    /** Come back if the user swipes the app away from recents. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (started) {
            runCatching { startService(Intent(applicationContext, FinderService::class.java)) }
        }
        super.onTaskRemoved(rootIntent)
    }

    // --- foreground state --------------------------------------------------

    /**
     * Returns false when the system refuses the microphone foreground service. That
     * happens on Android 14 and later whenever the service is started from the
     * background — after a reboot, most of all — and it is the one case the app
     * cannot work around, so the user gets a notification asking for the single tap
     * that starts it from the foreground instead.
     */
    private fun goForeground(listening: Boolean): Boolean {
        // The microphone type only exists from API 30; earlier releases take a plain
        // foreground service, which is all they require to keep recording.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_STATUS,
                Notifications.status(this, listening),
                type,
            )
            instance = this
            true
        } catch (e: Exception) {
            Log.w(TAG, "microphone service refused: ${e.message}")
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_BOOT_HINT, Notifications.bootHint(this))
            false
        }
    }

    private fun updateStatusNotification(listening: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_STATUS, Notifications.status(this, listening))
    }

    // --- listening policy --------------------------------------------------

    /**
     * Record while the screen is off, and also while the alarm is sounding so that
     * saying "עצור" can silence it.
     */
    private fun syncListening() {
        val shouldListen = screenOff || AlarmService.isPlaying
        if (shouldListen && !voice.isRunning) {
            acquireWakeLock()
            voice.start()
            updateStatusNotification(listening = true)
        } else if (!shouldListen && voice.isRunning) {
            voice.stop()
            releaseWakeLock()
            updateStatusNotification(listening = false)
        }
    }

    private fun onPhrase(phrase: String) {
        if (AlarmService.isPlaying) {
            if (TriggerMatcher.isStopCommand(phrase)) AlarmService.stop(this)
            return
        }
        if (TriggerMatcher.isFindCommand(phrase)) {
            Log.i(TAG, "trigger phrase recognised")
            AlarmService.start(this)
        }
    }

    // --- screen state ------------------------------------------------------

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOff = intent?.action == Intent.ACTION_SCREEN_OFF
            syncListening()
        }
    }

    private fun isScreenOn(): Boolean = getSystemService(PowerManager::class.java).isInteractive

    // --- wake lock ---------------------------------------------------------

    /**
     * Held without a timeout on purpose: the whole point is to keep recording for as
     * long as the screen stays off. It is released the moment listening stops, and
     * lint's timeout advice does not apply to a service the user switched on.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        const val TAG = "PhoneFinder/Service"
        const val ACTION_STOP_SERVICE = "com.gravity.phonefinder.STOP_SERVICE"

        private const val WAKE_LOCK_TAG = "PhoneFinder::listening"

        /** Set while the service is in the foreground; used to reach it without binding. */
        @Volatile
        private var instance: FinderService? = null

        val isRunning: Boolean get() = instance != null

        /**
         * Called by [AlarmService] when the siren starts or stops, so listening can
         * stay on during an alarm even with the screen lit.
         */
        fun onAlarmStateChanged() {
            instance?.syncListening()
        }

        fun stop(context: Context) {
            val intent = Intent(context, FinderService::class.java).setAction(ACTION_STOP_SERVICE)
            runCatching { context.startService(intent) }
        }
    }
}
