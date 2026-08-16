#pragma once

#include <cstddef>
#include <vector>

#include "../core/FastMath.h"
#include "../core/ParamBlock.h"
#include "Effects.h"
#include "FormantWarp.h"
#include "Freeverb.h"
#include "Lpc.h"
#include "Wsola.h"

namespace kolan {

/**
 * The complete real-time voice chain.
 *
 * Signal order:
 *   noise gate -> LPC source/filter pitch + formant stage -> ring modulator -> waveshaper ->
 *   telephone band -> reverb -> compressor -> output gain -> limiter
 *
 * The pitch and formant stage is where the interesting part happens. The input is inverse
 * filtered by its own LPC predictor, which strips the vocal tract off and leaves a
 * formant-free excitation. WSOLA moves the pitch of that excitation. A fresh vocal tract, with
 * its formants warped by an independent ratio, is then filtered back on. Because the two
 * operations touch different parts of the source/filter model, the pitch and formant sliders do
 * not interact.
 *
 * Every buffer is allocated in init(). processBlock() performs no allocation, takes no locks
 * and makes no JNI calls, so it is safe to call directly from an Oboe callback.
 */
class VoiceChain {
public:
    void init(float sampleRate, size_t maxBlockSize);
    void reset();

    /** `input` and `output` may alias. `n` must not exceed the maxBlockSize passed to init(). */
    void processBlock(const float* input, float* output, size_t n, const Params& params);

    /**
     * Everything except the pitch and formant stage.
     *
     * The voice-message path shifts pitch and formants with the phase vocoder instead, so it
     * runs the rest of the chain through here rather than doing the work twice.
     */
    void processEffectsOnly(const float* input, float* output, size_t n, const Params& params);

    /** The dry signal, delayed to match the processed path. Valid until the next block. */
    const float* alignedDry() const { return dryDelayed_.data(); }

    /** Algorithmic latency contributed by this chain, in milliseconds. */
    float algorithmicLatencyMs() const;

private:
    void runSourceFilterStage(const float* input, float* output, size_t n, const Params& params);
    void runEffects(float* output, size_t n, const Params& params);
    void updateDryDelay(const float* input, size_t n);

    static constexpr int kLpcOrder = 20;
    static constexpr size_t kLpcFrame = 512;
    static constexpr size_t kFormantTaps = 128;
    static constexpr size_t kFormantFftSize = 512;

    float sampleRate_ = 48000.0f;
    size_t maxBlock_ = 1024;

    NoiseGate gate_;
    Lpc lpc_;
    CrossfadedFir inverseFilter_;
    Wsola wsola_;
    FormantWarp formantWarp_;
    CrossfadedFir synthesisFilter_;
    RingModulator ringMod_;
    Waveshaper waveshaper_;
    TelephoneFilter telephone_;
    Freeverb reverb_;
    Compressor compressor_;
    Limiter limiter_;
    SmoothedValue outputGain_;
    Rng rng_;

    std::vector<float> lpcHistory_;
    std::vector<float> scratchA_;
    std::vector<float> scratchB_;
    std::vector<float> dryDelayLine_;
    std::vector<float> dryDelayed_;
    size_t dryWritePos_ = 0;
    size_t dryMask_ = 0;
};

}  // namespace kolan
