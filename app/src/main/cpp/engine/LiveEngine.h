#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include "../core/Meters.h"
#include "../core/ParamBlock.h"
#include "../core/RingBuffer.h"
#include "../dsp/VoiceChain.h"

namespace kolan {

/** Where the processed audio should be sent. */
enum class OutputRoute : int {
    kHeadphones = 0,  // LIVE MONITOR: voice communication routing, follows the headset
    kSpeaker = 1,     // LOUDSPEAKER: forced to the built-in speaker, media usage
};

/**
 * Full-duplex low-latency engine built on Oboe.
 *
 * The output stream owns the callback and drives everything: it drains whatever the input
 * stream has captured into a lock-free FIFO, runs the voice chain over it, and writes the
 * result. Driving from the output side is what keeps the two streams from blocking one another
 * when their callbacks drift apart, which they always do.
 *
 * onAudioReady performs no allocation, takes no lock and makes no JNI call. Everything it
 * touches was sized in start().
 */
class LiveEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    LiveEngine(ParamBlock& params, Meters& meters) : params_(params), meters_(meters) {}
    ~LiveEngine() override { stop(); }

    /** Opens both streams and starts them. Returns false if either stream cannot open. */
    bool start(OutputRoute route, int preferredDeviceId);
    void stop();
    bool isRunning() const { return running_.load(std::memory_order_acquire); }

    /** Silences the output without tearing the streams down. Used when headphones vanish. */
    void setMuted(bool muted) { muted_.store(muted, std::memory_order_relaxed); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openInput(int preferredDeviceId);
    bool openOutput(OutputRoute route);
    void updateLatency();

    ParamBlock& params_;
    Meters& meters_;

    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<oboe::AudioStream> outputStream_;

    VoiceChain chain_;
    RingBuffer inputFifo_;

    std::vector<float> captureBuffer_;
    std::vector<float> processBuffer_;

    std::atomic<bool> running_{false};
    std::atomic<bool> muted_{false};
    std::atomic<bool> restartRequested_{false};

    int32_t sampleRate_ = 48000;
    size_t maxFrames_ = 1024;

    // Guards start()/stop() against each other. Never taken on the audio thread.
    std::mutex lifecycleMutex_;
};

}  // namespace kolan
