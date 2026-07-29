package com.gravity.phonefinder

import android.content.Context
import androidx.core.content.edit

/** Remembers whether the user switched listening on, so a reboot can restore it. */
object Prefs {

    private const val FILE = "phone_finder"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
