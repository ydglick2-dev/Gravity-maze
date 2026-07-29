package com.gravity.phonefinder

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Everything the phone does to make itself findable: a looping alarm tone at maximum
 * alarm volume, a heavy repeating vibration, and a blinking torch.
 *
 * Each piece fails independently — a device without a flash still rings, a device
 * whose alarm stream is locked down still vibrates.
 */
class Alarm(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null
    private var torchCameraId: String? = null
    private var torchOn = false

    @Volatile
    var isPlaying = false
        private set

    fun start() {
        if (isPlaying) return
        isPlaying = true
        startSound()
        startVibration()
        startTorch()
    }

    fun stop() {
        if (!isPlaying) return
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
        stopSound()
        stopVibration()
        stopTorch()
    }

    // --- sound -------------------------------------------------------------

    private fun startSound() {
        try {
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        } catch (e: SecurityException) {
            // Do Not Disturb can block volume changes; play at the current level.
            Log.w(TAG, "could not raise alarm volume: ${e.message}")
        }

        val uri = alarmUri() ?: return
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "alarm sound failed", e)
            player?.release()
            player = null
        }
    }

    /** The user's alarm tone, falling back to the ringtone and then the notification tone. */
    private fun alarmUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun stopSound() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        previousAlarmVolume?.let {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        }
        previousAlarmVolume = null
    }

    // --- vibration ---------------------------------------------------------

    private fun startVibration() {
        val timings = longArrayOf(0, 600, 300, 600, 300, 600, 900)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0)
        val effect = VibrationEffect.createWaveform(timings, amplitudes, 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as VibratorManager
                manager.vibrate(CombinedVibration.createParallel(effect))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as VibratorManager
                manager.cancel()
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.cancel()
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibration cancel failed: ${e.message}")
        }
    }

    // --- torch -------------------------------------------------------------

    private val cameraManager: CameraManager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun startTorch() {
        torchCameraId = findFlashCamera() ?: return
        blink()
    }

    private fun blink() {
        if (!isPlaying) return
        val id = torchCameraId ?: return
        torchOn = !torchOn
        try {
            cameraManager.setTorchMode(id, torchOn)
        } catch (e: Exception) {
            // Another app may hold the camera; give up on the torch quietly.
            Log.w(TAG, "torch failed: ${e.message}")
            torchCameraId = null
            return
        }
        handler.postDelayed(::blink, if (torchOn) 300L else 500L)
    }

    private fun stopTorch() {
        val id = torchCameraId ?: return
        runCatching { cameraManager.setTorchMode(id, false) }
        torchOn = false
    }

    private fun findFlashCamera(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        Log.w(TAG, "no torch available: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "PhoneFinder/Alarm"
    }
}
