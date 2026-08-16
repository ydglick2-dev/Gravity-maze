#include "OfflineEngine.h"

#include <algorithm>
#include <cmath>

#include "../core/FastMath.h"
#include "../dsp/PhaseVocoder.h"
#include "../dsp/Resampler.h"
#include "../dsp/VoiceChain.h"

namespace kolan {

std::vector<float> OfflineEngine::process(const std::vector<float>& input, float sampleRate,
                                          const Params& params) {
    if (input.empty()) return {};

    const float pitchRatio = semitonesToRatio(clampf(params.pitchSemitones, -12.0f, 12.0f));
    const float formantRatio = semitonesToRatio(clampf(params.formantSemitones, -12.0f, 12.0f));
    const float whisper = clampf(params.whisper, 0.0f, 1.0f);

    std::vector<float> shifted;
    const bool pitchIsUnity = std::fabs(pitchRatio - 1.0f) < 0.0005f;
    const bool formantIsUnity = std::fabs(formantRatio - 1.0f) < 0.0005f;

    if (pitchIsUnity && formantIsUnity && whisper < 0.001f) {
        shifted = input;
    } else {
        PhaseVocoder vocoder;
        // Resampling by pitchRatio afterwards multiplies the formants by pitchRatio too, so the
        // envelope warp applied here has to divide it back out.
        const float envelopeWarp = formantRatio / pitchRatio;
        std::vector<float> stretched = vocoder.process(input, pitchRatio, envelopeWarp, whisper);
        shifted = pitchIsUnity ? std::move(stretched) : resampleHermite(stretched, pitchRatio);

        // Rounding through two resampling stages can leave the result a few samples short or
        // long; pin it to the original duration so the exported file matches the recording.
        if (shifted.size() != input.size()) {
            shifted = resampleToLength(shifted, input.size());
        }
    }

    // Run the remainder of the chain — gate, ring modulator, drive, telephone, reverb,
    // compressor, limiter — using the same code the live path uses.
    constexpr size_t kBlock = 480;
    VoiceChain chain;
    chain.init(sampleRate, kBlock);

    std::vector<float> output(shifted.size(), 0.0f);
    size_t i = 0;
    for (; i + kBlock <= shifted.size(); i += kBlock) {
        chain.processEffectsOnly(shifted.data() + i, output.data() + i, kBlock, params);
    }
    if (i < shifted.size()) {
        chain.processEffectsOnly(shifted.data() + i, output.data() + i, shifted.size() - i, params);
    }

    return output;
}

}  // namespace kolan
