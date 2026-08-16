#pragma once

#include <cstddef>
#include <vector>

namespace kolan {

/**
 * Offline fractional resampler using cubic Hermite interpolation.
 *
 * Used by the voice-message path, where the whole signal is in memory and allocation is free.
 * The live path does its interpolation inside Wsola instead, to avoid a second buffer hop.
 */
std::vector<float> resampleHermite(const std::vector<float>& input, float rate);

/** Resamples so the result has exactly `targetLength` samples. */
std::vector<float> resampleToLength(const std::vector<float>& input, size_t targetLength);

}  // namespace kolan
