package com.glassify.launcher.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import com.glassify.launcher.glass.GlassTheme
import java.util.Calendar

/**
 * Our own status bar, drawn on the home screen in place of the system's.
 *
 * The system bar is hidden while the launcher is in front — its clock sits on
 * the wrong side, its battery is the wrong shape, and on One UI it carries a
 * cluster of vendor icons that immediately gives the game away. Only the
 * launcher can do this; inside other apps the real status bar is still theirs.
 *
 * Deliberately no dangerous permissions: signal *strength* would need
 * READ_PHONE_STATE and the Wi-Fi SSID would need location, neither of which is
 * worth asking for to draw a four-bar glyph.
 */
@Composable
fun IosStatusBar(
    modifier: Modifier = Modifier,
    tint: Color = GlassTheme.colors.onGlass,
) {
    val time by rememberClock()
    val battery by rememberBattery()
    val connectivity by rememberConnectivity()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS keeps the clock left and the indicators right even in Hebrew, so
        // the bar is laid out in a fixed direction regardless of locale.
        val clock = @Composable {
            Text(
                text = time,
                style = GlassTheme.type.footnote,
                color = tint,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(72.dp),
            )
        }
        val indicators = @Composable {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                CellularBars(connectivity.cellularBars, tint)
                if (connectivity.wifi) WifiGlyph(tint)
                BatteryPill(battery.percent, battery.charging, tint)
            }
        }

        if (rtl) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { indicators() }
            clock()
        } else {
            clock()
            Spacer(Modifier.weight(1f))
            indicators()
        }
    }
}

/** The iOS battery: a rounded body, a nub, and a fill inset by one point. */
@Composable
private fun BatteryPill(percent: Int, charging: Boolean, tint: Color) {
    val positive = GlassTheme.colors.positive
    val warning = GlassTheme.colors.destructive

    Canvas(Modifier.size(width = 27.dp, height = 13.dp)) {
        val nub = size.width * 0.055f
        val bodyWidth = size.width - nub - 1.dp.toPx()
        val radius = size.height * 0.32f

        drawRoundRect(
            color = tint.copy(alpha = 0.38f),
            size = Size(bodyWidth, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            style = Stroke(width = 1.dp.toPx()),
        )

        // Nub.
        drawRoundRect(
            color = tint.copy(alpha = 0.38f),
            topLeft = Offset(bodyWidth + 1.dp.toPx(), size.height * 0.32f),
            size = Size(nub + 1.dp.toPx(), size.height * 0.36f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(nub, nub),
        )

        val inset = 2f.dp.toPx()
        val fillWidth = (bodyWidth - inset * 2) * (percent.coerceIn(0, 100) / 100f)
        if (fillWidth > 0f) {
            drawRoundRect(
                color = when {
                    charging -> positive
                    percent <= 20 -> warning
                    else -> tint
                },
                topLeft = Offset(inset, inset),
                size = Size(fillWidth, size.height - inset * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 0.7f, radius * 0.7f),
            )
        }

        if (charging) drawBolt(tint = Color.White, bodyWidth = bodyWidth)
    }
}

/** The charging bolt, centred in the battery body. */
private fun DrawScope.drawBolt(tint: Color, bodyWidth: Float) {
    val h = size.height * 0.72f
    val w = h * 0.46f
    val left = (bodyWidth - w) / 2f
    val top = (size.height - h) / 2f
    val path = Path().apply {
        moveTo(left + w * 0.62f, top)
        lineTo(left + w * 0.12f, top + h * 0.56f)
        lineTo(left + w * 0.46f, top + h * 0.56f)
        lineTo(left + w * 0.34f, top + h)
        lineTo(left + w * 0.94f, top + h * 0.40f)
        lineTo(left + w * 0.56f, top + h * 0.40f)
        close()
    }
    drawPath(path, tint)
}

/** Four ascending bars; filled ones are opaque, the rest are ghosted. */
@Composable
private fun CellularBars(active: Int, tint: Color) {
    Canvas(Modifier.size(width = 18.dp, height = 12.dp)) {
        val gap = size.width * 0.12f
        val barWidth = (size.width - gap * 3) / 4f
        for (i in 0 until 4) {
            val fraction = 0.36f + 0.213f * i
            val barHeight = size.height * fraction
            drawRoundRect(
                color = if (i < active) tint else tint.copy(alpha = 0.3f),
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.35f, barWidth * 0.35f),
            )
        }
    }
}

/** Three nested arcs and a dot. */
@Composable
private fun WifiGlyph(tint: Color) {
    Canvas(Modifier.size(width = 17.dp, height = 12.dp)) {
        val cx = size.width / 2f
        val cy = size.height * 0.95f
        val stroke = 1.6f.dp.toPx()

        for (i in 0 until 3) {
            val radius = size.width * (0.20f + 0.19f * i)
            drawArc(
                color = tint,
                startAngle = 210f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        drawCircle(tint, radius = stroke * 0.85f, center = Offset(cx, cy - stroke * 0.4f))
    }
}

@Immutable
private data class BatteryState(val percent: Int = 100, val charging: Boolean = false)

@Immutable
private data class ConnectivityState(val wifi: Boolean = false, val cellularBars: Int = 4)

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
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
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
private fun rememberBattery(): State<BatteryState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(BatteryState()) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level >= 0 && scale > 0) {
                    state.value = BatteryState(
                        percent = level * 100 / scale,
                        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL,
                    )
                }
            }
        }
        // ACTION_BATTERY_CHANGED is sticky, so registering also returns the
        // current value immediately.
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

@Composable
private fun rememberConnectivity(): State<ConnectivityState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(ConnectivityState()) }

    DisposableEffect(Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            return@DisposableEffect onDispose { }
        }

        fun publish() {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            state.value = ConnectivityState(
                wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                // Without READ_PHONE_STATE the real strength is unavailable, so
                // this shows "connected" rather than inventing a number.
                cellularBars = if (
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                ) 4 else 1,
            )
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = publish()
        }

        manager.registerDefaultNetworkCallback(callback)
        publish()
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return state
}
