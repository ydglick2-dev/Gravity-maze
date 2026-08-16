#pragma once

#include <cstddef>
#include <vector>

namespace kolan {

/**
 * WSOLA pitch shifter: a delay line read at a fractional rate, with waveform-similarity
 * splices that keep the read head from drifting into or away from the write head.
 *
 * Reading the delay line at rate `r` shifts pitch by `r` but would consume input `r` times
 * faster than it arrives. The splices put that back: when the read head gets too close to the
 * write head it jumps backward by one waveform period (repeating material), and when it falls
 * too far behind it jumps forward (skipping). The jump distance is chosen by maximising the
 * normalised cross-correlation between the material either side of the splice, and the two
 * sides are crossfaded, which is what keeps the joins from clicking.
 *
 * At r == 1 no splice ever fires, so the path is bit-transparent apart from the interpolator.
 *
 * Everything is preallocated in init(); process() is allocation- and lock-free.
 */
class Wsola {
public:
    void init(float sampleRate);
    void reset();

    /** Pitch ratio, e.g. 2.0 for one octave up. Clamped to [0.25, 4.0]. */
    void setRatio(float ratio) { ratio_ = ratio < 0.25f ? 0.25f : (ratio > 4.0f ? 4.0f : ratio); }

    /** Pushes `n` input samples and pulls `n` output samples. In-place safe. */
    void process(const float* input, float* output, size_t n);

    /** Current read-head lag in samples, i.e. the algorithmic latency this stage contributes. */
    float latencySamples() const { return static_cast<float>(lastLag_); }

    /** Mid-point of the lag band. Used to time-align the dry signal for A/B comparison. */
    float nominalLatencySamples() const {
        return static_cast<float>(lagMin_ + lagMax_) * 0.5f;
    }

private:
    float readInterpolated(double pos) const;
    float readAt(long index) const { return line_[static_cast<size_t>(index) & mask_]; }
    /** Returns the best splice distance in samples, or 0 when no candidate is usable. */
    long findBestSplice(long basePos, long dMin, long dMax, int direction) const;

    std::vector<float> line_;
    size_t mask_ = 0;

    long writePos_ = 0;
    double readPos_ = 0.0;
    long lastLag_ = 0;

    // Crossfade state for an in-flight splice.
    double fadeReadPos_ = 0.0;
    int fadeRemaining_ = 0;

    float ratio_ = 1.0f;
    float sampleRate_ = 48000.0f;

    long lagMin_ = 0;
    long lagMax_ = 0;
    long spliceMin_ = 0;
    long spliceMax_ = 0;
    int crossfade_ = 0;
    int corrLen_ = 0;
};

}  // namespace kolan
