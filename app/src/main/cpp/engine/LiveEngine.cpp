#include "LiveEngine.h"

#include <android/log.h>

#include <algorithm>
#include <cstring>

#include "../core/FastMath.h"

#define LOG_TAG "KolanEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace kolan {

namespace {
constexpr int32_t kSampleRate = 48000;
// 10 ms at 48 kHz. Oboe may hand us more or fewer frames per callback; this only sizes the
// scratch buffers and the FIFO.
constexpr int32_t kBlockFrames = 480;
}  // namespace

bool LiveEngine::openInput(int preferredDeviceId) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(kSampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        // VoiceCommunication would engage the platform's own AEC and noise suppression, which
        // fight the voice chain and add their own buffering. Unprocessed gives us the raw mic.
        ->setInputPreset(oboe::InputPreset::Unprocessed)
        ->setErrorCallback(this);

    if (preferredDeviceId != 0) {
        builder.setDeviceId(preferredDeviceId);
    }

    oboe::Result result = builder.openStream(inputStream_);
    if (result != oboe::Result::OK) {
        // Not every device exposes an Unprocessed input; fall back to the generic recording
        // preset rather than failing to start at all.
        builder.setInputPreset(oboe::InputPreset::VoiceRecognition);
        result = builder.openStream(inputStream_);
    }
    if (result != oboe::Result::OK) {
        LOGW("input open failed: %s", oboe::convertToText(result));
        return false;
    }

    inputStream_->setBufferSizeInFrames(inputStream_->getFramesPerBurst() * 2);
    return true;
}

bool LiveEngine::openOutput(OutputRoute route) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(kSampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    if (route == OutputRoute::kSpeaker) {
        // LOUDSPEAKER mode plays into a call that is already running on speakerphone. Media
        // usage keeps the platform from ducking us as if we were a notification.
        builder.setUsage(oboe::Usage::Media)->setContentType(oboe::ContentType::Music);
    } else {
        builder.setUsage(oboe::Usage::VoiceCommunication)
            ->setContentType(oboe::ContentType::Speech);
    }

    const oboe::Result result = builder.openStream(outputStream_);
    if (result != oboe::Result::OK) {
        LOGW("output open failed: %s", oboe::convertToText(result));
        return false;
    }

    outputStream_->setBufferSizeInFrames(outputStream_->getFramesPerBurst() * 2);
    return true;
}

bool LiveEngine::start(OutputRoute route, int preferredDeviceId) {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (running_.load(std::memory_order_acquire)) return true;

    if (!openInput(preferredDeviceId)) {
        inputStream_.reset();
        return false;
    }
    if (!openOutput(route)) {
        inputStream_->close();
        inputStream_.reset();
        outputStream_.reset();
        return false;
    }

    sampleRate_ = outputStream_->getSampleRate();
    const int32_t burst = std::max(outputStream_->getFramesPerBurst(),
                                   inputStream_->getFramesPerBurst());
    maxFrames_ = static_cast<size_t>(std::max(burst * 4, kBlockFrames * 2));

    // Everything below allocates, and all of it happens here rather than in the callback.
    chain_.init(static_cast<float>(sampleRate_), maxFrames_);
    captureBuffer_.assign(maxFrames_, 0.0f);
    processBuffer_.assign(maxFrames_, 0.0f);
    inputFifo_.resize(maxFrames_ * 8);
    meters_.reset();

    oboe::Result result = inputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGW("input start failed: %s", oboe::convertToText(result));
        stop();
        return false;
    }
    result = outputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGW("output start failed: %s", oboe::convertToText(result));
        stop();
        return false;
    }

    running_.store(true, std::memory_order_release);
    LOGI("engine started: rate=%d burst=%d exclusive=%d", sampleRate_, burst,
         outputStream_->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0);
    updateLatency();
    return true;
}

void LiveEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    running_.store(false, std::memory_order_release);

    if (outputStream_) {
        outputStream_->requestStop();
        outputStream_->close();
        outputStream_.reset();
    }
    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }
    inputFifo_.clear();
}

void LiveEngine::updateLatency() {
    float total = chain_.algorithmicLatencyMs();

    if (outputStream_) {
        const auto latency = outputStream_->calculateLatencyMillis();
        if (latency) total += static_cast<float>(latency.value());
    }
    if (inputStream_) {
        const auto latency = inputStream_->calculateLatencyMillis();
        if (latency) total += static_cast<float>(latency.value());
    }
    // Whatever is sitting in the FIFO is latency too, and it is the part that moves.
    total += static_cast<float>(inputFifo_.available()) * 1000.0f / static_cast<float>(sampleRate_);

    meters_.setLatencyMs(total);
}

oboe::DataCallbackResult LiveEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                  int32_t numFrames) {
    // Reverb tails and IIR states decay into denormal territory constantly, and denormals cost
    // hundreds of cycles. This has to happen on the audio thread, every callback.
    enableFlushDenormals();

    auto* output = static_cast<float*>(audioData);
    const size_t frames = static_cast<size_t>(numFrames);

    if (frames > maxFrames_ || !inputStream_) {
        std::memset(output, 0, frames * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }

    // Drain the input stream without blocking. A zero timeout means we take what is there and
    // move on; if the microphone is running behind, we pad with silence for this block rather
    // than stalling the output.
    const auto read = inputStream_->read(captureBuffer_.data(), numFrames, 0);
    const size_t captured = read ? static_cast<size_t>(read.value()) : 0;
    if (captured > 0) {
        inputFifo_.write(captureBuffer_.data(), captured);
    }

    // If the FIFO has run long, the microphone is outpacing the speaker and every extra frame
    // is pure added latency. Drop the oldest rather than let it grow without bound.
    const size_t maxBacklog = frames * 3;
    if (inputFifo_.available() > maxBacklog) {
        inputFifo_.trimTo(maxBacklog);
        meters_.addXrun();
    }

    const size_t pulled = inputFifo_.read(processBuffer_.data(), frames);
    if (pulled < frames) {
        std::memset(processBuffer_.data() + pulled, 0, (frames - pulled) * sizeof(float));
    }

    const Params params = params_.snapshot();
    chain_.processBlock(processBuffer_.data(), output, frames, params);

    meters_.pushBlock(chain_.alignedDry(), output, frames);

    if (muted_.load(std::memory_order_relaxed)) {
        // Feedback guard: the chain still ran, so all its state stays warm and unmuting is
        // instant, but nothing reaches the speaker.
        std::memset(output, 0, frames * sizeof(float));
    }

    // calculateLatencyMillis() only reads timestamps the stream has already published, so it is
    // safe here and gives the UI a figure measured rather than assumed.
    updateLatency();

    return oboe::DataCallbackResult::Continue;
}

void LiveEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // Disconnects happen whenever headphones are plugged or unplugged. Mark the engine stopped
    // so the Kotlin layer can decide whether to reopen; restarting from here would race with a
    // deliberate stop().
    LOGW("stream error after close: %s", oboe::convertToText(error));
    running_.store(false, std::memory_order_release);
    restartRequested_.store(true, std::memory_order_release);
}

}  // namespace kolan
