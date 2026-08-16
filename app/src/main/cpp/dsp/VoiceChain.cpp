#include "VoiceChain.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace kolan {

void VoiceChain::init(float sampleRate, size_t maxBlockSize) {
    sampleRate_ = sampleRate;
    maxBlock_ = maxBlockSize;

    gate_.init(sampleRate);
    lpc_.init(kLpcOrder, kLpcFrame);
    inverseFilter_.init(static_cast<size_t>(kLpcOrder) + 1, maxBlockSize);
    wsola_.init(sampleRate);
    formantWarp_.init(kFormantFftSize, kFormantTaps);
    synthesisFilter_.init(kFormantTaps, maxBlockSize);
    ringMod_.init(sampleRate);
    waveshaper_.init(sampleRate, maxBlockSize);
    telephone_.init(sampleRate);
    reverb_.init(sampleRate);
    compressor_.init(sampleRate);
    limiter_.init(sampleRate);
    outputGain_.configure(sampleRate, 0.02f);

    lpcHistory_.assign(kLpcFrame, 0.0f);
    scratchA_.assign(maxBlockSize, 0.0f);
    scratchB_.assign(maxBlockSize + kFormantTaps, 0.0f);
    dryDelayed_.assign(maxBlockSize, 0.0f);

    size_t cap = 1024;
    while (cap < maxBlockSize + 4096) cap <<= 1;
    dryDelayLine_.assign(cap, 0.0f);
    dryMask_ = cap - 1;

    reset();
}

void VoiceChain::reset() {
    gate_.reset();
    inverseFilter_.reset();
    wsola_.reset();
    synthesisFilter_.reset();
    ringMod_.reset();
    waveshaper_.reset();
    telephone_.reset();
    reverb_.reset();
    compressor_.reset();
    limiter_.reset();
    outputGain_.reset(1.0f);

    std::fill(lpcHistory_.begin(), lpcHistory_.end(), 0.0f);
    std::fill(dryDelayLine_.begin(), dryDelayLine_.end(), 0.0f);
    std::fill(dryDelayed_.begin(), dryDelayed_.end(), 0.0f);
    dryWritePos_ = 0;
}

float VoiceChain::algorithmicLatencyMs() const {
    // The WSOLA read-head lag dominates. The minimum-phase formant filter concentrates its
    // energy in the first few taps, so a sixteenth of the tap count is a fair estimate of its
    // group delay, and the LPC analysis adds none because it only produces coefficients.
    const float samples = wsola_.latencySamples() + static_cast<float>(kFormantTaps) / 16.0f;
    return samples * 1000.0f / sampleRate_;
}

void VoiceChain::updateDryDelay(const float* input, size_t n) {
    const size_t delay = static_cast<size_t>(wsola_.nominalLatencySamples());
    for (size_t i = 0; i < n; ++i) {
        dryDelayLine_[(dryWritePos_ + i) & dryMask_] = input[i];
    }
    dryWritePos_ += n;
    for (size_t i = 0; i < n; ++i) {
        const size_t index = dryWritePos_ - n + i - delay;
        dryDelayed_[i] = dryDelayLine_[index & dryMask_];
    }
}

void VoiceChain::runSourceFilterStage(const float* input, float* output, size_t n,
                                      const Params& params) {
    const float pitchRatio = semitonesToRatio(clampf(params.pitchSemitones, -12.0f, 12.0f));
    const float formantRatio = semitonesToRatio(clampf(params.formantSemitones, -12.0f, 12.0f));
    const float whisper = clampf(params.whisper, 0.0f, 1.0f);

    const bool pitchIsUnity = std::fabs(pitchRatio - 1.0f) < 0.0005f;
    const bool formantIsUnity = std::fabs(formantRatio - 1.0f) < 0.0005f;
    if (pitchIsUnity && formantIsUnity && whisper < 0.001f) {
        // Nothing to do in this stage; keep WSOLA's delay line fed so that turning a slider
        // does not restart it from silence.
        wsola_.setRatio(1.0f);
        wsola_.process(input, output, n);
        return;
    }

    // 1. Refresh the LPC analysis from the most recent kLpcFrame samples.
    if (n >= kLpcFrame) {
        std::copy(input + (n - kLpcFrame), input + n, lpcHistory_.begin());
    } else {
        std::memmove(lpcHistory_.data(), lpcHistory_.data() + n, (kLpcFrame - n) * sizeof(float));
        std::copy(input, input + n, lpcHistory_.begin() + static_cast<long>(kLpcFrame - n));
    }
    lpc_.analyze(lpcHistory_.data());

    // 2. Inverse filter to the excitation. A(z) is FIR, so the coefficients go in directly.
    const float* a = lpc_.coefficients();
    float* inversePending = inverseFilter_.pending();
    for (int i = 0; i <= kLpcOrder; ++i) {
        inversePending[i] = a[i];
    }
    inverseFilter_.commit(static_cast<size_t>(kLpcOrder) + 1);
    inverseFilter_.process(input, scratchA_.data(), n);

    // 3. Whisper: swap the excitation for noise at matched energy. The vocal tract filter is
    //    applied unchanged below, which is precisely what distinguishes a whisper from noise.
    if (whisper > 0.001f) {
        float energy = 0.0f;
        for (size_t i = 0; i < n; ++i) energy += scratchA_[i] * scratchA_[i];
        const float rms = std::sqrt(energy / static_cast<float>(n > 0 ? n : 1));
        for (size_t i = 0; i < n; ++i) {
            scratchA_[i] = lerp(scratchA_[i], rng_.nextBipolar() * rms * 1.732f, whisper);
        }
    }

    // 4. Move the pitch of the excitation. Formants are not in this signal, so they stay put.
    wsola_.setRatio(pitchRatio);
    wsola_.process(scratchA_.data(), scratchA_.data(), n);

    // 5. Put a vocal tract back on, with its envelope warped by the formant ratio.
    float* synthPending = synthesisFilter_.pending();
    formantWarp_.compute(a, kLpcOrder, formantRatio, synthPending);
    synthesisFilter_.commit(kFormantTaps);
    synthesisFilter_.process(scratchA_.data(), output, n);
}

void VoiceChain::processBlock(const float* input, float* output, size_t n, const Params& params) {
    if (n == 0) return;
    if (n > maxBlock_) n = maxBlock_;

    updateDryDelay(input, n);

    // Gate first: everything downstream adds gain, and gating after the compressor would just
    // amplify the room tone before removing it.
    gate_.process(input, scratchB_.data(), n, params.gateThresholdDb);

    runSourceFilterStage(scratchB_.data(), output, n, params);

    ringMod_.process(output, output, n, params.ringModHz, params.ringModDepth);
    waveshaper_.process(output, output, n, params.drive);
    telephone_.process(output, output, n, params.telephone);
    reverb_.process(output, output, n, params.reverbSize, params.reverbMix);
    compressor_.process(output, output, n, params.compressorAmount);

    const float targetGain = dbToGain(clampf(params.outputGainDb, -24.0f, 24.0f));
    for (size_t i = 0; i < n; ++i) {
        output[i] *= outputGain_.next(targetGain);
    }

    limiter_.process(output, output, n);

    // A/B: the processed path always runs so that switching is instantaneous and the effect
    // states stay warm, and the dry signal is delayed to match so the comparison is fair.
    if (params.bypass) {
        std::memcpy(output, dryDelayed_.data(), n * sizeof(float));
    }
}

}  // namespace kolan
