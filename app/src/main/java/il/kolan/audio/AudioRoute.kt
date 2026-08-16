package il.kolan.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** What the processed audio is currently coming out of. */
enum class OutputDevice {
    WIRED,
    BLUETOOTH,
    USB,
    SPEAKER,
}

data class RouteState(
    val device: OutputDevice,
    val deviceName: String?,
) {
    /**
     * Whether LIVE MONITOR is safe to run.
     *
     * Without headphones the microphone hears the speaker, the speaker replays it a few
     * milliseconds later, and the loop screams. This is the single check that stops that.
     */
    val headphonesConnected: Boolean get() = device != OutputDevice.SPEAKER
}

/**
 * Watches output routing.
 *
 * Android reports device changes rather than "are headphones plugged in", so this maps the
 * device list onto the only distinction that matters here: does the output reach the user's ears
 * without also reaching the microphone.
 */
class AudioRoute(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun currentState(): RouteState {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val wired = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        if (wired != null) return RouteState(OutputDevice.WIRED, wired.productName?.toString())

        val usb = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        if (usb != null) return RouteState(OutputDevice.USB, usb.productName?.toString())

        val bluetooth = devices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        if (bluetooth != null) {
            return RouteState(OutputDevice.BLUETOOTH, bluetooth.productName?.toString())
        }

        return RouteState(OutputDevice.SPEAKER, null)
    }

    /** Emits the current state immediately, then again on every device change. */
    fun observe(): Flow<RouteState> = callbackFlow {
        trySend(currentState())

        val handler = Handler(Looper.getMainLooper())
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                trySend(currentState())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                trySend(currentState())
            }
        }

        audioManager.registerAudioDeviceCallback(callback, handler)
        awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    /**
     * Picks a microphone that matches the output.
     *
     * With a wired or USB headset the headset's own microphone is closer to the mouth and, more
     * usefully, further from the earpieces, so the loop gain is lower. Returning 0 lets the
     * platform choose.
     */
    fun preferredInputDeviceId(): Int {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val headsetMic = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return headsetMic?.id ?: 0
    }

    /** Routes playback to the built-in speaker for LOUDSPEAKER mode. */
    fun setSpeakerphoneOn(enabled: Boolean) {
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = enabled
    }
}
