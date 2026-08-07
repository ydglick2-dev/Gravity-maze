package com.glassify.launcher.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the overlays back after a reboot or an app update.
 *
 * Without this the Dynamic Island silently stops existing after every restart
 * until the user happens to open the launcher — which, on a phone where the
 * launcher *is* the home screen, could be a while.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> OverlayService.syncWithPermissions(context)
        }
    }
}
