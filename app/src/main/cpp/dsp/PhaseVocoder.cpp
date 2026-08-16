#include "PhaseVocoder.h"

#include <algorithm>
#include <cmath>

#include "../core/FastMath.h"

namespace kolan {

namespace {

/** Wraps a phase difference into (-pi, pi]. */
inline float principalArgument(float x) {
    const float wrapped = x - kTwoPi * std::floor(x / kTwoPi + 0.5f);
    return wrapped;
}

// Quefrency cutoff for the envelope lifter. Keeping the first ~50 cepstral coefficients at a
// 2048-point window follows the spectral envelope without tracking individual harmonics.
constexpr size_t kLifterCutoff = 50;

}  // namespace

PhaseVocoder::PhaseVocoder() {
    fft_.init(kFftSize);

    window_.resize(kFftSize);
    for (size_t i = 0; i < kFftSize; ++i) {
        window_[i] = 0.5f - 0.5f * std::cos(kTwoPi * static_cast<float>(i) /
                                            static_cast<float>(kFftSize));
    }

    re_.assign(kFftSize, 0.0f);
    im_.assign(kFftSize, 0.0f);
    magnitude_.assign(kBins, 0.0f);
    phase_.assign(kBins, 0.0f);
    previousPhase_.assign(kBins, 0.0f);
    accumulatedPhase_.assign(kBins, 0.0f);
    envelope_.assign(kBins, 0.0f);
    warpedEnvelope_.assign(kBins, 0.0f);
    cepstrumRe_.assign(kFftSize, 0.0f);
    cepstrumIm_.assign(kFftSize, 0.0f);
    peakOf_.assign(kBins, 0);
    frame_.assign(kFftSize, 0.0f);
}

void PhaseVocoder::computeEnvelope(const float* magnitude, size_t bins) {
    for (size_t k = 0; k < bins; ++k) {
        cepstrumRe_[k] = std::log(magnitude[k] > 1.0e-7f ? magnitude[k] : 1.0e-7f);
        cepstrumIm_[k] = 0.0f;
    }
    for (size_t k = bins; k < kFftSize; ++k) {
        cepstrumRe_[k] = cepstrumRe_[kFftSize - k];
        cepstrumIm_[k] = 0.0f;
    }

    fft_.transform(cepstrumRe_.data(), cepstrumIm_.data(), true);

    // Lifter: zero the high-quefrency half, which is where the harmonic comb lives, leaving the
    // slowly varying part that describes the vocal tract.
    for (size_t q = kLifterCutoff; q < kFftSize - kLifterCutoff; ++q) {
        cepstrumRe_[q] = 0.0f;
        cepstrumIm_[q] = 0.0f;
    }

    fft_.transform(cepstrumRe_.data(), cepstrumIm_.data(), false);
    for (size_t k = 0; k < bins; ++k) {
        envelope_[k] = std::exp(clampf(cepstrumRe_[k], -30.0f, 30.0f));
    }
}

std::vector<float> PhaseVocoder::process(const std::vector<float>& input, float stretch,
                                         float formantRatio, float whisper) {
    if (input.empty()) return {};

    const float safeStretch = clampf(stretch, 0.25f, 4.0f);
    const size_t analysisHop = std::max<size_t>(
        1, static_cast<size_t>(std::lround(static_cast<float>(kSynthesisHop) / safeStretch)));

    // Pad the front by one window so the first samples get full overlap coverage.
    std::vector<float> padded(input.size() + 2 * kFftSize, 0.0f);
    std::copy(input.begin(), input.end(), padded.begin() + kFftSize);

    const size_t frameCount =
        padded.size() > kFftSize ? (padded.size() - kFftSize) / analysisHop : 0;
    const size_t outputLength = frameCount * kSynthesisHop + kFftSize;

    std::vector<float> output(outputLength, 0.0f);
    std::vector<float> windowSum(outputLength, 0.0f);

    std::fill(previousPhase_.begin(), previousPhase_.end(), 0.0f);
    std::fill(accumulatedPhase_.begin(), accumulatedPhase_.end(), 0.0f);

    const float warp = clampf(formantRatio, 0.25f, 4.0f);
    const bool warpNeeded = std::fabs(warp - 1.0f) > 0.001f;
    const float whisperAmount = clampf(whisper, 0.0f, 1.0f);
    Rng rng(0x5EED1234u);

    for (size_t f = 0; f < frameCount; ++f) {
        const size_t start = f * analysisHop;

        for (size_t i = 0; i < kFftSize; ++i) {
            frame_[i] = padded[start + i] * window_[i];
        }
        fft_.forwardReal(frame_.data(), re_.data(), im_.data());

        for (size_t k = 0; k < kBins; ++k) {
            magnitude_[k] = std::sqrt(re_[k] * re_[k] + im_[k] * im_[k]);
            phase_[k] = std::atan2(im_[k], re_[k]);
        }

        // Map every bin to the peak whose region of influence it falls in. Bins between two
        // peaks belong to the nearer one.
        {
            int lastPeak = 0;
            for (size_t k = 0; k < kBins; ++k) peakOf_[k] = -1;
            for (size_t k = 2; k + 2 < kBins; ++k) {
                if (magnitude_[k] > magnitude_[k - 1] && magnitude_[k] > magnitude_[k - 2] &&
                    magnitude_[k] > magnitude_[k + 1] && magnitude_[k] > magnitude_[k + 2]) {
                    peakOf_[k] = static_cast<int>(k);
                    lastPeak = static_cast<int>(k);
                }
            }
            (void)lastPeak;
            int current = -1;
            int nextPeak = -1;
            for (size_t k = 0; k < kBins; ++k) {
                if (peakOf_[k] >= 0) {
                    current = peakOf_[k];
                    // Look ahead for the following peak so the midpoint can be the boundary.
                    nextPeak = -1;
                    for (size_t j = k + 1; j < kBins; ++j) {
                        if (peakOf_[j] >= 0) {
                            nextPeak = peakOf_[j];
                            break;
                        }
                    }
                } else if (current >= 0) {
                    const int boundary =
                        nextPeak >= 0 ? (current + nextPeak) / 2 : static_cast<int>(kBins);
                    peakOf_[k] = (static_cast<int>(k) <= boundary) ? current : nextPeak;
                    if (peakOf_[k] < 0) peakOf_[k] = current;
                } else {
                    peakOf_[k] = 0;
                }
            }
        }

        // Advance the phase of each bin by its measured instantaneous frequency times the
        // synthesis hop.
        for (size_t k = 0; k < kBins; ++k) {
            const float omega = kTwoPi * static_cast<float>(k) / static_cast<float>(kFftSize);
            const float expected = omega * static_cast<float>(analysisHop);
            const float deviation =
                principalArgument(phase_[k] - previousPhase_[k] - expected);
            const float instantaneous = omega + deviation / static_cast<float>(analysisHop);
            accumulatedPhase_[k] += instantaneous * static_cast<float>(kSynthesisHop);
        }

        if (warpNeeded) {
            computeEnvelope(magnitude_.data(), kBins);
            const float inverse = 1.0f / warp;
            for (size_t k = 0; k < kBins; ++k) {
                const float src = static_cast<float>(k) * inverse;
                float value;
                if (src >= static_cast<float>(kBins - 1)) {
                    value = envelope_[kBins - 1];
                } else {
                    const size_t i0 = static_cast<size_t>(src);
                    value = lerp(envelope_[i0], envelope_[i0 + 1], src - static_cast<float>(i0));
                }
                warpedEnvelope_[k] = value;
            }
            for (size_t k = 0; k < kBins; ++k) {
                magnitude_[k] *= warpedEnvelope_[k] / (envelope_[k] + 1.0e-9f);
            }
        }

        for (size_t k = 0; k < kBins; ++k) {
            float outputPhase;
            if (whisperAmount > 0.001f) {
                // Randomising the phase destroys the harmonic structure while leaving the
                // spectral envelope intact, which is exactly what a whisper is.
                const float random = rng.nextUnipolar() * kTwoPi;
                outputPhase = lerp(accumulatedPhase_[k], random, whisperAmount);
            } else {
                const int peak = peakOf_[k];
                outputPhase = accumulatedPhase_[static_cast<size_t>(peak)] +
                              (phase_[k] - phase_[static_cast<size_t>(peak)]);
            }
            re_[k] = magnitude_[k] * std::cos(outputPhase);
            im_[k] = magnitude_[k] * std::sin(outputPhase);
        }

        fft_.inverseReal(re_.data(), im_.data(), frame_.data());

        const size_t outStart = f * kSynthesisHop;
        for (size_t i = 0; i < kFftSize; ++i) {
            output[outStart + i] += frame_[i] * window_[i];
            windowSum[outStart + i] += window_[i] * window_[i];
        }

        std::copy(phase_.begin(), phase_.end(), previousPhase_.begin());
    }

    // Normalise by the accumulated window energy rather than assuming a constant-overlap-add
    // factor: it stays exact for any hop and cleans up the ramps at both ends.
    for (size_t i = 0; i < outputLength; ++i) {
        if (windowSum[i] > 1.0e-6f) output[i] /= windowSum[i];
    }

    // Drop the padding that was added at the front, scaled to the stretched timeline.
    const size_t trim = static_cast<size_t>(static_cast<float>(kFftSize) * safeStretch);
    if (trim >= output.size()) return {};
    const size_t wanted = static_cast<size_t>(static_cast<float>(input.size()) * safeStretch);
    const size_t available = output.size() - trim;
    const size_t length = std::min(wanted, available);
    return std::vector<float>(output.begin() + static_cast<long>(trim),
                              output.begin() + static_cast<long>(trim + length));
}

}  // namespace kolan
