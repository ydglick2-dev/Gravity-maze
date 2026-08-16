#pragma once

#include <cstddef>
#include <vector>

#include "../core/ParamBlock.h"

namespace kolan {

/**
 * The voice-message path. Latency is irrelevant here, so this uses the phase vocoder rather
 * than WSOLA and gets noticeably cleaner pitch shifting for it.
 *
 * Pitch is moved by stretching with the vocoder and resampling back to the original duration.
 * That resampling drags the formants along by the same ratio, so the vocoder is told to warp
 * the spectral envelope by the target formant ratio *divided by* the pitch ratio, which leaves
 * the formants exactly where the preset asked for them.
 */
class OfflineEngine {
public:
    /** Processes an entire mono recording. Allocation is fine here; this never runs on the
     *  audio thread. */
    static std::vector<float> process(const std::vector<float>& input, float sampleRate,
                                      const Params& params);
};

}  // namespace kolan
