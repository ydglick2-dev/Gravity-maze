#pragma once

#include <atomic>

namespace kolan {

/** Indices shared with Kotlin (see NativeEngine.Param). Keep the two lists in lockstep. */
enum ParamId : int {
    kParamPitchSemitones = 0,
    kParamFormantSemitones = 1,
    kParamRingModHz = 2,
    kParamRingModDepth = 3,
    kParamWhisper = 4,
    kParamDrive = 5,
    kParamReverbMix = 6,
    kParamReverbSize = 7,
    kParamTelephone = 8,
    kParamGateThresholdDb = 9,
    kParamCompressorAmount = 10,
    kParamOutputGainDb = 11,
    kParamBypass = 12,
    kParamCount = 13,
};

/** Plain snapshot read once per audio block, so the chain never re-loads atomics per sample. */
struct Params {
    float pitchSemitones = 0.0f;    // -12 .. +12
    float formantSemitones = 0.0f;  // -12 .. +12, independent of pitch
    float ringModHz = 0.0f;         // 20 .. 1000
    float ringModDepth = 0.0f;      // 0 .. 1
    float whisper = 0.0f;           // 0 .. 1, replaces the LPC excitation with noise
    float drive = 0.0f;             // 0 .. 1, waveshaper amount
    float reverbMix = 0.0f;         // 0 .. 1
    float reverbSize = 0.5f;        // 0 .. 1
    float telephone = 0.0f;         // 0 .. 1, 300-3400 Hz bandpass blend
    float gateThresholdDb = -60.0f; // -80 .. 0
    float compressorAmount = 0.0f;  // 0 .. 1
    float outputGainDb = 0.0f;      // -12 .. +12
    bool bypass = false;            // A/B comparison
};

/**
 * The UI thread writes here, the audio thread reads. Every field is a relaxed atomic: we do not
 * need the whole set to update atomically together, because a single block landing with a
 * half-applied preset is inaudible, whereas a mutex on the audio thread is not.
 */
class ParamBlock {
public:
    ParamBlock() {
        set(kParamPitchSemitones, 0.0f);
        set(kParamFormantSemitones, 0.0f);
        set(kParamRingModHz, 0.0f);
        set(kParamRingModDepth, 0.0f);
        set(kParamWhisper, 0.0f);
        set(kParamDrive, 0.0f);
        set(kParamReverbMix, 0.0f);
        set(kParamReverbSize, 0.5f);
        set(kParamTelephone, 0.0f);
        set(kParamGateThresholdDb, -60.0f);
        set(kParamCompressorAmount, 0.0f);
        set(kParamOutputGainDb, 0.0f);
        set(kParamBypass, 0.0f);
    }

    void set(int id, float value) {
        if (id < 0 || id >= kParamCount) return;
        values_[id].store(value, std::memory_order_relaxed);
    }

    float get(int id) const {
        if (id < 0 || id >= kParamCount) return 0.0f;
        return values_[id].load(std::memory_order_relaxed);
    }

    Params snapshot() const {
        Params p;
        p.pitchSemitones = get(kParamPitchSemitones);
        p.formantSemitones = get(kParamFormantSemitones);
        p.ringModHz = get(kParamRingModHz);
        p.ringModDepth = get(kParamRingModDepth);
        p.whisper = get(kParamWhisper);
        p.drive = get(kParamDrive);
        p.reverbMix = get(kParamReverbMix);
        p.reverbSize = get(kParamReverbSize);
        p.telephone = get(kParamTelephone);
        p.gateThresholdDb = get(kParamGateThresholdDb);
        p.compressorAmount = get(kParamCompressorAmount);
        p.outputGainDb = get(kParamOutputGainDb);
        p.bypass = get(kParamBypass) > 0.5f;
        return p;
    }

private:
    std::atomic<float> values_[kParamCount];
};

}  // namespace kolan
