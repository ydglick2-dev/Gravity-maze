package com.glassify.launcher.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.liquidGlass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A glass clock panel for the home screen.
 *
 * Deliberately the one piece of information a home screen always wants, on the
 * one surface that shows off the material: a wide, mostly empty pane where the
 * blur behind it has room to read as glass. Small busy panels hide the effect.
 */
@Composable
fun GlassClock(modifier: Modifier = Modifier) {
    val time by rememberClock()
    val battery by rememberBatteryLevel()
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
    }
    val date = remember(locale, time) {
        SimpleDateFormat("EEEE, d MMMM", locale).format(Date())
    }

    Column(
        modifier
            .liquidGlass(
                shape = GlassShapes.panel,
                cornerRadius = 34.dp,
                spec = GlassSpec(thickness = 16.dp, specular = 0.6f, surfaceAlpha = 0.42f),
            )
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = date, style = GlassTheme.type.callout, color = GlassTheme.colors.onGlassSecondary)
        Text(text = time, style = GlassTheme.type.clock, color = GlassTheme.colors.onGlass)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$battery%",
                style = GlassTheme.type.footnote,
                color = GlassTheme.colors.onGlassSecondary,
            )
        }
    }
}

/** Repaints on the minute tick the system already broadcasts. No polling. */
@Composable
private fun rememberClock(): State<String> {
    val context = LocalContext.current
    val use24 = DateFormat.is24HourFormat(context)
    val state = remember { mutableStateOf(formatTime(use24)) }

    DisposableEffect(use24) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                state.value = formatTime(use24)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        state.value = formatTime(use24)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

private fun formatTime(use24: Boolean): String {
    val calendar = Calendar.getInstance()
    val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val hour = if (use24) hour24 else ((hour24 + 11) % 12) + 1
    return "%d:%02d".format(hour, minute)
}

@Composable
private fun rememberBatteryLevel(): State<Int> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(100) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) state.value = level * 100 / scale
            }
        }
        // Sticky, so registering also delivers the current value straight away.
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver.onReceive(context, sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}
