package com.glassify.launcher.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * The direction light appears to come from, derived from how the phone is held.
 *
 * This is the piece that makes the glass feel physical: the highlight slides
 * around the rim as you tilt the device, so the panel reads as a lit surface
 * rather than a painted gradient. Everything else in the effect is static by
 * comparison.
 *
 * The gravity vector already gives us tilt without any of the drift that comes
 * with integrating the gyroscope, and it needs no permission. It is heavily
 * smoothed because raw sensor jitter turns the highlight into a flicker.
 */
@Composable
fun rememberTiltLight(enabled: Boolean = true): State<Offset> {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    val light = remember { mutableStateOf(DEFAULT_LIGHT) }

    DisposableEffect(enabled, inspecting) {
        if (!enabled || inspecting) {
            light.value = DEFAULT_LIGHT
            return@DisposableEffect onDispose { }
        }

        val sensorManager = context.getSystemService(SensorManager::class.java)
        val gravity = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gravity == null) {
            light.value = DEFAULT_LIGHT
            return@DisposableEffect onDispose { }
        }

        val listener = object : SensorEventListener {
            private var smoothX = 0f
            private var smoothY = 0f
            private var primed = false

            override fun onSensorChanged(event: SensorEvent) {
                // Normalise out of m/s^2 into roughly -1..1 per axis.
                val x = (event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                val y = (event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)

                if (!primed) {
                    smoothX = x
                    smoothY = y
                    primed = true
                } else {
                    smoothX += (x - smoothX) * SMOOTHING
                    smoothY += (y - smoothY) * SMOOTHING
                }

                // Screen y grows downwards while the sensor's y grows towards the
                // top of the device, hence the flip. Tilting the phone left must
                // move the highlight right, so x is negated too.
                val next = Offset(-smoothX, smoothY) + BASE_LIGHT
                if ((next - light.value).getDistanceSquared() > MIN_STEP) {
                    light.value = next
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    return light
}

/**
 * Where the highlight sits when the phone is flat on a table, and the constant
 * offset that keeps a top-left key light even at extreme tilts. Glass with the
 * light dead-centre looks flat, so the neutral pose is deliberately off-axis.
 */
private val BASE_LIGHT = Offset(-0.35f, -0.85f)
private val DEFAULT_LIGHT = BASE_LIGHT

private const val SMOOTHING = 0.12f

/** Ignore sub-pixel sensor noise; each accepted step recomposes the glass. */
private const val MIN_STEP = 0.00002f
