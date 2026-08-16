#pragma once

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

#include "../core/Meters.h"
#include "../core/ParamBlock.h"
#include "../dsp/VoiceChain.h"

namespace kolan {

/**
 * In-place processor for WebRTC's capture buffer.
 *
 * WebRTC's JavaAudioDeviceModule hands every recorded block to an AudioBufferCallback before
 * the encoder sees it, as a direct ByteBuffer of 16-bit PCM. Rewriting that buffer in place is
 * what makes the person on the other end of the call hear the changed voice — it is the one
 * place on stock Android where processed audio can be substituted for the raw microphone,
 * because the stream belongs to us rather than to another app.
 *
 * This runs on WebRTC's own recording thread, not an Oboe callback, so a mutex around
 * reconfiguration is acceptable here. The steady-state path still allocates nothing.
 */
class InjectEngine {
public:
    InjectEngine(ParamBlock& params, Meters& meters) : params_(params), meters_(meters) {}

    /** Rewrites `frameCount` interleaved 16-bit samples in place. */
    void processInterleavedInt16(int16_t* samples, size_t frameCount, int channelCount,
                                 int sampleRate);

    void reset();

private:
    void ensureConfigured(int sampleRate, size_t frameCount);

    ParamBlock& params_;
    Meters& meters_;

    VoiceChain chain_;
    std::vector<float> mono_;
    std::vector<float> processed_;

    int configuredRate_ = 0;
    size_t configuredFrames_ = 0;
    std::mutex mutex_;
};

}  // namespace kolan
