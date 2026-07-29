package com.gravity.phonefinder

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Registers the app as a device administrator.
 *
 * The one effect the app relies on is that an active admin cannot be uninstalled
 * until it is deactivated in Settings — a small anti-tamper guard so whoever ends up
 * holding a lost phone cannot remove the finder in two taps.
 *
 * This does NOT, and technically cannot, override parental controls or a Family Link
 * screen-time limit: those are enforced above app level and no self-installed admin
 * can exempt itself from them.
 */
class DeviceAdmin : DeviceAdminReceiver() {

    /** Shown when the user tries to turn admin off, explaining what protection is lost. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}
