package il.kolan.data

import androidx.annotation.StringRes
import il.kolan.R
import kotlinx.serialization.Serializable

/**
 * A preset is either one of the eight built-ins, whose name comes from a string resource so it
 * follows the device language, or a user-saved copy carrying its own literal name.
 */
sealed interface Preset {
    val id: String
    val iconKey: String
    val params: PresetParams
    val isBuiltIn: Boolean
}

data class BuiltInPreset(
    override val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    override val iconKey: String,
    override val params: PresetParams,
) : Preset {
    override val isBuiltIn: Boolean get() = true
}

@Serializable
data class CustomPreset(
    override val id: String,
    val name: String,
    override val iconKey: String,
    override val params: PresetParams,
) : Preset {
    override val isBuiltIn: Boolean get() = false
}

/**
 * The eight shipped presets.
 *
 * The values are deliberately conservative on formant: pushing pitch and formant the same
 * direction by the same amount is exactly what produces the cartoon chipmunk, so the feminine
 * and child presets move formants by less than pitch, and the deep male preset moves them by
 * less still.
 */
object BuiltInPresets {

    val deepMale = BuiltInPreset(
        id = "builtin.deep_male",
        nameRes = R.string.preset_deep_male,
        descriptionRes = R.string.preset_deep_male_desc,
        iconKey = "deep_male",
        params = PresetParams(
            pitchSemitones = -5f,
            formantSemitones = -3f,
            drive = 0.12f,
            compressorAmount = 0.4f,
            gateThresholdDb = -52f,
            outputGainDb = 1.5f,
        ),
    )

    val female = BuiltInPreset(
        id = "builtin.female",
        nameRes = R.string.preset_female,
        descriptionRes = R.string.preset_female_desc,
        iconKey = "female",
        params = PresetParams(
            pitchSemitones = 4.5f,
            formantSemitones = 3.5f,
            compressorAmount = 0.3f,
            gateThresholdDb = -52f,
        ),
    )

    val child = BuiltInPreset(
        id = "builtin.child",
        nameRes = R.string.preset_child,
        descriptionRes = R.string.preset_child_desc,
        iconKey = "child",
        params = PresetParams(
            pitchSemitones = 7f,
            formantSemitones = 5f,
            compressorAmount = 0.35f,
            gateThresholdDb = -50f,
        ),
    )

    val robot = BuiltInPreset(
        id = "builtin.robot",
        nameRes = R.string.preset_robot,
        descriptionRes = R.string.preset_robot_desc,
        iconKey = "robot",
        params = PresetParams(
            pitchSemitones = 0f,
            formantSemitones = -1f,
            ringModHz = 55f,
            ringModDepth = 0.92f,
            telephone = 0.25f,
            compressorAmount = 0.6f,
            gateThresholdDb = -50f,
            outputGainDb = 2f,
        ),
    )

    val demon = BuiltInPreset(
        id = "builtin.demon",
        nameRes = R.string.preset_demon,
        descriptionRes = R.string.preset_demon_desc,
        iconKey = "demon",
        params = PresetParams(
            pitchSemitones = -8f,
            formantSemitones = -4.5f,
            drive = 0.62f,
            reverbMix = 0.32f,
            reverbSize = 0.75f,
            compressorAmount = 0.5f,
            gateThresholdDb = -48f,
        ),
    )

    val alien = BuiltInPreset(
        id = "builtin.alien",
        nameRes = R.string.preset_alien,
        descriptionRes = R.string.preset_alien_desc,
        iconKey = "alien",
        params = PresetParams(
            pitchSemitones = 3f,
            formantSemitones = 2f,
            ringModHz = 220f,
            ringModDepth = 0.45f,
            reverbMix = 0.5f,
            reverbSize = 0.85f,
            compressorAmount = 0.3f,
            gateThresholdDb = -50f,
        ),
    )

    val oldPhone = BuiltInPreset(
        id = "builtin.old_phone",
        nameRes = R.string.preset_old_phone,
        descriptionRes = R.string.preset_old_phone_desc,
        iconKey = "old_phone",
        params = PresetParams(
            pitchSemitones = 0f,
            formantSemitones = 0f,
            drive = 0.22f,
            telephone = 1f,
            compressorAmount = 0.55f,
            gateThresholdDb = -46f,
            outputGainDb = 2.5f,
        ),
    )

    val whisper = BuiltInPreset(
        id = "builtin.whisper",
        nameRes = R.string.preset_whisper,
        descriptionRes = R.string.preset_whisper_desc,
        iconKey = "whisper",
        params = PresetParams(
            pitchSemitones = 0f,
            formantSemitones = 1f,
            whisper = 1f,
            compressorAmount = 0.45f,
            gateThresholdDb = -44f,
            outputGainDb = 4f,
        ),
    )

    val all: List<BuiltInPreset> =
        listOf(deepMale, female, child, robot, demon, alien, oldPhone, whisper)

    val default: BuiltInPreset = deepMale

    fun byId(id: String): BuiltInPreset? = all.firstOrNull { it.id == id }
}
