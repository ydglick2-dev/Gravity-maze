#pragma once

#include <cmath>

#include "../core/FastMath.h"

namespace kolan {

/** Transposed direct form II biquad. Numerically well behaved at low frequencies in float. */
class Biquad {
public:
    void reset() { z1_ = z2_ = 0.0f; }

    void setLowpass(float sampleRate, float freqHz, float q) {
        const float w0 = kTwoPi * clampf(freqHz, 20.0f, sampleRate * 0.49f) / sampleRate;
        const float cw = std::cos(w0), sw = std::sin(w0);
        const float alpha = sw / (2.0f * q);
        const float b0 = (1.0f - cw) * 0.5f, b1 = 1.0f - cw, b2 = b0;
        const float a0 = 1.0f + alpha, a1 = -2.0f * cw, a2 = 1.0f - alpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }

    void setHighpass(float sampleRate, float freqHz, float q) {
        const float w0 = kTwoPi * clampf(freqHz, 20.0f, sampleRate * 0.49f) / sampleRate;
        const float cw = std::cos(w0), sw = std::sin(w0);
        const float alpha = sw / (2.0f * q);
        const float b0 = (1.0f + cw) * 0.5f, b1 = -(1.0f + cw), b2 = b0;
        const float a0 = 1.0f + alpha, a1 = -2.0f * cw, a2 = 1.0f - alpha;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }

    void setPeaking(float sampleRate, float freqHz, float q, float gainDb) {
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w0 = kTwoPi * clampf(freqHz, 20.0f, sampleRate * 0.49f) / sampleRate;
        const float cw = std::cos(w0), sw = std::sin(w0);
        const float alpha = sw / (2.0f * q);
        const float b0 = 1.0f + alpha * A, b1 = -2.0f * cw, b2 = 1.0f - alpha * A;
        const float a0 = 1.0f + alpha / A, a1 = -2.0f * cw, a2 = 1.0f - alpha / A;
        setCoefficients(b0, b1, b2, a0, a1, a2);
    }

    inline float process(float x) {
        const float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        z1_ = killDenormal(z1_);
        z2_ = killDenormal(z2_);
        return y;
    }

    void processBlock(const float* in, float* out, size_t n) {
        for (size_t i = 0; i < n; ++i) out[i] = process(in[i]);
    }

private:
    void setCoefficients(float b0, float b1, float b2, float a0, float a1, float a2) {
        const float inv = 1.0f / a0;
        b0_ = b0 * inv;
        b1_ = b1 * inv;
        b2_ = b2 * inv;
        a1_ = a1 * inv;
        a2_ = a2 * inv;
    }

    float b0_ = 1.0f, b1_ = 0.0f, b2_ = 0.0f, a1_ = 0.0f, a2_ = 0.0f;
    float z1_ = 0.0f, z2_ = 0.0f;
};

}  // namespace kolan
