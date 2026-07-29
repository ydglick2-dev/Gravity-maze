package com.gravity.phonefinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Channels, ids and builders for every notification the app posts. */
object Notifications {

    const val CHANNEL_STATUS = "finder_status"
    const val CHANNEL_ALARM = "finder_alarm"

    const val ID_STATUS = 1
    const val ID_ALARM = 2
    const val ID_BOOT_HINT = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val status = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_status_desc)
            setShowBadge(false)
        }

        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(R.string.channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_desc)
            // The alarm audio is played by the app itself, not by the notification.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(status)
        manager.createNotificationChannel(alarm)
    }

    /** The permanent notification that keeps the foreground service alive. */
    fun status(context: Context, listening: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                context.getString(
                    if (listening) R.string.status_listening else R.string.status_standby,
                ),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                context.getString(R.string.action_test_alarm),
                command(context, AlarmCommandReceiver.ACTION_TRIGGER_ALARM, 10),
            )
            .addAction(
                0,
                context.getString(R.string.action_stop_app),
                command(context, AlarmCommandReceiver.ACTION_STOP_SERVICE, 11),
            )
            .build()
    }

    /** The heads-up / full-screen notification shown while the phone is sounding off. */
    fun alarm(context: Context): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            20,
            Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.alarm_title))
            .setContentText(context.getString(R.string.alarm_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(
                0,
                context.getString(R.string.action_stop_alarm),
                command(context, AlarmCommandReceiver.ACTION_STOP_ALARM, 21),
            )
            .build()
    }

    /** Shown after a reboot, when Android will not let us re-open the microphone on our own. */
    fun bootHint(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            30,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_AUTO_START, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.boot_hint_title))
            .setContentText(context.getString(R.string.boot_hint_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun command(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmCommandReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
