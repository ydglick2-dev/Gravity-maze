package com.glassify.launcher.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassify.launcher.R
import com.glassify.launcher.data.GlassifySettings
import com.glassify.launcher.data.LauncherPrefs
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.GlassTier
import com.glassify.launcher.glass.liquidGlass
import com.glassify.launcher.launcher.WallpaperPresets
import com.glassify.launcher.launcher.importWallpaper
import com.glassify.launcher.overlay.OverlayService
import kotlinx.coroutines.launch

/**
 * Settings, in grouped glass cards.
 *
 * The two sliders are the ones worth having: blur and refraction are multipliers
 * on the design system's defaults, so the whole interface stays coherent at any
 * setting instead of letting individual panels drift apart. The quality picker
 * below them exists for battery — the full tier runs a fragment shader over the
 * backdrop on every frame a panel is visible.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { LauncherPrefs(context) }
    val settings by prefs.settings.collectAsStateWithLifecycle(GlassifySettings())

    fun update(block: (GlassifySettings) -> GlassifySettings) {
        scope.launch { prefs.update(block) }
    }

    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            importWallpaper(context, uri)?.let { path ->
                prefs.update { it.copy(wallpaperUri = path) }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().systemBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings),
                        style = GlassTheme.type.largeTitle,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.done),
                        style = GlassTheme.type.headline,
                        color = GlassTheme.colors.accent,
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures { onClose() } },
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_glass)) {
                    SliderRow(
                        label = stringResource(R.string.settings_blur),
                        value = settings.blurStrength,
                        onChange = { update { s -> s.copy(blurStrength = it) } },
                    )
                    SliderRow(
                        label = stringResource(R.string.settings_refraction),
                        value = settings.refractionStrength,
                        onChange = { update { s -> s.copy(refractionStrength = it) } },
                    )

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

            item {
                SettingsGroup(stringResource(R.string.settings_appearance)) {
                    SwitchRow(
                        label = stringResource(R.string.settings_labels),
                        checked = settings.showLabels,
                        onChange = { update { s -> s.copy(showLabels = it) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_dark),
                        checked = settings.darkTheme,
                        onChange = { update { s -> s.copy(darkTheme = it) } },
                    )
                    StepperRow(
                        label = stringResource(R.string.settings_columns),
                        value = settings.columns,
                        range = 3..6,
                        onChange = { update { s -> s.copy(columns = it) } },
                    )
                    StepperRow(
                        label = stringResource(R.string.settings_rows),
                        value = settings.rows,
                        range = 4..8,
                        onChange = { update { s -> s.copy(rows = it) } },
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_wallpaper)) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        WallpaperPresets.names.forEachIndexed { index, _ ->
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(GlassShapes.icon)
                                    .background(WallpaperPresets.brush(index))
                                    .pointerInput(index) {
                                        detectTapGestures {
                                            update { s ->
                                                s.copy(wallpaperPresetIndex = index, wallpaperUri = null)
                                            }
                                        }
                                    }
                            )
                        }
                    }
                    TapRow(
                        label = stringResource(R.string.setup_wallpaper_pick),
                        onClick = {
                            pickWallpaper.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_overlays)) {
                    SwitchRow(
                        label = stringResource(R.string.settings_island),
                        checked = settings.islandEnabled,
                        onChange = { update { s -> s.copy(islandEnabled = it) } },
                    )
                    StepperRow(
                        label = stringResource(R.string.settings_island_offset),
                        value = settings.islandTopOffsetDp,
                        range = 0..40,
                        step = 2,
                        onChange = { update { s -> s.copy(islandTopOffsetDp = it) } },
                    )
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
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_permissions)) {
                    TapRow(
                        label = stringResource(R.string.setup_home_title),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_HOME_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                    )
                    TapRow(
                        label = stringResource(R.string.setup_notifications_title),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                    )
                    TapRow(
                        label = stringResource(R.string.setup_overlay_title),
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            OverlayService.syncWithPermissions(context)
                        },
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_about)) {
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
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
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
                    spec = GlassSpec(blur = 26.dp, refraction = 8.dp, tintAmount = 0.22f),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
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
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = GlassTheme.type.body,
                color = GlassTheme.colors.onGlass,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = GlassTheme.type.footnote,
                color = GlassTheme.colors.onGlassSecondary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            // Below 40% the effect stops reading as glass; above 160% the blur
            // costs more than it adds.
            valueRange = 0.4f..1.6f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = GlassTheme.colors.accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
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
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = GlassTheme.type.body,
            color = GlassTheme.colors.onGlass,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.toString(),
            style = GlassTheme.type.body,
            color = GlassTheme.colors.onGlassSecondary,
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
private fun TapRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = GlassTheme.type.body,
            color = GlassTheme.colors.onGlass,
            modifier = Modifier.weight(1f),
        )
        Text(text = "›", style = GlassTheme.type.title, color = GlassTheme.colors.onGlassTertiary)
    }
}

/** The three glass tiers, with anything the device cannot manage greyed out. */
@Composable
private fun QualityPicker(
    current: GlassTier,
    deviceMax: GlassTier,
    onPick: (GlassTier) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = stringResource(R.string.settings_quality),
            style = GlassTheme.type.body,
            color = GlassTheme.colors.onGlass,
            modifier = Modifier.padding(bottom = 6.dp),
        )
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
                    color = if (available) {
                        GlassTheme.colors.onGlass
                    } else {
                        GlassTheme.colors.onGlassTertiary
                    },
                    modifier = Modifier.weight(1f),
                )
                if (tier == current) {
                    Text(text = "✓", style = GlassTheme.type.body, color = GlassTheme.colors.accent)
                }
            }
        }
    }
}
