#pragma once

#include <cstddef>
#include <vector>

#include "Fft.h"

namespace kolan {

/**
 * Phase vocoder with peak-based phase locking and cepstral formant warping.
 *
 * This is the quality path, used for voice messages where the whole recording is in memory and
 * the 2048-sample window costs nothing but CPU. A plain phase vocoder lets each bin's phase
 * drift independently, which smears transients and gives the characteristic "phasiness";
 * locking every bin to the phase of the spectral peak that dominates it (Laroche and Dolson's
 * identity phase locking) keeps the partials of each harmonic moving together.
 *
 * Formants are handled separately from pitch: the spectral envelope is estimated by liftering
 * the real cepstrum, warped along the frequency axis, and divided back in. Since the pitch
 * shift is completed by resampling — which drags formants along with it — the warp applied here
 * is the target formant ratio divided by the pitch ratio.
 */
class PhaseVocoder {
public:
    PhaseVocoder();

    /** Time-stretches by `stretch` while warping the spectral envelope by `formantRatio`. */
    std::vector<float> process(const std::vector<float>& input, float stretch, float formantRatio,
                               float whisper);

private:
    void computeEnvelope(const float* magnitude, size_t bins);

    static constexpr size_t kFftSize = 2048;
    static constexpr size_t kSynthesisHop = 256;
    static constexpr size_t kBins = kFftSize / 2 + 1;

    Fft fft_;
    std::vector<float> window_;

    std::vector<float> re_, im_;
    std::vector<float> magnitude_, phase_;
    std::vector<float> previousPhase_, accumulatedPhase_;
    std::vector<float> envelope_, warpedEnvelope_;
    std::vector<float> cepstrumRe_, cepstrumIm_;
    std::vector<int> peakOf_;
    std::vector<float> frame_;
};

}  // namespace kolan
