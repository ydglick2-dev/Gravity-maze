package com.glassify.launcher.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassify.launcher.CrashReporter
import com.glassify.launcher.R
import com.glassify.launcher.data.AppRepository
import com.glassify.launcher.data.GlassifySettings
import com.glassify.launcher.data.LaunchableApp
import com.glassify.launcher.data.LauncherPrefs
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.GlassTier
import com.glassify.launcher.glass.liquidGlass
import com.glassify.launcher.notifications.NotificationRepository
import com.glassify.launcher.overlay.HomeWatcher
import com.glassify.launcher.overlay.OverlayService
import kotlinx.coroutines.launch

/**
 * The whole app, in one screen.
 *
 * Setup and settings are the same list rather than a wizard followed by a
 * settings screen. Everything here is a permission the user grants in Android's
 * own settings and can revoke at any time, so a one-way wizard would be lying
 * about the shape of the thing: the state has to be re-read and re-shown every
 * time they come back, which is exactly what a live list does.
 */
@Composable
fun ControlPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { LauncherPrefs(context) }
    val settings by prefs.settings.collectAsStateWithLifecycle(GlassifySettings())

    // Permissions are granted in another app, so the only reliable moment to
    // re-read them is when we come back to the foreground.
    val permissionEpoch = rememberResumeCounter()
    val hasOverlay = remember(permissionEpoch) { OverlayService.canDrawOverlays(context) }
    val hasNotifications = remember(permissionEpoch) { NotificationRepository.hasAccess(context) }
    val hasHomeWatcher = remember(permissionEpoch) { HomeWatcher.isEnabled(context) }
    val batteryExempt = remember(permissionEpoch) { isBatteryExempt(context) }
    val lastCrash = remember(permissionEpoch) { CrashReporter.lastCrash(context) }

    fun update(block: (GlassifySettings) -> GlassifySettings) {
        scope.launch { prefs.update(block) }
    }

    // Starting the service is driven from the settings rather than from the
    // activity's lifecycle, so that resuming this screen while paused does not
    // start what the user just switched off.
    androidx.compose.runtime.LaunchedEffect(settings.overlaysPaused, hasOverlay) {
        if (!settings.overlaysPaused && hasOverlay) OverlayService.syncWithPermissions(context)
    }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = GlassTheme.type.largeTitle,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.panel_subtitle),
                    style = GlassTheme.type.callout,
                    color = GlassTheme.colors.onGlassSecondary,
                )
            }
        }

        item {
            // First, because a user who has just tapped the off switch opens
            // this screen looking for exactly one thing.
            Group(
                if (settings.overlaysPaused) {
                    stringResource(R.string.panel_paused_title)
                } else {
                    stringResource(R.string.panel_running)
                }
            ) {
                SwitchRow(
                    label = stringResource(R.string.panel_running),
                    checked = !settings.overlaysPaused,
                    onChange = { on ->
                        update { s -> s.copy(overlaysPaused = !on) }
                        if (on) OverlayService.syncWithPermissions(context)
                    },
                )
                if (settings.overlaysPaused) {
                    Text(
                        text = stringResource(R.string.panel_paused_body),
                        style = GlassTheme.type.footnote,
                        color = GlassTheme.colors.onGlassSecondary,
                    )
                }

                Divider()

                TapRow(
                    label = stringResource(R.string.panel_add_stop_icon),
                    body = stringResource(R.string.panel_add_stop_icon_hint),
                    onClick = { pinStopShortcut(context) },
                )
            }
        }

        item {
            Group(stringResource(R.string.settings_permissions)) {
                PermissionRow(
                    title = stringResource(R.string.setup_overlay_title),
                    body = stringResource(R.string.setup_overlay_body),
                    granted = hasOverlay,
                    onGrant = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    },
                )
                PermissionRow(
                    title = stringResource(R.string.setup_home_watcher_title),
                    body = stringResource(R.string.setup_home_watcher_body),
                    granted = hasHomeWatcher,
                    onGrant = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
                PermissionRow(
                    title = stringResource(R.string.setup_notifications_title),
                    body = stringResource(R.string.setup_notifications_body),
                    granted = hasNotifications,
                    onGrant = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    },
                )
                PermissionRow(
                    title = stringResource(R.string.setup_battery_title),
                    body = stringResource(R.string.setup_battery_body),
                    footnote = stringResource(R.string.setup_battery_hint),
                    granted = batteryExempt,
                    onGrant = {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    },
                )
            }
        }

        item {
            Group(stringResource(R.string.panel_layers)) {
                SwitchRow(
                    label = stringResource(R.string.settings_island),
                    checked = settings.islandEnabled,
                    onChange = { update { s -> s.copy(islandEnabled = it) } },
                )
                StepperRow(
                    label = stringResource(R.string.settings_island_offset),
                    value = settings.islandTopOffsetDp,
                    range = 0..48,
                    step = 2,
                    onChange = { update { s -> s.copy(islandTopOffsetDp = it) } },
                )

                Divider()

                SwitchRow(
                    label = stringResource(R.string.panel_dock),
                    checked = settings.dockEnabled,
                    onChange = { update { s -> s.copy(dockEnabled = it) } },
                )
                StepperRow(
                    label = stringResource(R.string.panel_dock_offset),
                    value = settings.dockBottomOffsetDp,
                    range = 0..160,
                    step = 4,
                    onChange = { update { s -> s.copy(dockBottomOffsetDp = it) } },
                )

                Divider()

                SwitchRow(
                    label = stringResource(R.string.panel_clock),
                    checked = settings.clockEnabled,
                    onChange = { update { s -> s.copy(clockEnabled = it) } },
                )
                StepperRow(
                    label = stringResource(R.string.panel_clock_offset),
                    value = settings.clockTopOffsetDp,
                    range = 0..400,
                    step = 10,
                    onChange = { update { s -> s.copy(clockTopOffsetDp = it) } },
                )

                Divider()

                SwitchRow(
                    label = stringResource(R.string.settings_notification_center),
                    checked = settings.notificationCenterEnabled,
                    onChange = { update { s -> s.copy(notificationCenterEnabled = it) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_edge_trigger),
                    checked = settings.edgeTriggerEnabled,
                    onChange = { update { s -> s.copy(edgeTriggerEnabled = it) } },
                )
                Text(
                    text = stringResource(R.string.settings_edge_trigger_note),
                    style = GlassTheme.type.footnote,
                    color = GlassTheme.colors.onGlassTertiary,
                )
            }
        }

        item {
            Group(stringResource(R.string.panel_dock_apps)) {
                DockPicker(
                    chosen = settings.dockKeys,
                    onChange = { keys -> update { s -> s.copy(dockKeys = keys) } },
                )
            }
        }

        item {
            Group(stringResource(R.string.settings_glass)) {
                val deviceMax = remember { GlassTier.deviceMax(context) }
                QualityPicker(
                    current = settings.resolveTier(deviceMax),
                    deviceMax = deviceMax,
                    onPick = { tier ->
                        update { s -> s.copy(tierOverride = tier.takeIf { it != deviceMax }) }
                    },
                )
            }
        }

        if (lastCrash != null) {
            item {
                Group(stringResource(R.string.panel_last_crash)) {
                    Text(
                        text = lastCrash.take(MAX_TRACE_CHARS),
                        style = GlassTheme.type.footnote,
                        color = Color(0xFFFF9F9F),
                    )
                    Spacer(Modifier.height(10.dp))
                    val shareLabel = stringResource(R.string.safe_mode_share)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallButton(shareLabel) {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, lastCrash)
                                    },
                                    shareLabel,
                                )
                            )
                        }
                        SmallButton(stringResource(R.string.panel_clear_crash)) {
                            CrashReporter.clear(context)
                        }
                    }
                }
            }
        }

        item {
            Group(stringResource(R.string.settings_about)) {
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = GlassTheme.type.footnote,
                    color = GlassTheme.colors.onGlassSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_licenses),
                    style = GlassTheme.type.footnote,
                    color = GlassTheme.colors.onGlassTertiary,
                )
            }
        }
    }
}

