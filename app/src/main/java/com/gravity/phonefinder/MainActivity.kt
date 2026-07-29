package com.gravity.phonefinder

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri

/**
 * Setup screen. Everything this app needs is a permission the user has to grant by
 * hand, so the screen is a live checklist: each row shows whether that piece is ready
 * and its button opens exactly the settings page that fixes it.
 *
 * It is also the only place the microphone service can legally be started from —
 * Android requires a visible activity for that.
 */
class MainActivity : Activity() {

    private lateinit var checklist: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.createChannels(this)
        setContentView(R.layout.activity_main)

        checklist = findViewById(R.id.checklist)
        toggleButton = findViewById(R.id.toggle_button)
        statusText = findViewById(R.id.status_text)

        toggleButton.setOnClickListener { onToggle() }
        findViewById<Button>(R.id.test_button).setOnClickListener { AlarmService.start(this) }

        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            cancelBootHint()
            startListening()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            cancelBootHint()
            startListening()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // --- checklist ---------------------------------------------------------

    private fun render() {
        checklist.removeAllViews()

        addRow(
            title = getString(R.string.check_mic),
            ok = hasMicPermission(),
            detail = getString(R.string.check_mic_detail),
        ) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }

        addRow(
            title = getString(R.string.check_notifications),
            ok = hasNotificationPermission(),
            detail = getString(R.string.check_notifications_detail),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            } else {
                openAppNotificationSettings()
            }
        }

        addRow(
            title = getString(R.string.check_chat),
            ok = ChatNotificationListener.isEnabled(this),
            detail = getString(R.string.check_chat_detail),
        ) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }

        addRow(
            title = getString(R.string.check_battery),
            ok = isBatteryUnrestricted(),
            detail = getString(R.string.check_battery_detail),
        ) { requestBatteryExemption() }

        addRow(
            title = getString(R.string.check_fullscreen),
            ok = canUseFullScreenIntent(),
            detail = getString(R.string.check_fullscreen_detail),
        ) { requestFullScreenIntent() }

        addRow(
            title = getString(R.string.check_speech),
            ok = SpeechRecognizer.isRecognitionAvailable(this),
            detail = getString(R.string.check_speech_detail),
            action = null,
        )

        addRow(
            title = getString(R.string.check_admin),
            ok = isDeviceAdminActive(),
            detail = getString(R.string.check_admin_detail),
        ) { requestDeviceAdmin() }

        val listening = FinderService.isRunning
        toggleButton.setText(if (listening) R.string.action_stop_app else R.string.action_start_app)
        statusText.setText(if (listening) R.string.status_on else R.string.status_off)
    }

    private fun addRow(
        title: String,
        ok: Boolean,
        detail: String,
        action: (() -> Unit)? = null,
    ) {
        val row = layoutInflater.inflate(R.layout.item_check, checklist, false) as ViewGroup
        row.findViewById<TextView>(R.id.check_mark).text = if (ok) "✓" else "✗"
        row.findViewById<TextView>(R.id.check_mark)
            .setTextColor(getColor(if (ok) R.color.ok else R.color.missing))
        row.findViewById<TextView>(R.id.check_title).text = title
        row.findViewById<TextView>(R.id.check_detail).text = detail

        val button = row.findViewById<Button>(R.id.check_button)
        if (action == null || ok) {
            button.visibility = View.GONE
        } else {
            button.setOnClickListener { action() }
        }
        checklist.addView(row)
    }

    // --- actions -----------------------------------------------------------

    private fun onToggle() {
        if (FinderService.isRunning) {
            Prefs.setEnabled(this, false)
            AlarmService.stop(this)
            FinderService.stop(this)
            toggleButton.postDelayed({ render() }, 300)
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        Prefs.setEnabled(this, true)
        cancelBootHint()
        startForegroundService(Intent(this, FinderService::class.java))
        toggleButton.postDelayed({ render() }, 300)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            render()
        }
    }

    // --- permission state --------------------------------------------------

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }

    private fun isBatteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private val adminComponent: ComponentName
        get() = ComponentName(this, DeviceAdmin::class.java)

    private fun isDeviceAdminActive(): Boolean =
        getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent)

    private fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }

    // --- settings shortcuts ------------------------------------------------

    @Suppress("BatteryLife")
    private fun requestBatteryExemption() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:$packageName".toUri(),
        )
        if (!launch(intent)) open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    private fun requestFullScreenIntent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            "package:$packageName".toUri(),
        )
        if (!launch(intent)) openAppNotificationSettings()
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_add_explanation),
            )
        if (!launch(intent)) open(Settings.ACTION_SECURITY_SETTINGS)
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        if (!launch(intent)) open(Settings.ACTION_SETTINGS)
    }

    private fun open(action: String) {
        launch(Intent(action))
    }

    private fun launch(intent: Intent): Boolean = runCatching {
        startActivity(intent)
        true
    }.getOrDefault(false)

    private fun cancelBootHint() {
        getSystemService(NotificationManager::class.java).cancel(Notifications.ID_BOOT_HINT)
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        private const val REQ_MIC = 1
        private const val REQ_NOTIF = 2
    }
}
