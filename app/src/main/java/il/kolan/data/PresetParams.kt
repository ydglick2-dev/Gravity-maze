package il.kolan.data

import il.kolan.audio.NativeEngine
import kotlinx.serialization.Serializable

/**
 * The complete state of the voice engine.
 *
 * Pitch and formant are separate on purpose: shifting pitch alone keeps the vocal tract where it
 * is, which is the difference between a voice that sounds like a different person and one that
 * sounds like a sped-up tape.
 */
@Serializable
data class PresetParams(
    /** -12 to +12 semitones. */
    val pitchSemitones: Float = 0f,
    /** -12 to +12 semitones, applied to the spectral envelope only. */
    val formantSemitones: Float = 0f,
    /** Ring modulator carrier, 0 disables it. */
    val ringModHz: Float = 0f,
    val ringModDepth: Float = 0f,
    /** Replaces the excitation with noise; 1 is a full whisper. */
    val whisper: Float = 0f,
    /** Waveshaper amount. */
    val drive: Float = 0f,
    val reverbMix: Float = 0f,
    val reverbSize: Float = 0.5f,
    /** Blend towards a 300-3400 Hz telephone band. */
    val telephone: Float = 0f,
    /** -80 disables the gate. */
    val gateThresholdDb: Float = -55f,
    val compressorAmount: Float = 0.25f,
    val outputGainDb: Float = 0f,
) {
    /** Pushes every value into the native parameter block. Safe to call while audio is running. */
    fun applyToEngine() {
        NativeEngine.setParam(NativeEngine.Param.PITCH_SEMITONES, pitchSemitones)
        NativeEngine.setParam(NativeEngine.Param.FORMANT_SEMITONES, formantSemitones)
        NativeEngine.setParam(NativeEngine.Param.RING_MOD_HZ, ringModHz)
        NativeEngine.setParam(NativeEngine.Param.RING_MOD_DEPTH, ringModDepth)
        NativeEngine.setParam(NativeEngine.Param.WHISPER, whisper)
        NativeEngine.setParam(NativeEngine.Param.DRIVE, drive)
        NativeEngine.setParam(NativeEngine.Param.REVERB_MIX, reverbMix)
        NativeEngine.setParam(NativeEngine.Param.REVERB_SIZE, reverbSize)
        NativeEngine.setParam(NativeEngine.Param.TELEPHONE, telephone)
        NativeEngine.setParam(NativeEngine.Param.GATE_THRESHOLD_DB, gateThresholdDb)
        NativeEngine.setParam(NativeEngine.Param.COMPRESSOR_AMOUNT, compressorAmount)
        NativeEngine.setParam(NativeEngine.Param.OUTPUT_GAIN_DB, outputGainDb)
    }

    companion object {
        const val PITCH_RANGE_SEMITONES = 12f
        const val FORMANT_RANGE_SEMITONES = 12f
        const val RING_MOD_MIN_HZ = 20f
        const val RING_MOD_MAX_HZ = 1000f
        const val GATE_MIN_DB = -80f
        const val GATE_MAX_DB = -20f
        const val OUTPUT_GAIN_RANGE_DB = 12f
    }
}
