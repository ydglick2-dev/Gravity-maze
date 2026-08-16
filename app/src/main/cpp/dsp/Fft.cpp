#include "Fft.h"

#include <cmath>

#include "../core/FastMath.h"

namespace kolan {

void Fft::init(size_t size) {
    size_ = size;
    levels_ = 0;
    while ((size_t(1) << levels_) < size) ++levels_;

    cosTable_.resize(size / 2);
    sinTable_.resize(size / 2);
    for (size_t i = 0; i < size / 2; ++i) {
        const double angle = 2.0 * M_PI * static_cast<double>(i) / static_cast<double>(size);
        cosTable_[i] = static_cast<float>(std::cos(angle));
        sinTable_[i] = static_cast<float>(std::sin(angle));
    }

    bitReverse_.resize(size);
    for (size_t i = 0; i < size; ++i) {
        size_t x = i;
        size_t r = 0;
        for (size_t j = 0; j < levels_; ++j) {
            r = (r << 1) | (x & 1u);
            x >>= 1;
        }
        bitReverse_[i] = r;
    }

    scratchRe_.assign(size, 0.0f);
    scratchIm_.assign(size, 0.0f);
}

void Fft::transform(float* real, float* imag, bool inverse) const {
    const size_t n = size_;

    for (size_t i = 0; i < n; ++i) {
        const size_t j = bitReverse_[i];
        if (j > i) {
            std::swap(real[i], real[j]);
            std::swap(imag[i], imag[j]);
        }
    }

    for (size_t len = 2; len <= n; len <<= 1) {
        const size_t half = len >> 1;
        const size_t step = n / len;
        for (size_t i = 0; i < n; i += len) {
            for (size_t j = i, k = 0; j < i + half; ++j, k += step) {
                const float wr = cosTable_[k];
                const float wi = inverse ? sinTable_[k] : -sinTable_[k];
                const size_t l = j + half;
                const float tr = real[l] * wr - imag[l] * wi;
                const float ti = real[l] * wi + imag[l] * wr;
                real[l] = real[j] - tr;
                imag[l] = imag[j] - ti;
                real[j] += tr;
                imag[j] += ti;
            }
        }
    }

    if (inverse) {
        const float scale = 1.0f / static_cast<float>(n);
        for (size_t i = 0; i < n; ++i) {
            real[i] *= scale;
            imag[i] *= scale;
        }
    }
}

void Fft::forwardReal(const float* input, float* real, float* imag) const {
    for (size_t i = 0; i < size_; ++i) {
        real[i] = input[i];
        imag[i] = 0.0f;
    }
    transform(real, imag, false);
}

void Fft::inverseReal(float* real, float* imag, float* output) const {
    const size_t n = size_;
    const size_t half = n / 2;

    // Rebuild the negative-frequency half from Hermitian symmetry before transforming back.
    for (size_t i = 1; i < half; ++i) {
        real[n - i] = real[i];
        imag[n - i] = -imag[i];
    }
    imag[0] = 0.0f;
    imag[half] = 0.0f;

    transform(real, imag, true);
    for (size_t i = 0; i < n; ++i) {
        output[i] = real[i];
    }
}

}  // namespace kolan
