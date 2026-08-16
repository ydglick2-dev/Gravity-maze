#include "Resampler.h"

#include <cmath>

namespace kolan {

namespace {

inline float sampleAt(const std::vector<float>& v, long i) {
    if (i < 0) return 0.0f;
    if (static_cast<size_t>(i) >= v.size()) return 0.0f;
    return v[static_cast<size_t>(i)];
}

inline float hermite(const std::vector<float>& v, double pos) {
    const long i = static_cast<long>(std::floor(pos));
    const float t = static_cast<float>(pos - static_cast<double>(i));
    const float xm1 = sampleAt(v, i - 1);
    const float x0 = sampleAt(v, i);
    const float x1 = sampleAt(v, i + 1);
    const float x2 = sampleAt(v, i + 2);

    const float c0 = x0;
    const float c1 = 0.5f * (x1 - xm1);
    const float c2 = xm1 - 2.5f * x0 + 2.0f * x1 - 0.5f * x2;
    const float c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1);
    return ((c3 * t + c2) * t + c1) * t + c0;
}

}  // namespace

std::vector<float> resampleHermite(const std::vector<float>& input, float rate) {
    if (input.empty() || rate <= 0.0f) return {};
    const size_t outLength = static_cast<size_t>(static_cast<double>(input.size()) / rate);
    std::vector<float> output;
    output.reserve(outLength);
    double pos = 0.0;
    for (size_t i = 0; i < outLength; ++i) {
        output.push_back(hermite(input, pos));
        pos += static_cast<double>(rate);
    }
    return output;
}

std::vector<float> resampleToLength(const std::vector<float>& input, size_t targetLength) {
    if (input.empty() || targetLength == 0) return std::vector<float>(targetLength, 0.0f);
    std::vector<float> output(targetLength, 0.0f);
    const double step = static_cast<double>(input.size() - 1) /
                        static_cast<double>(targetLength > 1 ? targetLength - 1 : 1);
    double pos = 0.0;
    for (size_t i = 0; i < targetLength; ++i) {
        output[i] = hermite(input, pos);
        pos += step;
    }
    return output;
}

}  // namespace kolan
