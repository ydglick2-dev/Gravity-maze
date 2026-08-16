#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>

namespace kolan {

/**
 * Level telemetry for the waveform visualizer and the latency badge.
 *
 * The audio thread only ever does relaxed stores here. The UI thread polls at frame rate from
 * Kotlin; a torn or slightly stale read is invisible in a 60 fps animation, so no
 * synchronisation beyond the atomics is warranted.
 */
class Meters {
public:
    void reset() {
        rawPeak_.store(0.0f, std::memory_order_relaxed);
        processedPeak_.store(0.0f, std::memory_order_relaxed);
        rawRms_.store(0.0f, std::memory_order_relaxed);
        processedRms_.store(0.0f, std::memory_order_relaxed);
        latencyMs_.store(0.0f, std::memory_order_relaxed);
        xruns_.store(0, std::memory_order_relaxed);
    }

    /** Called once per block from the audio thread. */
    void pushBlock(const float* raw, const float* processed, size_t frames) {
        float rawPeak = 0.0f, procPeak = 0.0f;
        float rawSum = 0.0f, procSum = 0.0f;
        for (size_t i = 0; i < frames; ++i) {
            const float a = std::fabs(raw[i]);
            const float b = std::fabs(processed[i]);
            rawPeak = std::max(rawPeak, a);
            procPeak = std::max(procPeak, b);
            rawSum += raw[i] * raw[i];
            procSum += processed[i] * processed[i];
        }
        const float inv = frames > 0 ? 1.0f / static_cast<float>(frames) : 0.0f;
        // Peaks decay rather than jump down, so the visualiser reads as a waveform envelope
        // instead of a strobe.
        store(rawPeak_, rawPeak);
        store(processedPeak_, procPeak);
        rawRms_.store(std::sqrt(rawSum * inv), std::memory_order_relaxed);
        processedRms_.store(std::sqrt(procSum * inv), std::memory_order_relaxed);
    }

    void setLatencyMs(float ms) { latencyMs_.store(ms, std::memory_order_relaxed); }
    void addXrun() { xruns_.fetch_add(1, std::memory_order_relaxed); }

    float rawPeak() const { return rawPeak_.load(std::memory_order_relaxed); }
    float processedPeak() const { return processedPeak_.load(std::memory_order_relaxed); }
    float rawRms() const { return rawRms_.load(std::memory_order_relaxed); }
    float processedRms() const { return processedRms_.load(std::memory_order_relaxed); }
    float latencyMs() const { return latencyMs_.load(std::memory_order_relaxed); }
    int xruns() const { return xruns_.load(std::memory_order_relaxed); }

private:
    static void store(std::atomic<float>& slot, float value) {
        const float prev = slot.load(std::memory_order_relaxed);
        slot.store(value > prev ? value : prev * 0.72f + value * 0.28f, std::memory_order_relaxed);
    }

    std::atomic<float> rawPeak_{0.0f};
    std::atomic<float> processedPeak_{0.0f};
    std::atomic<float> rawRms_{0.0f};
    std::atomic<float> processedRms_{0.0f};
    std::atomic<float> latencyMs_{0.0f};
    std::atomic<int> xruns_{0};
};

}  // namespace kolan
