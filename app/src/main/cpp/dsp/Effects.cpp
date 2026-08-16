#include "Effects.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace kolan {

// ---------------------------------------------------------------------------- RingModulator

void RingModulator::process(const float* in, float* out, size_t n, float freqHz, float depth) {
    const float d = clampf(depth, 0.0f, 1.0f);
    if (d < 0.001f || freqHz < 1.0f) {
        if (out != in) std::memcpy(out, in, n * sizeof(float));
        return;
    }

    const float increment = kTwoPi * clampf(freqHz, 1.0f, sampleRate_ * 0.45f) / sampleRate_;
    for (size_t i = 0; i < n; ++i) {
        const float carrier = std::sin(phase_);
        phase_ += increment;
        if (phase_ > kTwoPi) phase_ -= kTwoPi;
        out[i] = in[i] * (1.0f - d + d * carrier);
    }
}

// -------------------------------------------------------------------------------- Waveshaper

void Waveshaper::init(float sampleRate, size_t maxBlock) {
    oversampled_.assign(maxBlock * 2 + 4, 0.0f);
    // Both stages run at the doubled rate; two cascaded lowpasses at 0.45 of the base Nyquist
    // give enough stopband rejection to keep aliasing below the noise floor of a phone mic.
    const float doubleRate = sampleRate * 2.0f;
    upFilter_.setLowpass(doubleRate, sampleRate * 0.45f, 0.707f);
    downFilter1_.setLowpass(doubleRate, sampleRate * 0.45f, 0.541f);
    downFilter2_.setLowpass(doubleRate, sampleRate * 0.45f, 1.307f);
    reset();
}

void Waveshaper::reset() {
    upFilter_.reset();
    downFilter1_.reset();
    downFilter2_.reset();
    previous_ = 0.0f;
    std::fill(oversampled_.begin(), oversampled_.end(), 0.0f);
}

void Waveshaper::process(const float* in, float* out, size_t n, float drive) {
    const float d = clampf(drive, 0.0f, 1.0f);
    if (d < 0.001f) {
        if (out != in) std::memcpy(out, in, n * sizeof(float));
        previous_ = n > 0 ? in[n - 1] : previous_;
        return;
    }

    const float preGain = 1.0f + d * 24.0f;
    // Asymmetry adds even harmonics, which is the difference between "fuzzy" and "throaty".
    const float bias = d * 0.18f;
    const float postGain = 1.0f / (1.0f + d * 2.2f);

    // Upsample by inserting an interpolated sample between each pair, then filter.
    for (size_t i = 0; i < n; ++i) {
        const float prev = (i == 0) ? previous_ : in[i - 1];
        oversampled_[2 * i] = upFilter_.process(0.5f * (prev + in[i]));
        oversampled_[2 * i + 1] = upFilter_.process(in[i]);
    }
    previous_ = n > 0 ? in[n - 1] : previous_;

    const size_t total = n * 2;
    for (size_t i = 0; i < total; ++i) {
        const float x = oversampled_[i] * preGain + bias;
        oversampled_[i] = (fastTanh(x) - fastTanh(bias)) * postGain;
    }

    for (size_t i = 0; i < total; ++i) {
        oversampled_[i] = downFilter2_.process(downFilter1_.process(oversampled_[i]));
    }

    // Decimate by dropping the odd samples; the cascade above has already removed everything
    // that would have folded back.
    for (size_t i = 0; i < n; ++i) {
        out[i] = oversampled_[2 * i + 1] * 2.0f;
    }
}

// --------------------------------------------------------------------------------- NoiseGate

void NoiseGate::init(float sampleRate) {
    sampleRate_ = sampleRate;
    holdSamples_ = static_cast<int>(sampleRate * 0.08f);   // 80 ms hold
    attackCoeff_ = std::exp(-1.0f / (sampleRate * 0.002f));   // 2 ms open
    releaseCoeff_ = std::exp(-1.0f / (sampleRate * 0.120f));  // 120 ms close
    detectorCoeff_ = std::exp(-1.0f / (sampleRate * 0.005f));
    reset();
}

void NoiseGate::reset() {
    envelope_ = 0.0f;
    gain_ = 1.0f;
    holdCounter_ = 0;
}

void NoiseGate::process(const float* in, float* out, size_t n, float thresholdDb) {
    if (thresholdDb <= -79.5f) {
        if (out != in) std::memcpy(out, in, n * sizeof(float));
        return;
    }

    const float threshold = dbToGain(clampf(thresholdDb, -80.0f, 0.0f));
    // Opening a few dB above the closing point stops the gate flapping on a steady breath.
    const float openThreshold = threshold * 1.6f;

    for (size_t i = 0; i < n; ++i) {
        const float rectified = std::fabs(in[i]);
        envelope_ = rectified + (envelope_ - rectified) * detectorCoeff_;
        envelope_ = killDenormal(envelope_);

        float target;
        if (envelope_ > openThreshold) {
            target = 1.0f;
            holdCounter_ = holdSamples_;
        } else if (envelope_ > threshold || holdCounter_ > 0) {
            target = 1.0f;
            if (holdCounter_ > 0) --holdCounter_;
        } else {
            target = 0.0f;
        }

        const float coeff = target > gain_ ? attackCoeff_ : releaseCoeff_;
        gain_ = target + (gain_ - target) * coeff;
        gain_ = killDenormal(gain_);
        out[i] = in[i] * gain_;
    }
}

