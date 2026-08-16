#include "Freeverb.h"

#include <algorithm>
#include <cstring>

#include "../core/FastMath.h"

namespace kolan {

namespace {
// Freeverb's original delay lengths, in samples at 44.1 kHz.
constexpr int kCombTuning[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllpassTuning[4] = {556, 441, 341, 225};
}  // namespace

float Freeverb::Comb::process(float input, float feedback, float damp) {
    const float output = buffer[index];
    filterState = output * (1.0f - damp) + filterState * damp;
    filterState = killDenormal(filterState);
    buffer[index] = killDenormal(input + filterState * feedback);
    if (++index >= buffer.size()) index = 0;
    return output;
}

void Freeverb::Comb::reset() {
    std::fill(buffer.begin(), buffer.end(), 0.0f);
    index = 0;
    filterState = 0.0f;
}

float Freeverb::Allpass::process(float input, float feedback) {
    const float buffered = buffer[index];
    const float output = -input + buffered;
    buffer[index] = killDenormal(input + buffered * feedback);
    if (++index >= buffer.size()) index = 0;
    return output;
}

void Freeverb::Allpass::reset() {
    std::fill(buffer.begin(), buffer.end(), 0.0f);
    index = 0;
}

void Freeverb::init(float sampleRate) {
    const float scale = sampleRate / 44100.0f;
    for (int i = 0; i < 8; ++i) {
        const size_t length = static_cast<size_t>(static_cast<float>(kCombTuning[i]) * scale);
        combs_[i].buffer.assign(length > 1 ? length : 1, 0.0f);
    }
    for (int i = 0; i < 4; ++i) {
        const size_t length = static_cast<size_t>(static_cast<float>(kAllpassTuning[i]) * scale);
        allpasses_[i].buffer.assign(length > 1 ? length : 1, 0.0f);
    }
    reset();
}

void Freeverb::reset() {
    for (auto& comb : combs_) comb.reset();
    for (auto& allpass : allpasses_) allpass.reset();
}

void Freeverb::process(const float* in, float* out, size_t n, float size, float mix) {
    const float wet = clampf(mix, 0.0f, 1.0f);
    if (wet < 0.001f) {
        if (out != in) std::memcpy(out, in, n * sizeof(float));
        return;
    }

    const float feedback = lerp(0.70f, 0.94f, clampf(size, 0.0f, 1.0f));
    const float damp = 0.35f;
    const float dryGain = 1.0f - wet * 0.5f;
    // Freeverb's own input scaling; without it the comb bank saturates on loud input.
    constexpr float kInputGain = 0.015f;

    for (size_t i = 0; i < n; ++i) {
        const float input = in[i] * kInputGain;
        float accumulated = 0.0f;
        for (auto& comb : combs_) {
            accumulated += comb.process(input, feedback, damp);
        }
        for (auto& allpass : allpasses_) {
            accumulated = allpass.process(accumulated, 0.5f);
        }
        out[i] = in[i] * dryGain + accumulated * wet * 3.0f;
    }
}

}  // namespace kolan
