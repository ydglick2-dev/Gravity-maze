#include "FormantWarp.h"

#include <algorithm>
#include <cmath>

#include "../core/FastMath.h"

namespace kolan {

void FormantWarp::init(size_t fftSize, size_t taps) {
    fftSize_ = fftSize;
    taps_ = taps;
    fft_.init(fftSize);

    re_.assign(fftSize, 0.0f);
    im_.assign(fftSize, 0.0f);
    envelope_.assign(fftSize / 2 + 1, 0.0f);
    logMagnitude_.assign(fftSize, 0.0f);
    cepstrum_.assign(fftSize, 0.0f);
    impulse_.assign(fftSize, 0.0f);

    // Half-Hann taper over the tail of the truncated impulse response. Cutting it off square
    // would ripple the magnitude response by a couple of dB across the band.
    taper_.assign(taps, 1.0f);
    const size_t fadeStart = taps * 3 / 4;
    for (size_t i = fadeStart; i < taps; ++i) {
        const float t = static_cast<float>(i - fadeStart) / static_cast<float>(taps - fadeStart);
        taper_[i] = 0.5f + 0.5f * std::cos(kPi * t);
    }
}

void FormantWarp::compute(const float* lpcCoefficients, int order, float ratio, float* out) {
    const size_t n = fftSize_;
    const size_t half = n / 2;

    // 1. Frequency response of the predictor A(w).
    std::fill(re_.begin(), re_.end(), 0.0f);
    std::fill(im_.begin(), im_.end(), 0.0f);
    for (int i = 0; i <= order && static_cast<size_t>(i) < n; ++i) {
        re_[static_cast<size_t>(i)] = lpcCoefficients[i];
    }
    fft_.transform(re_.data(), im_.data(), false);

    // 2. Envelope magnitude |1 / A(w)| on the non-negative half of the spectrum.
    for (size_t k = 0; k <= half; ++k) {
        const float mag = std::sqrt(re_[k] * re_[k] + im_[k] * im_[k]);
        envelope_[k] = 1.0f / (mag + 1.0e-9f);
    }

    // 3. Warp along frequency. The new envelope at bin k is the old one at k / ratio, with
    //    linear interpolation between bins and a clamp at the Nyquist edge.
    const float safeRatio = clampf(ratio, 0.25f, 4.0f);
    const float invRatio = 1.0f / safeRatio;
    for (size_t k = 0; k <= half; ++k) {
        const float src = static_cast<float>(k) * invRatio;
        float value;
        if (src >= static_cast<float>(half)) {
            value = envelope_[half];
        } else {
            const size_t i0 = static_cast<size_t>(src);
            const float frac = src - static_cast<float>(i0);
            const size_t i1 = i0 + 1 <= half ? i0 + 1 : half;
            value = lerp(envelope_[i0], envelope_[i1], frac);
        }
        // Floor the magnitude before taking the log so a null in the envelope cannot produce
        // a -inf that poisons the cepstrum.
        logMagnitude_[k] = std::log(value > 1.0e-7f ? value : 1.0e-7f);
    }
    for (size_t k = half + 1; k < n; ++k) {
        logMagnitude_[k] = logMagnitude_[n - k];
    }

    // 4. Real cepstrum, then fold it to the minimum-phase complex cepstrum: double the causal
    //    part, drop the anti-causal part, leave DC and Nyquist alone.
    std::copy(logMagnitude_.begin(), logMagnitude_.end(), re_.begin());
    std::fill(im_.begin(), im_.end(), 0.0f);
    fft_.transform(re_.data(), im_.data(), true);
    std::copy(re_.begin(), re_.end(), cepstrum_.begin());

    re_[0] = cepstrum_[0];
    im_[0] = 0.0f;
    for (size_t i = 1; i < half; ++i) {
        re_[i] = 2.0f * cepstrum_[i];
        im_[i] = 0.0f;
    }
    re_[half] = cepstrum_[half];
    im_[half] = 0.0f;
    for (size_t i = half + 1; i < n; ++i) {
        re_[i] = 0.0f;
        im_[i] = 0.0f;
    }

    // 5. exp() of the folded cepstrum's spectrum gives the minimum-phase transfer function.
    fft_.transform(re_.data(), im_.data(), false);
    for (size_t i = 0; i < n; ++i) {
        const float magnitude = std::exp(clampf(re_[i], -30.0f, 30.0f));
        const float phase = im_[i];
        re_[i] = magnitude * std::cos(phase);
        im_[i] = magnitude * std::sin(phase);
    }

    // 6. Back to the time domain and truncate to the tap count.
    fft_.transform(re_.data(), im_.data(), true);
    for (size_t i = 0; i < taps_; ++i) {
        out[i] = re_[i] * taper_[i];
    }
}

}  // namespace kolan
