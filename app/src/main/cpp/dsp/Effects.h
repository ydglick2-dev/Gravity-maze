#pragma once

#include <cstddef>
#include <vector>

#include "../core/FastMath.h"
#include "Biquad.h"

namespace kolan {

/** Ring modulator. Multiplying by a sine puts sum and difference tones either side of every
 *  partial, which destroys harmonic relationships and is what makes the robot preset metallic. */
class RingModulator {
public:
    void init(float sampleRate) { sampleRate_ = sampleRate; phase_ = 0.0f; }
    void reset() { phase_ = 0.0f; }

    /** `depth` 0 leaves the signal alone, 1 is full ring modulation. */
    void process(const float* in, float* out, size_t n, float freqHz, float depth);

private:
    float sampleRate_ = 48000.0f;
    float phase_ = 0.0f;
};

/**
 * Asymmetric waveshaper with 2x oversampling.
 *
 * Distortion generates harmonics above Nyquist that alias down into the audible band as
 * inharmonic grit. Running the shaper at twice the rate and filtering on the way back keeps the
 * demon preset sounding like distortion rather than like a broken codec.
 */
class Waveshaper {
public:
    void init(float sampleRate, size_t maxBlock);
    void reset();

    /** `drive` 0..1. */
    void process(const float* in, float* out, size_t n, float drive);

private:
    Biquad upFilter_;
    Biquad downFilter1_;
    Biquad downFilter2_;
    std::vector<float> oversampled_;
    float previous_ = 0.0f;
};

/**
 * Downward-expanding noise gate with hold and smooth release. Straight muting below a threshold
 * chatters on breath noise; the hold time and the release ramp are what stop that.
 */
class NoiseGate {
public:
    void init(float sampleRate);
    void reset();

    void process(const float* in, float* out, size_t n, float thresholdDb);

private:
    float sampleRate_ = 48000.0f;
    float envelope_ = 0.0f;
    float gain_ = 0.0f;
    int holdCounter_ = 0;
    int holdSamples_ = 0;
    float attackCoeff_ = 0.0f;
    float releaseCoeff_ = 0.0f;
    float detectorCoeff_ = 0.0f;
};

/** Feed-forward compressor with a soft knee, followed by automatic makeup gain. */
class Compressor {
public:
    void init(float sampleRate);
    void reset();

    /** `amount` 0..1 interpolates from untouched to a heavily levelled 8:1 at -30 dBFS. */
    void process(const float* in, float* out, size_t n, float amount);

private:
    float sampleRate_ = 48000.0f;
    float envelopeDb_ = -120.0f;
    float attackCoeff_ = 0.0f;
    float releaseCoeff_ = 0.0f;
};

/**
 * Look-ahead-free soft limiter. The chain can stack pitch shifting, resonance and drive into
 * peaks well over 0 dBFS; this is the last thing before the DAC and it must never let one
 * through, because a clipped sample on a headphone monitor is genuinely painful.
 */
class Limiter {
public:
    void init(float sampleRate);
    void reset();
    void process(const float* in, float* out, size_t n);

private:
    float gain_ = 1.0f;
    float attackCoeff_ = 0.0f;
    float releaseCoeff_ = 0.0f;
};

/** 300-3400 Hz band with a touch of resonance, blended against the dry signal. */
class TelephoneFilter {
public:
    void init(float sampleRate);
    void reset();
    void process(const float* in, float* out, size_t n, float amount);

private:
    Biquad highpass1_;
    Biquad highpass2_;
    Biquad lowpass1_;
    Biquad lowpass2_;
    Biquad presence_;
};

}  // namespace kolan
