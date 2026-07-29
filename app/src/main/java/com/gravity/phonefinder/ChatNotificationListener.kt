package com.gravity.phonefinder

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * The backup trigger: an incoming chat message that says "מצא טלפון" sets the phone
 * off, whether it arrives over WhatsApp, Telegram, SMS or anything else that posts a
 * notification.
 *
 * Unlike the microphone service, the system binds this one by itself — including
 * straight after a reboot — which makes it the dependable path when the phone is
 * genuinely lost.
 *
 * Privacy: notification text is matched in memory and immediately discarded. Nothing
 * is stored, logged or sent anywhere.
 */
class ChatNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras ?: return
        val candidates = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
            extras.getCharSequence(Notification.EXTRA_TITLE),
        ) + messagingLines(extras)

        for (line in candidates) {
            val text = line.toString()
            if (TriggerMatcher.isFindCommand(text)) {
                Log.i(TAG, "trigger message from ${sbn.packageName}")
                AlarmService.start(this)
                return
            }
            if (TriggerMatcher.isStopCommand(text) && AlarmService.isPlaying) {
                AlarmService.stop(this)
                return
            }
        }
    }

    /** Chat apps often bundle several messages under EXTRA_TEXT_LINES. */
    private fun messagingLines(extras: android.os.Bundle): List<CharSequence> =
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList() ?: emptyList()

    companion object {
        const val TAG = "PhoneFinder/Chat"

        /** Whether the user has granted this app notification access in system settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val component = ComponentName(context, ChatNotificationListener::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == component
            }
        }
    }
}
