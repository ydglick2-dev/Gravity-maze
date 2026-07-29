package com.gravity.phonefinder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings listening back after a reboot or an app update.
 *
 * Android 14 and later refuse to let an app create a microphone foreground service
 * from the background, and a boot broadcast counts as background — no battery or
 * overlay exemption changes that. So this tries anyway (it works on older releases)
 * and, when the system says no, posts a notification the user taps once to restore
 * listening. The chat trigger keeps working either way.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> restore(context)
        }
    }

    private fun restore(context: Context) {
        if (!Prefs.isEnabled(context)) return
        Notifications.createChannels(context)

        // On Android 13 and below this simply works. On 14+ the start itself is
        // allowed but the service's own startForeground call is refused, and
        // FinderService posts the "tap to resume" notification from there.
        val service = Intent(context, FinderService::class.java)
        runCatching { context.startForegroundService(service) }.onFailure {
            Log.i(TAG, "microphone service blocked at boot: ${it.message}")
            context.getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_BOOT_HINT, Notifications.bootHint(context))
        }
    }

    private companion object {
        const val TAG = "PhoneFinder/Boot"
    }
}
