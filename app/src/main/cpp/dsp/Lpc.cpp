#include "Lpc.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "../core/FastMath.h"

namespace kolan {

void Lpc::init(int order, size_t frameSize) {
    order_ = order;
    frameSize_ = frameSize;

    window_.resize(frameSize);
    for (size_t i = 0; i < frameSize; ++i) {
        window_[i] = 0.5f - 0.5f * std::cos(kTwoPi * static_cast<float>(i) /
                                            static_cast<float>(frameSize - 1));
    }

    // Gaussian lag window: multiplying the autocorrelation by a slowly decaying envelope is
    // equivalent to smearing the spectrum by a few Hz, which broadens the poles just enough to
    // stop them ringing.
    lagWindow_.resize(static_cast<size_t>(order) + 1);
    const float bandwidthHz = 60.0f;
    const float sampleRate = 48000.0f;
    for (int i = 0; i <= order; ++i) {
        const float x = kPi * bandwidthHz * static_cast<float>(i) / sampleRate;
        lagWindow_[static_cast<size_t>(i)] = std::exp(-0.5f * x * x);
    }

    windowed_.assign(frameSize, 0.0f);
    autocorr_.assign(static_cast<size_t>(order) + 1, 0.0f);
    a_.assign(static_cast<size_t>(order) + 1, 0.0f);
    tmp_.assign(static_cast<size_t>(order) + 1, 0.0f);
    a_[0] = 1.0f;
}

void Lpc::analyze(const float* frame) {
    const size_t n = frameSize_;
    for (size_t i = 0; i < n; ++i) {
        windowed_[i] = frame[i] * window_[i];
    }

    for (int lag = 0; lag <= order_; ++lag) {
        float sum = 0.0f;
        for (size_t i = static_cast<size_t>(lag); i < n; ++i) {
            sum += windowed_[i] * windowed_[i - static_cast<size_t>(lag)];
        }
        autocorr_[static_cast<size_t>(lag)] = sum * lagWindow_[static_cast<size_t>(lag)];
    }

    // Ridge term: without it, a fully silent or perfectly periodic frame produces a singular
    // system and the recursion divides by zero.
    autocorr_[0] = autocorr_[0] * 1.0001f + 1.0e-9f;

    std::fill(a_.begin(), a_.end(), 0.0f);
    a_[0] = 1.0f;
    float error = autocorr_[0];

    for (int i = 1; i <= order_; ++i) {
        float acc = autocorr_[static_cast<size_t>(i)];
        for (int j = 1; j < i; ++j) {
            acc += a_[static_cast<size_t>(j)] * autocorr_[static_cast<size_t>(i - j)];
        }
        float k = -acc / error;
        // Reflection coefficients outside the unit circle mean an unstable synthesis filter.
        k = clampf(k, -0.999f, 0.999f);

        std::copy(a_.begin(), a_.begin() + i, tmp_.begin());
        for (int j = 1; j < i; ++j) {
            a_[static_cast<size_t>(j)] = tmp_[static_cast<size_t>(j)] +
                                         k * tmp_[static_cast<size_t>(i - j)];
        }
        a_[static_cast<size_t>(i)] = k;
        error *= (1.0f - k * k);
        if (error < 1.0e-12f) error = 1.0e-12f;
    }

    gain_ = std::sqrt(error);
}

void CrossfadedFir::init(size_t maxTaps, size_t maxBlock) {
    current_.assign(maxTaps, 0.0f);
    previous_.assign(maxTaps, 0.0f);
    pending_.assign(maxTaps, 0.0f);

    size_t cap = 64;
    while (cap < maxTaps + maxBlock + 4) cap <<= 1;
    history_.assign(cap, 0.0f);
    historyMask_ = cap - 1;
    reset();
}

void CrossfadedFir::reset() {
    std::fill(history_.begin(), history_.end(), 0.0f);
    std::fill(current_.begin(), current_.end(), 0.0f);
    std::fill(previous_.begin(), previous_.end(), 0.0f);
    historyPos_ = 0;
    taps_ = 0;
    prevTaps_ = 0;
    primed_ = false;
}

void CrossfadedFir::commit(size_t taps) {
    if (primed_) {
        std::copy(current_.begin(), current_.begin() + static_cast<long>(taps_), previous_.begin());
        prevTaps_ = taps_;
    } else {
        std::copy(pending_.begin(), pending_.begin() + static_cast<long>(taps), previous_.begin());
        prevTaps_ = taps;
        primed_ = true;
    }
    std::copy(pending_.begin(), pending_.begin() + static_cast<long>(taps), current_.begin());
    taps_ = taps;
}

void CrossfadedFir::process(const float* input, float* output, size_t n) {
    if (taps_ == 0) {
        if (output != input) std::memcpy(output, input, n * sizeof(float));
        return;
    }

    const float invN = n > 1 ? 1.0f / static_cast<float>(n - 1) : 0.0f;

    for (size_t i = 0; i < n; ++i) {
        history_[historyPos_ & historyMask_] = input[i];

        float accNew = 0.0f;
        for (size_t t = 0; t < taps_; ++t) {
            accNew += current_[t] * history_[(historyPos_ - t) & historyMask_];
        }
        float accOld = 0.0f;
        for (size_t t = 0; t < prevTaps_; ++t) {
            accOld += previous_[t] * history_[(historyPos_ - t) & historyMask_];
        }

        const float w = static_cast<float>(i) * invN;
        output[i] = accOld + (accNew - accOld) * w;
        ++historyPos_;
    }
}

}  // namespace kolan
