package com.gravity.phonefinder

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * The stop screen. It lights up the display over the lock screen so the phone is easy
 * to spot in the dark, and gives one very large button to silence the alarm.
 */
class AlarmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_alarm)
        findViewById<android.widget.Button>(R.id.stop_button).setOnClickListener {
            AlarmService.stop(this)
            finish()
        }
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /** The alarm is not dismissed by wandering off — only by the button. */
    @Deprecated("Kept for API 26-32, which have no back-pressed dispatcher.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Intentionally ignored.
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        // The flags cover API 26; setShowWhenLocked/setTurnScreenOn take over from 27.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }
        // Full brightness so the screen is visible across a dark room.
        window.attributes = window.attributes.apply { screenBrightness = 1f }
    }

    companion object {
        @Volatile
        private var current: AlarmActivity? = null

        /** Closes the stop screen when the alarm ends for any other reason. */
        fun dismiss() {
            current?.let { activity ->
                activity.runOnUiThread { activity.finish() }
            }
            current = null
        }
    }
}
