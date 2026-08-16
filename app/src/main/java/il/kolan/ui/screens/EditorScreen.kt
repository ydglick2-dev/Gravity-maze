package il.kolan.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.kolan.R
import il.kolan.data.BuiltInPreset
import il.kolan.data.CustomPreset
import il.kolan.data.Preset
import il.kolan.data.PresetParams
import il.kolan.ui.components.BigSlider
import il.kolan.ui.components.GradientButton
import il.kolan.ui.components.OutlineButton
import il.kolan.ui.theme.KolanBackground
import il.kolan.ui.theme.KolanCyan
import il.kolan.ui.theme.KolanDanger
import il.kolan.ui.theme.KolanSurface
import il.kolan.ui.theme.KolanTextPrimary
import il.kolan.ui.theme.KolanTextSecondary
import kotlin.math.roundToInt

/**
 * Parameter editor.
 *
 * Every change is applied to the engine immediately rather than on a confirm step, because the
 * only way to judge a voice setting is to hear it while you move the control.
 */
@Composable
fun EditorScreen(
    preset: Preset,
    params: PresetParams,
    onParamsChange: (PresetParams) -> Unit,
    onSaveAsCustom: (String) -> Unit,
    onRevert: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KolanBackground)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.close),
                    tint = KolanTextPrimary,
                )
            }
            Text(
                text = stringResource(R.string.editor_title),
                style = MaterialTheme.typography.headlineSmall,
                color = KolanTextPrimary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Text(
            text = when (preset) {
                is BuiltInPreset -> stringResource(preset.nameRes)
                is CustomPreset -> preset.name
            },
            style = MaterialTheme.typography.titleLarge,
            color = KolanCyan,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            BigSlider(
                label = stringResource(R.string.editor_pitch),
                hint = stringResource(R.string.editor_pitch_hint),
                valueText = stringResource(R.string.editor_semitones, formatSigned(params.pitchSemitones)),
                value = params.pitchSemitones,
                range = -PresetParams.PITCH_RANGE_SEMITONES..PresetParams.PITCH_RANGE_SEMITONES,
                onValueChange = { onParamsChange(params.copy(pitchSemitones = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_formant),
                hint = stringResource(R.string.editor_formant_hint),
                valueText = stringResource(
                    R.string.editor_semitones,
                    formatSigned(params.formantSemitones),
                ),
                value = params.formantSemitones,
                range = -PresetParams.FORMANT_RANGE_SEMITONES..PresetParams.FORMANT_RANGE_SEMITONES,
                onValueChange = { onParamsChange(params.copy(formantSemitones = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_reverb),
                valueText = stringResource(R.string.editor_percent, (params.reverbMix * 100).roundToInt()),
                value = params.reverbMix,
                range = 0f..1f,
                onValueChange = { onParamsChange(params.copy(reverbMix = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_reverb_size),
                valueText = stringResource(R.string.editor_percent, (params.reverbSize * 100).roundToInt()),
                value = params.reverbSize,
                range = 0f..1f,
                onValueChange = { onParamsChange(params.copy(reverbSize = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_drive),
                valueText = stringResource(R.string.editor_percent, (params.drive * 100).roundToInt()),
                value = params.drive,
                range = 0f..1f,
                onValueChange = { onParamsChange(params.copy(drive = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_ringmod),
                valueText = stringResource(R.string.editor_hz, params.ringModHz.roundToInt()),
                value = params.ringModHz,
                range = 0f..PresetParams.RING_MOD_MAX_HZ,
                onValueChange = { onParamsChange(params.copy(ringModHz = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_ringmod_depth),
                valueText = stringResource(
                    R.string.editor_percent,
                    (params.ringModDepth * 100).roundToInt(),
                ),
                value = params.ringModDepth,
                range = 0f..1f,
                onValueChange = { onParamsChange(params.copy(ringModDepth = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_whisper),
                valueText = stringResource(R.string.editor_percent, (params.whisper * 100).roundToInt()),
                value = params.whisper,
                range = 0f..1f,
                onValueChange = { onParamsChange(params.copy(whisper = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_telephone),
                valueText = stringResource(R.string.editor_percent, (params.telephone * 100).roundToInt()),
                value = params.telephone,
                range = 0f..1f,
                onValueChange = { onParamsChange(params.copy(telephone = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_gate),
                valueText = if (params.gateThresholdDb <= PresetParams.GATE_MIN_DB + 0.5f) {
                    stringResource(R.string.editor_gate_off)
                } else {
                    stringResource(R.string.editor_db, formatSigned(params.gateThresholdDb))
                },
                value = params.gateThresholdDb,
                range = PresetParams.GATE_MIN_DB..PresetParams.GATE_MAX_DB,
                onValueChange = { onParamsChange(params.copy(gateThresholdDb = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_compressor),
                valueText = stringResource(
                    R.string.editor_percent,
                    (params.compressorAmount * 100).roundToInt(),
                ),
                value = params.compressorAmount,
                range = 0f..1f,
                onValueChange = { onParamsChange(params.copy(compressorAmount = it)) },
            )

            BigSlider(
                label = stringResource(R.string.editor_output_gain),
                valueText = stringResource(R.string.editor_db, formatSigned(params.outputGainDb)),
                value = params.outputGainDb,
                range = -PresetParams.OUTPUT_GAIN_RANGE_DB..PresetParams.OUTPUT_GAIN_RANGE_DB,
                onValueChange = { onParamsChange(params.copy(outputGainDb = it)) },
            )

            Spacer(Modifier.height(16.dp))
        }

        Column(
            modifier = Modifier.padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GradientButton(
                text = stringResource(R.string.editor_save),
                icon = Icons.Filled.Save,
                onClick = { showSaveDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton(
                    text = stringResource(R.string.editor_revert),
                    onClick = onRevert,
                    modifier = Modifier.weight(1f),
                )
                if (!preset.isBuiltIn) {
                    OutlineButton(
                        text = stringResource(R.string.editor_delete),
                        icon = Icons.Filled.Delete,
                        onClick = onDelete,
                        tint = KolanDanger,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            initialName = when (preset) {
                is BuiltInPreset -> stringResource(preset.nameRes)
                is CustomPreset -> preset.name
            },
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                showSaveDialog = false
                onSaveAsCustom(name)
            },
        )
    }
}

@Composable
private fun SavePresetDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KolanSurface,
        title = {
            Text(stringResource(R.string.editor_save_dialog_title), color = KolanTextPrimary)
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.editor_save_dialog_name)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = KolanSurface,
                    unfocusedContainerColor = KolanSurface,
                    focusedTextColor = KolanTextPrimary,
                    unfocusedTextColor = KolanTextPrimary,
                    focusedIndicatorColor = KolanCyan,
                    unfocusedIndicatorColor = KolanTextSecondary,
                    focusedLabelColor = KolanCyan,
                    unfocusedLabelColor = KolanTextSecondary,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.ifBlank { initialName }) },
            ) {
                Text(stringResource(R.string.editor_save_confirm), color = KolanCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = KolanTextSecondary)
            }
        },
    )
}

/** Formats a value with an explicit sign, so "-3" and "+3" read as opposites at a glance. */
private fun formatSigned(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    return if (rounded > 0f) "+$text" else text
}
