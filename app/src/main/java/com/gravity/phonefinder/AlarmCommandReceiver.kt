package com.gravity.phonefinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes the buttons on the app's notifications to the right service. */
class AlarmCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRIGGER_ALARM -> AlarmService.start(context)
            ACTION_STOP_ALARM -> AlarmService.stop(context)
            ACTION_STOP_SERVICE -> {
                AlarmService.stop(context)
                FinderService.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.gravity.phonefinder.cmd.TRIGGER_ALARM"
        const val ACTION_STOP_ALARM = "com.gravity.phonefinder.cmd.STOP_ALARM"
        const val ACTION_STOP_SERVICE = "com.gravity.phonefinder.cmd.STOP_SERVICE"
    }
}