/**
 * Picks which apps sit in the dock.
 *
 * Order is the tap order, so the list doubles as the arrangement: tapping an app
 * appends it, tapping it again removes it. Four slots, like the iOS dock.
 */
@Composable
private fun DockPicker(chosen: List<String>, onChange: (List<String>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AppRepository(context, scope) }
    val apps by repository.apps.collectAsStateWithLifecycle()

    DisposableEffect(repository) {
        repository.start()
        onDispose { repository.stop() }
    }

    Text(
        text = stringResource(R.string.panel_dock_apps_hint),
        style = GlassTheme.type.footnote,
        color = GlassTheme.colors.onGlassTertiary,
        modifier = Modifier.padding(bottom = 10.dp),
    )

    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(apps, key = { it.key }) { app ->
            val index = chosen.indexOf(app.key)
            DockCandidate(
                app = app,
                position = if (index >= 0) index + 1 else null,
                onToggle = {
                    onChange(
                        when {
                            index >= 0 -> chosen - app.key
                            chosen.size >= 4 -> chosen.drop(1) + app.key
                            else -> chosen + app.key
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun DockCandidate(app: LaunchableApp, position: Int?, onToggle: () -> Unit) {
    Column(
        Modifier
            .width(64.dp)
            .pointerInput(app.key) { detectTapGestures { onToggle() } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Box(Modifier.size(52.dp).clip(GlassShapes.icon)) {
                app.icon?.let {
                    Image(
                        bitmap = it,
                        contentDescription = app.label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            // The number is the slot, which is more use than a tick: it shows
            // where the app will land as well as that it is selected.
            position?.let {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(GlassTheme.colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = it.toString(),
                        style = GlassTheme.type.caption,
                        color = Color.White,
                    )
                }
            }
        }
        Text(
            text = app.label,
            style = GlassTheme.type.caption,
            color = if (position != null) Color.White else GlassTheme.colors.onGlassTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = GlassTheme.type.caption,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 18.dp, bottom = 7.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(
                    shape = GlassShapes.panel,
                    cornerRadius = 28.dp,
                    spec = GlassSpec(surfaceAlpha = 0.10f),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    onGrant: () -> Unit,
    footnote: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = GlassTheme.type.body,
                color = GlassTheme.colors.onGlass,
                modifier = Modifier.weight(1f),
            )
            if (granted) {
                Text(
                    text = "✓",
                    style = GlassTheme.type.title,
                    color = GlassTheme.colors.positive,
                )
            } else {
                SmallButton(stringResource(R.string.setup_grant), onGrant)
            }
        }
        Text(
            text = body,
            style = GlassTheme.type.footnote,
            color = GlassTheme.colors.onGlassSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        footnote?.let {
            Text(
                text = it,
                style = GlassTheme.type.footnote,
                color = GlassTheme.colors.onGlassTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TapRow(label: String, body: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(vertical = 8.dp),
    ) {
        Text(text = label, style = GlassTheme.type.body, color = GlassTheme.colors.accent)
        Text(
            text = body,
            style = GlassTheme.type.footnote,
            color = GlassTheme.colors.onGlassSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Asks the launcher to place the off switch on the home screen.
 *
 * Only a request: the launcher decides, and some refuse outright, which is why
 * the activity is also an ordinary entry in the app list as a fallback.
 */
private fun pinStopShortcut(context: Context) {
    val manager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
    if (manager == null || !manager.isRequestPinShortcutSupported) {
        Toast.makeText(context, R.string.panel_add_stop_icon_unsupported, Toast.LENGTH_LONG).show()
        return
    }

    val shortcut = android.content.pm.ShortcutInfo.Builder(context, "glassify-stop")
        .setShortLabel(context.getString(R.string.stop_label))
        .setIcon(android.graphics.drawable.Icon.createWithResource(context, R.mipmap.ic_stop))
        .setIntent(
            Intent(context, com.glassify.launcher.StopActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
        )
        .build()

    manager.requestPinShortcut(shortcut, null)
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(GlassShapes.pill)
            .background(GlassTheme.colors.accent)
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(text = label, style = GlassTheme.type.callout, color = Color.White)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = GlassTheme.type.body,
            color = GlassTheme.colors.onGlass,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = GlassTheme.colors.positive,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.85f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = GlassTheme.type.callout,
            color = GlassTheme.colors.onGlassSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.toString(),
            style = GlassTheme.type.callout,
            color = GlassTheme.colors.onGlass,
            modifier = Modifier.padding(end = 10.dp),
        )
        StepperButton("−") { onChange((value - step).coerceIn(range.first, range.last)) }
        Spacer(Modifier.width(8.dp))
        StepperButton("+") { onChange((value + step).coerceIn(range.first, range.last)) }
    }
}

@Composable
private fun StepperButton(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = GlassTheme.type.headline, color = Color.White)
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(vertical = 0.dp)
            .background(GlassTheme.colors.separator)
    )
}

/** The three glass tiers, with anything the device cannot manage greyed out. */
@Composable
private fun QualityPicker(current: GlassTier, deviceMax: GlassTier, onPick: (GlassTier) -> Unit) {
    listOf(
        GlassTier.FULL to R.string.settings_quality_full,
        GlassTier.BLUR to R.string.settings_quality_blur,
        GlassTier.FLAT to R.string.settings_quality_flat,
    ).forEach { (tier, labelRes) ->
        val available = tier.ordinal >= deviceMax.ordinal
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(tier, available) {
                    if (available) detectTapGestures { onPick(tier) }
                }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(labelRes),
                style = GlassTheme.type.callout,
                color = if (available) GlassTheme.colors.onGlass else GlassTheme.colors.onGlassTertiary,
                modifier = Modifier.weight(1f),
            )
            if (tier == current) {
                Text(text = "✓", style = GlassTheme.type.body, color = GlassTheme.colors.accent)
            }
        }
    }
}

/** Increments every time the screen comes back to the foreground. */
@Composable
private fun rememberResumeCounter(): Int {
    var epoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) epoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return epoch
}

private fun isBatteryExempt(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/** Enough of a trace to identify the fault without turning the screen into a log. */
private const val MAX_TRACE_CHARS = 2000
