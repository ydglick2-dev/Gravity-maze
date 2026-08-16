#pragma once

#include <cmath>
#include <cstdint>

#if defined(__SSE2__) || defined(__x86_64__)
#include <xmmintrin.h>
#include <pmmintrin.h>
#endif

namespace kolan {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 6.28318530717958647692f;

/**
 * Denormal floats cost hundreds of cycles on some CPUs, and reverb tails plus IIR filters
 * produce them constantly as they decay to silence. Flushing them to zero at the top of every
 * audio callback keeps a decaying Freeverb from turning into an xrun generator.
 */
inline void enableFlushDenormals() {
#if defined(__SSE2__) || defined(__x86_64__)
    _MM_SET_FLUSH_ZERO_MODE(_MM_FLUSH_ZERO_ON);
    _MM_SET_DENORMALS_ZERO_MODE(_MM_DENORMALS_ZERO_ON);
#elif defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24);  // FZ
    asm volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__)
    uint32_t fpscr;
    asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1U << 24);  // FZ
    asm volatile("vmsr fpscr, %0" : : "r"(fpscr));
#endif
}

/** Belt-and-braces denormal kill for the few places that need it regardless of FPU state. */
inline float killDenormal(float x) {
    return (std::fabs(x) < 1.0e-25f) ? 0.0f : x;
}

inline float clampf(float x, float lo, float hi) {
    return x < lo ? lo : (x > hi ? hi : x);
}

/** Rational tanh approximation. Max error ~2e-4 over [-4, 4], monotonic, and branch-free. */
inline float fastTanh(float x) {
    if (x < -4.0f) return -1.0f;
    if (x > 4.0f) return 1.0f;
    const float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

/** Converts semitones to a frequency ratio. */
inline float semitonesToRatio(float semitones) {
    return std::pow(2.0f, semitones / 12.0f);
}

/** Linear interpolation. */
inline float lerp(float a, float b, float t) { return a + (b - a) * t; }

/** dB to linear gain. */
inline float dbToGain(float db) { return std::pow(10.0f, db * 0.05f); }

/** Linear gain to dB, floored so silence maps to -120 instead of -inf. */
inline float gainToDb(float gain) {
    return gain <= 1.0e-6f ? -120.0f : 20.0f * std::log10(gain);
}

/**
 * One-pole smoother for parameters that would otherwise click when the UI moves a slider.
 * `setTimeConstant` takes the time to reach ~63% of a step.
 */
class SmoothedValue {
public:
    void configure(float sampleRate, float seconds) {
        coeff_ = std::exp(-1.0f / (sampleRate * (seconds > 1.0e-5f ? seconds : 1.0e-5f)));
    }
    void reset(float value) { current_ = value; }
    float next(float target) {
        current_ = target + (current_ - target) * coeff_;
        return killDenormal(current_);
    }
    float current() const { return current_; }

private:
    float coeff_ = 0.99f;
    float current_ = 0.0f;
};

/** xorshift32. Deterministic, allocation-free, and fast enough for whisper/noise excitation. */
class Rng {
public:
    explicit Rng(uint32_t seed = 0x9E3779B9u) : state_(seed ? seed : 1u) {}

    uint32_t nextUint() {
        state_ ^= state_ << 13;
        state_ ^= state_ >> 17;
        state_ ^= state_ << 5;
        return state_;
    }

    /** Uniform in [-1, 1). */
    float nextBipolar() {
        return static_cast<float>(static_cast<int32_t>(nextUint())) * (1.0f / 2147483648.0f);
    }

    /** Uniform in [0, 1). */
    float nextUnipolar() { return static_cast<float>(nextUint()) * (1.0f / 4294967296.0f); }

private:
    uint32_t state_;
};

}  // namespace kolan
