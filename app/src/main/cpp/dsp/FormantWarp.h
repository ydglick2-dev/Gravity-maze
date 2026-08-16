#pragma once

#include <cstddef>
#include <vector>

#include "Fft.h"

namespace kolan {

/**
 * Turns a set of LPC coefficients into a warped-envelope FIR.
 *
 * The spectral envelope implied by the predictor is |gain / A(w)|. Shifting formants by a
 * ratio means resampling that envelope along the frequency axis: the new envelope at frequency
 * f is the old one at f / ratio. That warped magnitude response is then converted back into a
 * causal impulse response through the real cepstrum, which yields the minimum-phase filter with
 * exactly that magnitude — minimum phase because it concentrates the energy at the front of the
 * response, keeping the added latency to a handful of samples.
 *
 * This is the piece that separates a genuine voice change from a chipmunk: pitch is moved by
 * WSOLA on the excitation, formants are moved here, and neither drags the other along.
 */
class FormantWarp {
public:
    /** Allocates. `fftSize` must be a power of two; 512 gives ~94 Hz bins at 48 kHz. */
    void init(size_t fftSize, size_t taps);

    size_t taps() const { return taps_; }

    /**
     * Writes `taps()` coefficients of the warped-envelope filter into `out`.
     * `ratio` > 1 moves formants up, < 1 moves them down, 1 reproduces the original envelope.
     *
     * The filter realises the warped version of 1/A(w) with no gain term. That is deliberate:
     * the LPC gain belongs to the synthesis model only when 1/A is driven by a unit-variance
     * excitation, whereas this filter is driven by the true residual, which already carries the
     * signal's level. Folding the gain in as well would attenuate the output by exactly the
     * prediction error, which is a 15-20 dB hole on voiced speech.
     *
     * The consequence worth relying on: at ratio == 1 this filter inverts the analysis exactly,
     * so the stage is unity gain.
     */
    void compute(const float* lpcCoefficients, int order, float ratio, float* out);

private:
    Fft fft_;
    size_t fftSize_ = 512;
    size_t taps_ = 128;

    std::vector<float> re_;
    std::vector<float> im_;
    std::vector<float> envelope_;
    std::vector<float> logMagnitude_;
    std::vector<float> cepstrum_;
    std::vector<float> impulse_;
    std::vector<float> taper_;
};

}  // namespace kolan
