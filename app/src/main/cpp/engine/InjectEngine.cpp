#include "InjectEngine.h"

#include <algorithm>
#include <cmath>

#include "../core/FastMath.h"

namespace kolan {

namespace {
constexpr float kInt16Scale = 1.0f / 32768.0f;
}

void InjectEngine::ensureConfigured(int sampleRate, size_t frameCount) {
    if (configuredRate_ == sampleRate && frameCount <= configuredFrames_) return;

    configuredRate_ = sampleRate;
    configuredFrames_ = std::max(frameCount, static_cast<size_t>(sampleRate / 50));  // >= 20 ms
    chain_.init(static_cast<float>(sampleRate), configuredFrames_);
    mono_.assign(configuredFrames_, 0.0f);
    processed_.assign(configuredFrames_, 0.0f);
}

void InjectEngine::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (configuredRate_ != 0) chain_.reset();
}

void InjectEngine::processInterleavedInt16(int16_t* samples, size_t frameCount, int channelCount,
                                           int sampleRate) {
    if (samples == nullptr || frameCount == 0 || channelCount <= 0 || sampleRate <= 0) return;

    std::lock_guard<std::mutex> lock(mutex_);
    ensureConfigured(sampleRate, frameCount);

    // Downmix to mono. The chain is monophonic by design, and every voice call is too.
    for (size_t i = 0; i < frameCount; ++i) {
        float sum = 0.0f;
        for (int c = 0; c < channelCount; ++c) {
            sum += static_cast<float>(samples[i * static_cast<size_t>(channelCount) + c]);
        }
        mono_[i] = sum * kInt16Scale / static_cast<float>(channelCount);
    }

    const Params params = params_.snapshot();
    chain_.processBlock(mono_.data(), processed_.data(), frameCount, params);
    meters_.pushBlock(chain_.alignedDry(), processed_.data(), frameCount);

    for (size_t i = 0; i < frameCount; ++i) {
        const float clamped = clampf(processed_[i], -1.0f, 1.0f);
        const auto value = static_cast<int16_t>(std::lround(clamped * 32767.0f));
        for (int c = 0; c < channelCount; ++c) {
            samples[i * static_cast<size_t>(channelCount) + c] = value;
        }
    }
}

}  // namespace kolan
