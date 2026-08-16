#pragma once

#include <cstddef>
#include <vector>

namespace kolan {

/**
 * Radix-2 in-place complex FFT with precomputed twiddles and bit-reversal table.
 *
 * All scratch lives in the object, so transform() performs no allocation and is safe on the
 * audio thread once the object has been constructed.
 */
class Fft {
public:
    Fft() = default;
    explicit Fft(size_t size) { init(size); }

    /** Allocates. `size` must be a power of two. */
    void init(size_t size);

    size_t size() const { return size_; }

    /** In-place complex transform. `inverse` also applies the 1/N scaling. */
    void transform(float* real, float* imag, bool inverse) const;

    /**
     * Real-input forward transform. `input` holds `size` samples; `real`/`imag` receive
     * `size/2 + 1` usable bins (the rest of the arrays are used as scratch and must be at
     * least `size` long).
     */
    void forwardReal(const float* input, float* real, float* imag) const;

    /**
     * Inverse of forwardReal. Reads `size/2 + 1` bins, reconstructs Hermitian symmetry, and
     * writes `size` real samples. `real`/`imag` are used as scratch and are clobbered.
     */
    void inverseReal(float* real, float* imag, float* output) const;

private:
    size_t size_ = 0;
    size_t levels_ = 0;
    std::vector<float> cosTable_;
    std::vector<float> sinTable_;
    std::vector<size_t> bitReverse_;
    mutable std::vector<float> scratchRe_;
    mutable std::vector<float> scratchIm_;
};

}  // namespace kolan
