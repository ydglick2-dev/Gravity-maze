#pragma once

#include <cstddef>
#include <vector>

namespace kolan {

/**
 * Linear-prediction analysis: autocorrelation plus Levinson-Durbin recursion.
 *
 * The predictor A(z) = 1 + a1 z^-1 + ... + ap z^-p models the vocal tract. Filtering speech
 * through A(z) leaves a formant-free excitation; filtering an excitation through 1/A(z) puts a
 * vocal tract back on. That separation is what lets pitch and formants move independently.
 */
class Lpc {
public:
    /** Allocates. `order` is the predictor order, 20 is a good fit for 48 kHz speech. */
    void init(int order, size_t frameSize);

    int order() const { return order_; }

    /** Analysis gain from the last analyze() call, i.e. sqrt of the residual energy. */
    float gain() const { return gain_; }

    /** Coefficients a[0..order], with a[0] == 1. Valid until the next analyze(). */
    const float* coefficients() const { return a_.data(); }

    /**
     * Runs analysis over `frameSize` samples. Applies a Hann window, a Gaussian lag window and
     * a small ridge term, which together keep the recursion from producing the razor-sharp
     * near-unstable poles that make LPC resynthesis whistle.
     */
    void analyze(const float* frame);

private:
    int order_ = 20;
    size_t frameSize_ = 512;
    float gain_ = 1.0f;

    std::vector<float> window_;
    std::vector<float> lagWindow_;
    std::vector<float> windowed_;
    std::vector<float> autocorr_;
    std::vector<float> a_;
    std::vector<float> tmp_;
};

/**
 * FIR filter with a crossfade between the previous and current coefficient sets.
 *
 * LPC coefficients are refreshed once per block. Swapping them outright puts a step in the
 * impulse response and an audible tick in the output, so each block is filtered with both sets
 * and faded from one to the other.
 */
class CrossfadedFir {
public:
    void init(size_t maxTaps, size_t maxBlock);
    void reset();

    /** Coefficient buffer to fill for the upcoming block. */
    float* pending() { return pending_.data(); }
    void commit(size_t taps);

    void process(const float* input, float* output, size_t n);

private:
    std::vector<float> current_;
    std::vector<float> previous_;
    std::vector<float> pending_;
    std::vector<float> history_;
    size_t taps_ = 0;
    size_t prevTaps_ = 0;
    size_t historyPos_ = 0;
    size_t historyMask_ = 0;
    bool primed_ = false;
};

}  // namespace kolan