// -------------------------------------------------------------------------------- Compressor

void Compressor::init(float sampleRate) {
    sampleRate_ = sampleRate;
    attackCoeff_ = std::exp(-1.0f / (sampleRate * 0.005f));   // 5 ms
    releaseCoeff_ = std::exp(-1.0f / (sampleRate * 0.150f));  // 150 ms
    reset();
}

void Compressor::reset() { envelopeDb_ = -120.0f; }

void Compressor::process(const float* in, float* out, size_t n, float amount) {
    const float a = clampf(amount, 0.0f, 1.0f);
    if (a < 0.001f) {
        if (out != in) std::memcpy(out, in, n * sizeof(float));
        return;
    }

    const float thresholdDb = lerp(-6.0f, -30.0f, a);
    const float ratio = lerp(1.2f, 8.0f, a);
    const float kneeDb = 6.0f;
    const float slope = 1.0f - 1.0f / ratio;
    // Makeup restores roughly what the threshold and ratio took away, so turning the knob up
    // sounds like more density instead of less level.
    const float makeup = dbToGain(-thresholdDb * slope * 0.6f);

    for (size_t i = 0; i < n; ++i) {
        const float levelDb = gainToDb(std::fabs(in[i]));
        const float coeff = levelDb > envelopeDb_ ? attackCoeff_ : releaseCoeff_;
        envelopeDb_ = levelDb + (envelopeDb_ - levelDb) * coeff;

        const float over = envelopeDb_ - thresholdDb;
        float reductionDb;
        if (over <= -kneeDb * 0.5f) {
            reductionDb = 0.0f;
        } else if (over >= kneeDb * 0.5f) {
            reductionDb = slope * over;
        } else {
            const float t = over + kneeDb * 0.5f;
            reductionDb = slope * t * t / (2.0f * kneeDb);
        }

        out[i] = in[i] * dbToGain(-reductionDb) * makeup;
    }
}

// ----------------------------------------------------------------------------------- Limiter

void Limiter::init(float sampleRate) {
    attackCoeff_ = std::exp(-1.0f / (sampleRate * 0.0005f));  // 0.5 ms
    releaseCoeff_ = std::exp(-1.0f / (sampleRate * 0.050f));  // 50 ms
    reset();
}

void Limiter::reset() { gain_ = 1.0f; }

void Limiter::process(const float* in, float* out, size_t n) {
    constexpr float kCeiling = 0.97f;
    for (size_t i = 0; i < n; ++i) {
        const float magnitude = std::fabs(in[i]);
        const float target = magnitude > kCeiling ? kCeiling / magnitude : 1.0f;
        const float coeff = target < gain_ ? attackCoeff_ : releaseCoeff_;
        gain_ = target + (gain_ - target) * coeff;
        // A fast transient can still poke past the smoothed gain, so hard-clip as a backstop.
        out[i] = clampf(in[i] * gain_, -1.0f, 1.0f);
    }
}

// --------------------------------------------------------------------------- TelephoneFilter

void TelephoneFilter::init(float sampleRate) {
    // Two cascaded second-order sections either side give a 4th-order Butterworth band, which
    // is close to the 300-3400 Hz channel of an analogue telephone line.
    highpass1_.setHighpass(sampleRate, 300.0f, 0.541f);
    highpass2_.setHighpass(sampleRate, 300.0f, 1.307f);
    lowpass1_.setLowpass(sampleRate, 3400.0f, 0.541f);
    lowpass2_.setLowpass(sampleRate, 3400.0f, 1.307f);
    // A midrange bump is what gives the handset its nasal quality.
    presence_.setPeaking(sampleRate, 1600.0f, 1.1f, 6.0f);
    reset();
}

void TelephoneFilter::reset() {
    highpass1_.reset();
    highpass2_.reset();
    lowpass1_.reset();
    lowpass2_.reset();
    presence_.reset();
}

void TelephoneFilter::process(const float* in, float* out, size_t n, float amount) {
    const float a = clampf(amount, 0.0f, 1.0f);
    if (a < 0.001f) {
        if (out != in) std::memcpy(out, in, n * sizeof(float));
        return;
    }

    for (size_t i = 0; i < n; ++i) {
        float wet = highpass2_.process(highpass1_.process(in[i]));
        wet = lowpass2_.process(lowpass1_.process(wet));
        wet = presence_.process(wet);
        out[i] = lerp(in[i], wet * 1.4f, a);
    }
}

}  // namespace kolan
