package com.glassify.launcher.notifications

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.glassify.launcher.R
import com.glassify.launcher.glass.GlassMotion
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.liquidGlass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The Notification Centre panel.
 *
 * Runs in an overlay window over whatever app is in front, so there is no
 * backdrop to sample — the system blurs behind the window instead and the glass
 * here is the surface-only variant. See `OverlayWindows.panel`.
 */
@Composable
fun NotificationCenter(
    notifications: List<GlassNotification>,
    onDismiss: (String) -> Unit,
    onDismissAll: () -> Unit,
    onOpen: (GlassNotification) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(Modifier.fillMaxSize()) {
            ClockHeader()

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(notifications, key = { it.key }) { notification ->
                    NotificationCard(
                        notification = notification,
                        onDismiss = { onDismiss(notification.key) },
                        onOpen = { onOpen(notification) },
                    )
                }

                if (notifications.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 60.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.no_notifications),
                                style = GlassTheme.type.callout,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }

            if (notifications.any { it.clearable }) {
                Box(
                    Modifier.fillMaxWidth().padding(bottom = 26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.clear_all),
                        style = GlassTheme.type.callout,
                        color = Color.White,
                        modifier = Modifier
                            .liquidGlass(shape = GlassShapes.pill, cornerRadius = 999.dp)
                            .pointerInput(Unit) { detectTapGestures { onDismissAll() } }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * The active locale, read through the configuration so a language change
 * recomposes instead of leaving stale text on screen.
 */
@Composable
private fun rememberLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
    }
}

/** Oversized clock and date, the way iOS heads its notification list. */
@Composable
private fun ClockHeader() {
    val locale = rememberLocale()
    val now = remember { Date() }
    val time = remember(locale) { SimpleDateFormat("H:mm", locale).format(now) }
    val date = remember(locale) { SimpleDateFormat("EEEE, d MMMM", locale).format(now) }

    Column(
        Modifier.fillMaxWidth().padding(top = 58.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = date, style = GlassTheme.type.callout, color = Color.White.copy(alpha = 0.7f))
        Text(text = time, style = GlassTheme.type.clock, color = Color.White)
    }
}

/**
 * One notification card.
 *
 * Swiping it sideways dismisses it. The card follows the finger and only commits
 * past a threshold, so a hesitant swipe springs back rather than deleting
 * something the user was only inspecting.
 */
@Composable
private fun NotificationCard(
    notification: GlassNotification,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    var offset by remember(notification.key) { mutableFloatStateOf(0f) }
    var dismissed by remember(notification.key) { mutableStateOf(false) }
    val animated by animateFloatAsState(
        targetValue = if (dismissed) DISMISS_TRAVEL_PX else offset,
        animationSpec = GlassMotion.fluid(),
        label = "swipe",
    )
    val context = LocalContext.current
    val locale = rememberLocale()

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = animated
                alpha = (1f - abs(animated) / DISMISS_TRAVEL_PX).coerceIn(0f, 1f)
            }
            .liquidGlass(
                shape = GlassShapes.card,
                cornerRadius = 22.dp,
                spec = GlassSpec(surfaceAlpha = 0.62f),
            )
            .pointerInput(notification.key) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(offset) > size.width * DISMISS_FRACTION && notification.clearable) {
                            dismissed = true
                            onDismiss()
                        } else {
                            offset = 0f
                        }
                    },
                    onDragCancel = { offset = 0f },
                ) { change, amount ->
                    change.consume()
                    offset += amount
                }
            }
            .pointerInput(notification.key) {
                detectTapGestures {
                    notification.contentIntent?.let {
                        try {
                            it.send()
                        } catch (e: android.app.PendingIntent.CanceledException) {
                            // The posting app withdrew the intent.
                        }
                    }
                    onOpen()
                }
            }
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AppGlyph(notification, context)

        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = notification.appLabel.uppercase(locale),
                    style = GlassTheme.type.caption,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = relativeTime(notification.whenMillis),
                    style = GlassTheme.type.caption,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
            if (notification.title.isNotBlank()) {
                Text(
                    text = notification.title,
                    style = GlassTheme.type.footnote,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (notification.text.isNotBlank()) {
                Text(
                    text = notification.text,
                    style = GlassTheme.type.footnote,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The posting app's own icon, falling back to its initial. */
@Composable
private fun AppGlyph(notification: GlassNotification, context: android.content.Context) {
    val locale = rememberLocale()
    val icon = remember(notification.packageName) {
        try {
            context.packageManager.getApplicationIcon(notification.packageName)
        } catch (e: Exception) {
            null
        }
    }

    Box(
        Modifier
            .size(38.dp)
            .clip(GlassShapes.icon)
            .background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.graphics.painter.BitmapPainter(
                    remember(icon) { icon.toImageBitmap() }
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            Text(
                text = notification.appLabel.take(1).uppercase(locale),
                style = GlassTheme.type.footnote,
                color = Color.White,
            )
        }
    }
}

private fun android.graphics.drawable.Drawable.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val size = 96
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bitmap.asImageBitmap()
}

/** "now", "5m", "2h", then a clock time — the iOS ladder. */
private fun relativeTime(millis: Long): String {
    if (millis <= 0) return ""
    val delta = System.currentTimeMillis() - millis
    return when {
        delta < 60_000 -> "now"
        delta < 3_600_000 -> "${delta / 60_000}m"
        delta < 86_400_000 -> "${delta / 3_600_000}h"
        else -> {
            val calendar = Calendar.getInstance().apply { timeInMillis = millis }
            SimpleDateFormat("d MMM", Locale.getDefault()).format(calendar.time)
        }
    }
}

private const val DISMISS_TRAVEL_PX = 900f
private const val DISMISS_FRACTION = 0.32f
