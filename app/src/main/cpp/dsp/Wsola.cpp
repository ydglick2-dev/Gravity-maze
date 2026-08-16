#include "Wsola.h"

#include <cmath>
#include <cstring>

#include "../core/FastMath.h"

namespace kolan {

namespace {
// Voice fundamentals span roughly 60 Hz (low male) to 400 Hz (child), so splice distances are
// searched across the matching period range.
constexpr float kMinF0 = 60.0f;
constexpr float kMaxF0 = 400.0f;
// The correlation runs on every second sample; at 48 kHz that is still well above Nyquist for
// the periodicity we care about and halves the search cost.
constexpr int kCorrStride = 2;
}  // namespace

void Wsola::init(float sampleRate) {
    sampleRate_ = sampleRate;

    spliceMin_ = static_cast<long>(sampleRate / kMaxF0);  // ~120 samples at 48 kHz
    spliceMax_ = static_cast<long>(sampleRate / kMinF0);  // ~800 samples at 48 kHz
    crossfade_ = static_cast<int>(sampleRate * 0.004f);   // 4 ms
    corrLen_ = crossfade_;

    // The read head is kept between lagMin_ and lagMax_ behind the write head. lagMin_ has to
    // clear the crossfade plus the correlation window, otherwise a splice would read material
    // that has not been written yet.
    lagMin_ = crossfade_ + corrLen_ + 64;
    lagMax_ = lagMin_ + static_cast<long>(sampleRate * 0.009f);

    size_t needed = static_cast<size_t>(lagMax_ + spliceMax_ + crossfade_ + 1024);
    size_t cap = 1024;
    while (cap < needed) cap <<= 1;
    line_.assign(cap, 0.0f);
    mask_ = cap - 1;

    reset();
}

void Wsola::reset() {
    std::fill(line_.begin(), line_.end(), 0.0f);
    // Start in the middle of the regulation band so the first splice is not provoked
    // immediately, and so the reported latency matches nominalLatencySamples() from the start.
    writePos_ = (lagMin_ + lagMax_) / 2;
    readPos_ = 0.0;
    lastLag_ = writePos_;
    fadeRemaining_ = 0;
    fadeReadPos_ = 0.0;
}

float Wsola::readInterpolated(double pos) const {
    const long i = static_cast<long>(std::floor(pos));
    const float t = static_cast<float>(pos - static_cast<double>(i));

    // Catmull-Rom / cubic Hermite over the four neighbouring samples.
    const float xm1 = readAt(i - 1);
    const float x0 = readAt(i);
    const float x1 = readAt(i + 1);
    const float x2 = readAt(i + 2);

    const float c0 = x0;
    const float c1 = 0.5f * (x1 - xm1);
    const float c2 = xm1 - 2.5f * x0 + 2.0f * x1 - 0.5f * x2;
    const float c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1);
    return ((c3 * t + c2) * t + c1) * t + c0;
}

long Wsola::findBestSplice(long basePos, long dMin, long dMax, int direction) const {
    if (dMax < dMin) return 0;

    float bestScore = -2.0f;
    long bestD = 0;

    // Energy of the reference window (the material we are about to fade out of).
    float refEnergy = 0.0f;
    for (int i = 0; i < corrLen_; i += kCorrStride) {
        const float v = readAt(basePos + i);
        refEnergy += v * v;
    }
    if (refEnergy < 1.0e-9f) {
        // Silence: no periodicity to lock onto, so a plain period-sized jump is as good as any
        // and costs nothing to make.
        return dMin;
    }

    for (long d = dMin; d <= dMax; ++d) {
        const long candBase = basePos + direction * d;
        float dot = 0.0f;
        float candEnergy = 0.0f;
        for (int i = 0; i < corrLen_; i += kCorrStride) {
            const float a = readAt(basePos + i);
            const float b = readAt(candBase + i);
            dot += a * b;
            candEnergy += b * b;
        }
        const float score = dot / (std::sqrt(refEnergy * candEnergy) + 1.0e-9f);
        if (score > bestScore) {
            bestScore = score;
            bestD = d;
        }
    }
    return bestD;
}

void Wsola::process(const float* input, float* output, size_t n) {
    // Write the whole block first so the splice search can see the freshest material.
    const long blockStart = writePos_;
    for (size_t i = 0; i < n; ++i) {
        line_[static_cast<size_t>(writePos_ + static_cast<long>(i)) & mask_] = input[i];
    }
    writePos_ += static_cast<long>(n);

    const double rate = static_cast<double>(ratio_);

    for (size_t i = 0; i < n; ++i) {
        if (fadeRemaining_ > 0) {
            const float w = 1.0f - static_cast<float>(fadeRemaining_) / static_cast<float>(crossfade_);
            // Linear, not equal-power. The splice search deliberately aligns the two sides so
            // they are strongly correlated, and correlated signals sum in amplitude: an
            // equal-power fade would add 3 dB across every join.
            output[i] = (1.0f - w) * readInterpolated(fadeReadPos_) + w * readInterpolated(readPos_);
            fadeReadPos_ += rate;
            --fadeRemaining_;
        } else {
            output[i] = readInterpolated(readPos_);
        }

        readPos_ += rate;

        // Measure the lag against where the write head *would* be if input and output ran in
        // lockstep, not against the end of the block that was just written. Using the real
        // write head would inflate the lag by up to a full block at the start of each callback,
        // sweeping it across the whole regulation band and firing splices on every block even
        // when the ratio is exactly 1.
        const long lag = blockStart + static_cast<long>(i) + 1 - static_cast<long>(readPos_);
        lastLag_ = lag;
        if (fadeRemaining_ == 0) {
            if (lag < lagMin_) {
                // Read head is catching up with the write head: repeat a period.
                const long headroom = static_cast<long>(line_.size()) - lag - crossfade_ - 128;
                const long dMax = spliceMax_ < headroom ? spliceMax_ : headroom;
                const long d = findBestSplice(static_cast<long>(readPos_), spliceMin_, dMax, -1);
                if (d > 0) {
                    fadeReadPos_ = readPos_;
                    readPos_ -= static_cast<double>(d);
                    fadeRemaining_ = crossfade_;
                }
            } else if (lag > lagMax_) {
                // Read head is falling behind: skip a period.
                const long headroom = lag - lagMin_;
                const long dMax = spliceMax_ < headroom ? spliceMax_ : headroom;
                const long d = findBestSplice(static_cast<long>(readPos_), spliceMin_, dMax, +1);
                if (d > 0) {
                    fadeReadPos_ = readPos_;
                    readPos_ += static_cast<double>(d);
                    fadeRemaining_ = crossfade_;
                }
            }
        }
    }

    // Keep the absolute positions from growing without bound over long sessions. Rewinding both
    // heads by a multiple of the buffer size leaves every masked index unchanged.
    const long wrap = static_cast<long>(line_.size()) * 1024;
    if (writePos_ > wrap) {
        writePos_ -= wrap;
        readPos_ -= static_cast<double>(wrap);
        fadeReadPos_ -= static_cast<double>(wrap);
    }
}

}  // namespace kolan
