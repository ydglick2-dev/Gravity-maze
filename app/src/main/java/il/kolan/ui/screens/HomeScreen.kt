package il.kolan.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.kolan.R
import il.kolan.audio.EngineFailure
import il.kolan.audio.EngineMode
import il.kolan.audio.NativeEngine
import il.kolan.data.BuiltInPreset
import il.kolan.data.CustomPreset
import il.kolan.data.Preset
import il.kolan.ui.components.GradientButton
import il.kolan.ui.components.InfoCard
import il.kolan.ui.components.LatencyBadge
import il.kolan.ui.components.OutlineButton
import il.kolan.ui.components.PresetCard
import il.kolan.ui.components.WaveformCanvas
import il.kolan.ui.presetIcon
import il.kolan.ui.theme.KolanBackground
import il.kolan.ui.theme.KolanCyan
import il.kolan.ui.theme.KolanDanger
import il.kolan.ui.theme.KolanSurface
import il.kolan.ui.theme.KolanTextPrimary
import il.kolan.ui.theme.KolanTextSecondary
import il.kolan.ui.theme.KolanWarning
import kotlinx.coroutines.android.awaitFrame

/**
 * The main screen: preset grid, live visualiser, A/B, and the start control.
 *
 * The A/B button is a press-and-hold rather than a toggle. Comparing raw against processed is
 * something you do momentarily while listening, and a hold makes the comparison end when you
 * stop asking for it.
 */
@Composable
fun HomeScreen(
    presets: List<Preset>,
    selectedPresetId: String,
    engineMode: EngineMode,
    failure: EngineFailure,
    headphonesConnected: Boolean,
    onSelectPreset: (Preset) -> Unit,
    onStart: (EngineMode) -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCall: () -> Unit,
    onOpenVoiceMessage: () -> Unit,
    onBypassChange: (Boolean) -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRunning = engineMode == EngineMode.LIVE_MONITOR || engineMode == EngineMode.LOUDSPEAKER
    var latencyMs by remember { mutableFloatStateOf(0f) }
    var comparing by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            latencyMs = 0f
            return@LaunchedEffect
        }
        while (true) {
            awaitFrame()
            latencyMs = NativeEngine.readMeters().latencyMs
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KolanBackground)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.displaySmall,
                color = KolanTextPrimary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isRunning) {
                    LatencyBadge(
                        latencyMs = latencyMs,
                        label = stringResource(R.string.latency_label),
                        template = stringResource(R.string.latency_badge),
                    )
                }
                IconChip(Icons.Filled.Settings, onOpenSettings)
            }
        }

        RouteIndicator(headphonesConnected)

        Spacer(Modifier.height(12.dp))

        WaveformCanvas(
            isActive = isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.home_presets),
            style = MaterialTheme.typography.titleLarge,
            color = KolanTextPrimary,
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(presets, key = { it.id }) { preset ->
                PresetCard(
                    name = presetName(preset),
                    description = presetDescription(preset),
                    icon = presetIcon(preset.iconKey),
                    selected = preset.id == selectedPresetId,
                    isCustom = !preset.isBuiltIn,
                    customBadge = stringResource(R.string.preset_custom_badge),
                    onClick = { onSelectPreset(preset) },
                )
            }
        }

        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(
                    text = if (isRunning) stringResource(R.string.home_stop)
                    else stringResource(R.string.home_start),
                    icon = if (isRunning) Icons.Filled.Stop else Icons.Filled.Headphones,
                    onClick = {
                        if (isRunning) onStop() else onStart(EngineMode.LIVE_MONITOR)
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlineButton(
                    text = stringResource(R.string.home_edit),
                    icon = Icons.Filled.Tune,
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                )
            }

            AbCompareButton(
                comparing = comparing,
                enabled = isRunning,
                onComparingChange = {
                    comparing = it
                    onBypassChange(it)
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton(
                    text = stringResource(R.string.mode_call),
                    icon = Icons.Filled.Mic,
                    onClick = onOpenCall,
                    tint = KolanCyan,
                    modifier = Modifier.weight(1f),
                )
                OutlineButton(
                    text = stringResource(R.string.mode_message),
                    icon = Icons.Filled.Mic,
                    onClick = onOpenVoiceMessage,
                    tint = KolanCyan,
                    modifier = Modifier.weight(1f),
                )
            }

            OutlineButton(
                text = "${stringResource(R.string.mode_loudspeaker)} · ${stringResource(R.string.mode_experimental)}",
                icon = Icons.Filled.VolumeUp,
                onClick = {
                    if (engineMode == EngineMode.LOUDSPEAKER) onStop()
                    else onStart(EngineMode.LOUDSPEAKER)
                },
                tint = KolanWarning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    when (failure) {
        EngineFailure.NO_HEADPHONES -> FailureDialog(
            title = stringResource(R.string.no_headphones_title),
            body = stringResource(R.string.no_headphones_body),
            confirm = stringResource(R.string.no_headphones_dismiss),
            onDismiss = onDismissFailure,
        )

        EngineFailure.AUDIO_UNAVAILABLE -> FailureDialog(
            title = stringResource(R.string.audio_unavailable_title),
            body = stringResource(R.string.audio_unavailable_body),
            confirm = stringResource(R.string.close),
            onDismiss = onDismissFailure,
        )

        EngineFailure.NONE -> Unit
    }
}

@Composable
private fun presetName(preset: Preset): String = when (preset) {
    is BuiltInPreset -> stringResource(preset.nameRes)
    is CustomPreset -> preset.name
}

@Composable
private fun presetDescription(preset: Preset): String = when (preset) {
    is BuiltInPreset -> stringResource(preset.descriptionRes)
    is CustomPreset -> stringResource(R.string.preset_custom_badge)
}

@Composable
private fun RouteIndicator(headphonesConnected: Boolean) {
    if (headphonesConnected) return
    InfoCard(
        title = stringResource(R.string.no_headphones_title),
        body = stringResource(R.string.headphones_disconnected),
        accent = KolanWarning,
        icon = Icons.Filled.Warning,
    )
}

@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(KolanSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = KolanTextSecondary, modifier = Modifier.size(20.dp))
    }
}

/**
 * Press and hold to hear the raw signal.
 *
 * The dry path is delayed in the engine to match the processed path's latency, so releasing and
 * pressing does not change the timing of what you hear — only the timbre.
 */
@Composable
private fun AbCompareButton(
    comparing: Boolean,
    enabled: Boolean,
    onComparingChange: (Boolean) -> Unit,
) {
    val label = if (comparing) stringResource(R.string.home_ab_raw)
    else stringResource(R.string.home_ab_processed)
    val tint = if (comparing) KolanTextSecondary else KolanCyan

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (comparing) KolanSurface else Color.Transparent)
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .then(
                if (enabled) {
                    Modifier.pointerInputHold(
                        onPress = { onComparingChange(true) },
                        onRelease = { onComparingChange(false) },
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.CompareArrows,
                contentDescription = null,
                tint = if (enabled) tint else KolanTextSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) tint else KolanTextSecondary.copy(alpha = 0.4f),
            )
            Text(
                text = stringResource(R.string.home_ab_hint),
                style = MaterialTheme.typography.labelSmall,
                color = KolanTextSecondary.copy(alpha = if (enabled) 0.8f else 0.3f),
            )
        }
    }
}

@Composable
private fun FailureDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirm, color = KolanCyan) }
        },
        title = { Text(title, color = KolanTextPrimary) },
        text = { Text(body, color = KolanTextSecondary) },
        containerColor = KolanSurface,
        iconContentColor = KolanDanger,
    )
}
