// Host-side verification of the DSP engine.
//
// These run on a desktop compiler, not on a device, so they can be executed on every change
// without an emulator. They check the properties the product actually depends on: that pitch
// shifting moves the fundamental by the requested ratio, that formant shifting moves the
// spectral envelope *without* moving the fundamental (and vice versa), that nothing in the
// chain produces a non-finite or clipped sample, and that the lock-free FIFO survives genuine
// concurrent access.

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include "../core/FastMath.h"
#include "../core/RingBuffer.h"
#include "../dsp/Fft.h"
#include "../dsp/FormantWarp.h"
#include "../dsp/Lpc.h"
#include "../dsp/PhaseVocoder.h"
#include "../dsp/Resampler.h"
#include "../dsp/VoiceChain.h"

using namespace kolan;

namespace {

int gFailures = 0;
int gChecks = 0;

void check(bool condition, const std::string& what) {
    ++gChecks;
    if (condition) {
        std::printf("  ok    %s\n", what.c_str());
    } else {
        std::printf("  FAIL  %s\n", what.c_str());
        ++gFailures;
    }
}

void checkNear(float actual, float expected, float tolerance, const std::string& what) {
    const bool ok = std::fabs(actual - expected) <= tolerance;
    ++gChecks;
    if (ok) {
        std::printf("  ok    %s (got %.2f, expected %.2f +/- %.2f)\n", what.c_str(), actual,
                    expected, tolerance);
    } else {
        std::printf("  FAIL  %s (got %.2f, expected %.2f +/- %.2f)\n", what.c_str(), actual,
                    expected, tolerance);
        ++gFailures;
    }
}

constexpr float kSampleRate = 48000.0f;
constexpr size_t kBlock = 480;  // 10 ms

/**
 * A crude but unambiguous vowel: an impulse train excitation through a single two-pole
 * resonator. The fundamental and the formant are both known exactly, which is what makes it
 * useful for asserting that the two move independently.
 */
std::vector<float> makeVowel(float f0Hz, float formantHz, size_t samples) {
    std::vector<float> out(samples, 0.0f);

    const float period = kSampleRate / f0Hz;
    std::vector<float> excitation(samples, 0.0f);
    for (size_t i = 0; i < samples; ++i) {
        if (std::fmod(static_cast<float>(i), period) < 1.0f) excitation[i] = 1.0f;
    }

    // Two-pole resonator with a 90 Hz bandwidth.
    const float r = std::exp(-kPi * 90.0f / kSampleRate);
    const float theta = kTwoPi * formantHz / kSampleRate;
    const float a1 = -2.0f * r * std::cos(theta);
    const float a2 = r * r;
    float z1 = 0.0f, z2 = 0.0f;
    for (size_t i = 0; i < samples; ++i) {
        const float y = excitation[i] - a1 * z1 - a2 * z2;
        z2 = z1;
        z1 = y;
        out[i] = y * 0.05f;
    }
    return out;
}

/**
 * Autocorrelation pitch estimate over the voice range.
 *
 * A perfectly periodic signal correlates just as well at two or three times its period as at
 * the period itself, so a plain argmax reports the fundamental an octave (or a twelfth) too
 * low. Two things prevent that here: the correlation window is a fixed length regardless of
 * lag, so the scores are directly comparable, and among all lags scoring close to the best the
 * *shortest* wins.
 */
float estimateF0(const std::vector<float>& signal, size_t offset, size_t length) {
    const size_t minLag = static_cast<size_t>(kSampleRate / 500.0f);
    const size_t maxLag = static_cast<size_t>(kSampleRate / 60.0f);
    const size_t window = length / 2;
    if (window <= maxLag || offset + length > signal.size()) return 0.0f;

    std::vector<float> scores(maxLag + 1, -1.0f);
    float best = -1.0f;
    for (size_t lag = minLag; lag <= maxLag; ++lag) {
        float dot = 0.0f, energyA = 0.0f, energyB = 0.0f;
        for (size_t i = 0; i < window; ++i) {
            const float a = signal[offset + i];
            const float b = signal[offset + i + lag];
            dot += a * b;
            energyA += a * a;
            energyB += b * b;
        }
        const float score = dot / (std::sqrt(energyA * energyB) + 1.0e-9f);
        scores[lag] = score;
        best = std::max(best, score);
    }
    if (best <= 0.0f) return 0.0f;

    size_t bestLag = minLag;
    for (size_t lag = minLag; lag <= maxLag; ++lag) {
        if (scores[lag] >= best) {
            bestLag = lag;
            break;
        }
    }

    // Octave correction: only integer sub-multiples of the winning lag are considered, so a
    // spurious ripple elsewhere in the correlation cannot pull the estimate off. The shortest
    // sub-multiple that still correlates nearly as well is the true period.
    for (size_t divisor = 8; divisor >= 2; --divisor) {
        const size_t candidate = bestLag / divisor;
        if (candidate < minLag) continue;
        size_t refined = candidate;
        for (size_t lag = candidate > 2 ? candidate - 2 : minLag;
             lag <= candidate + 2 && lag <= maxLag; ++lag) {
            if (lag >= minLag && scores[lag] > scores[refined]) refined = lag;
        }
        if (scores[refined] >= best * 0.9f) return kSampleRate / static_cast<float>(refined);
    }
    return kSampleRate / static_cast<float>(bestLag);
}

/** Frequency of the strongest peak of the LPC spectral envelope. */
float estimateFirstFormant(const std::vector<float>& signal, size_t offset) {
    constexpr size_t kFrame = 512;
    constexpr int kOrder = 20;
    constexpr size_t kFftSize = 1024;
    if (offset + kFrame > signal.size()) return 0.0f;

    Lpc lpc;
    lpc.init(kOrder, kFrame);
    lpc.analyze(signal.data() + offset);

    Fft fft;
    fft.init(kFftSize);
    std::vector<float> re(kFftSize, 0.0f), im(kFftSize, 0.0f);
    for (int i = 0; i <= kOrder; ++i) re[static_cast<size_t>(i)] = lpc.coefficients()[i];
    fft.transform(re.data(), im.data(), false);

    float best = -1.0f;
    size_t bestBin = 0;
    // Ignore the DC corner, where the LPC envelope often has a shoulder that is not a formant.
    const size_t firstBin = static_cast<size_t>(150.0f / kSampleRate * kFftSize);
    for (size_t k = firstBin; k <= kFftSize / 2; ++k) {
        const float magnitude = std::sqrt(re[k] * re[k] + im[k] * im[k]);
        const float envelope = 1.0f / (magnitude + 1.0e-9f);
        if (envelope > best) {
            best = envelope;
            bestBin = k;
        }
    }
    return static_cast<float>(bestBin) * kSampleRate / static_cast<float>(kFftSize);
}

Params baseParams() {
    Params p;
    p.gateThresholdDb = -80.0f;  // gate off, the synthetic signals are quiet by design
    p.outputGainDb = 0.0f;
    return p;
}

std::vector<float> runChain(const std::vector<float>& input, const Params& params) {
    VoiceChain chain;
    chain.init(kSampleRate, kBlock);
    std::vector<float> output(input.size(), 0.0f);
    for (size_t i = 0; i + kBlock <= input.size(); i += kBlock) {
        chain.processBlock(input.data() + i, output.data() + i, kBlock, params);
    }
    return output;
}

bool allFinite(const std::vector<float>& v) {
    for (float x : v) {
        if (!std::isfinite(x)) return false;
    }
    return true;
}

float peakOf(const std::vector<float>& v) {
    float peak = 0.0f;
    for (float x : v) peak = std::max(peak, std::fabs(x));
    return peak;
}

// ------------------------------------------------------------------------------------ tests

void testRingBuffer() {
    std::printf("RingBuffer\n");

    RingBuffer ring(1024);
    check(ring.capacity() == 1024, "capacity rounds to a power of two");

    float in[100];
    for (int i = 0; i < 100; ++i) in[i] = static_cast<float>(i);
    check(ring.write(in, 100) == 100, "write accepts a full block");
    check(ring.available() == 100, "available reports what was written");

    float out[100] = {0};
    check(ring.read(out, 100) == 100, "read returns the same count");
    bool same = true;
    for (int i = 0; i < 100; ++i) same = same && (out[i] == static_cast<float>(i));
    check(same, "data survives the round trip");
    check(ring.available() == 0, "buffer drains to empty");

    // Threaded torture: one producer, one consumer, checking a monotonically increasing
    // sequence arrives intact and in order.
    RingBuffer shared(4096);
    constexpr int kTotal = 400000;
    std::atomic<bool> failed{false};

    std::thread producer([&] {
        float chunk[64];
        int written = 0;
        while (written < kTotal) {
            const int count = std::min(64, kTotal - written);
            for (int i = 0; i < count; ++i) chunk[i] = static_cast<float>(written + i);
            const size_t n = shared.write(chunk, static_cast<size_t>(count));
            written += static_cast<int>(n);
            if (n == 0) std::this_thread::yield();
        }
    });

    std::thread consumer([&] {
        float chunk[128];
        int expected = 0;
        while (expected < kTotal) {
            const size_t n = shared.read(chunk, 128);
            for (size_t i = 0; i < n; ++i) {
                if (chunk[i] != static_cast<float>(expected + static_cast<int>(i))) {
                    failed.store(true);
                    return;
                }
            }
            expected += static_cast<int>(n);
            if (n == 0) std::this_thread::yield();
        }
    });

    producer.join();
    consumer.join();
    check(!failed.load(), "400k samples cross threads in order with no loss");
}

void testFft() {
    std::printf("FFT\n");

    constexpr size_t kSize = 1024;
    Fft fft;
    fft.init(kSize);

    std::vector<float> signal(kSize), re(kSize), im(kSize), restored(kSize);
    for (size_t i = 0; i < kSize; ++i) {
        signal[i] = std::sin(kTwoPi * 10.0f * static_cast<float>(i) / static_cast<float>(kSize)) +
                    0.5f * std::sin(kTwoPi * 37.0f * static_cast<float>(i) /
                                    static_cast<float>(kSize));
    }

    fft.forwardReal(signal.data(), re.data(), im.data());

    float magnitude10 = std::sqrt(re[10] * re[10] + im[10] * im[10]);
    float magnitude37 = std::sqrt(re[37] * re[37] + im[37] * im[37]);
    float magnitude20 = std::sqrt(re[20] * re[20] + im[20] * im[20]);
    check(magnitude10 > 100.0f && magnitude37 > 50.0f, "both tones appear in their bins");
    check(magnitude20 < 1.0f, "an empty bin stays empty");
    checkNear(magnitude10 / magnitude37, 2.0f, 0.05f, "bin magnitudes keep their 2:1 ratio");

    fft.inverseReal(re.data(), im.data(), restored.data());
    float maxError = 0.0f;
    for (size_t i = 0; i < kSize; ++i) {
        maxError = std::max(maxError, std::fabs(restored[i] - signal[i]));
    }
    check(maxError < 1.0e-4f, "forward then inverse round-trips to the original");
}

void testPitchShiftMovesF0() {
    std::printf("Pitch shift moves the fundamental\n");

    const auto input = makeVowel(120.0f, 800.0f, 48000);
    const float inputF0 = estimateF0(input, 4800, 16384);
    checkNear(inputF0, 120.0f, 4.0f, "test signal starts at 120 Hz");

    Params up = baseParams();
    up.pitchSemitones = 12.0f;  // one octave
    const auto shiftedUp = runChain(input, up);
    check(allFinite(shiftedUp), "output is finite");
    checkNear(estimateF0(shiftedUp, 9600, 16384), 240.0f, 12.0f, "+12 st doubles the fundamental");

    Params down = baseParams();
    down.pitchSemitones = -12.0f;
    const auto shiftedDown = runChain(input, down);
    check(allFinite(shiftedDown), "output is finite");
    checkNear(estimateF0(shiftedDown, 9600, 16384), 60.0f, 4.0f, "-12 st halves the fundamental");

    Params fifth = baseParams();
    fifth.pitchSemitones = 7.0f;  // ratio 1.4983
    const auto shiftedFifth = runChain(input, fifth);
    checkNear(estimateF0(shiftedFifth, 9600, 16384), 179.8f, 9.0f, "+7 st lands on a fifth");
}

void testPitchShiftLeavesFormantsAlone() {
    std::printf("Pitch shift leaves formants where they are (not a chipmunk)\n");

    const auto input = makeVowel(120.0f, 800.0f, 48000);
    const float inputFormant = estimateFirstFormant(input, 9600);
    checkNear(inputFormant, 800.0f, 90.0f, "test signal's formant is at 800 Hz");

    Params up = baseParams();
    up.pitchSemitones = 12.0f;
    const auto shifted = runChain(input, up);

    const float shiftedFormant = estimateFirstFormant(shifted, 14400);
    // A naive resampling shifter would drag this to 1600 Hz. Staying near 800 is the whole
    // point of doing the pitch shift on the LPC excitation.
    checkNear(shiftedFormant, 800.0f, 150.0f, "formant stays put through a +12 st pitch shift");
    check(shiftedFormant < 1200.0f, "formant is nowhere near the 1600 Hz a resampler would give");
}

void testFormantShiftLeavesPitchAlone() {
    std::printf("Formant shift leaves pitch where it is\n");

    const auto input = makeVowel(120.0f, 800.0f, 48000);

    Params warm = baseParams();
    warm.formantSemitones = 7.0f;  // ratio 1.4983 -> ~1199 Hz
    const auto shifted = runChain(input, warm);
    check(allFinite(shifted), "output is finite");

    checkNear(estimateF0(shifted, 14400, 16384), 120.0f, 6.0f,
              "fundamental is untouched by a formant shift");
    checkNear(estimateFirstFormant(shifted, 14400), 1199.0f, 220.0f,
              "+7 st formant shift moves the envelope peak by 1.5x");

    Params deep = baseParams();
    deep.formantSemitones = -7.0f;  // ~534 Hz
    const auto lowered = runChain(input, deep);
    checkNear(estimateF0(lowered, 14400, 16384), 120.0f, 6.0f,
              "fundamental survives a downward formant shift too");
    checkNear(estimateFirstFormant(lowered, 14400), 534.0f, 130.0f,
              "-7 st formant shift moves the envelope peak by 1/1.5");
}

void testUnityIsTransparent() {
    std::printf("Unity settings stay transparent\n");

    const auto input = makeVowel(150.0f, 700.0f, 24000);
    const auto output = runChain(input, baseParams());
    check(allFinite(output), "output is finite");

    checkNear(estimateF0(output, 4800, 12000), 150.0f, 5.0f, "pitch is unchanged");
    // The delay line means samples do not line up, so compare energy rather than waveforms.
    float inputEnergy = 0.0f, outputEnergy = 0.0f;
    for (size_t i = 4800; i < 20000; ++i) {
        inputEnergy += input[i] * input[i];
        outputEnergy += output[i] * output[i];
    }
    const float ratioDb = 10.0f * std::log10((outputEnergy + 1e-12f) / (inputEnergy + 1e-12f));
    checkNear(ratioDb, 0.0f, 1.5f, "level is preserved within 1.5 dB");
}

void testChainStability() {
    std::printf("Full chain stays bounded under extreme settings\n");

    // Everything at once, at a level that would make a badly behaved chain blow up.
    std::vector<float> input(96000);
    Rng rng(12345);
    for (size_t i = 0; i < input.size(); ++i) {
        const float sweep = 200.0f + 3000.0f * static_cast<float>(i) /
                                         static_cast<float>(input.size());
        input[i] = 0.9f * std::sin(kTwoPi * sweep * static_cast<float>(i) / kSampleRate) +
                   0.1f * rng.nextBipolar();
    }

    Params extreme = baseParams();
    extreme.pitchSemitones = -12.0f;
    extreme.formantSemitones = 12.0f;
    extreme.ringModHz = 90.0f;
    extreme.ringModDepth = 1.0f;
    extreme.drive = 1.0f;
    extreme.reverbMix = 1.0f;
    extreme.reverbSize = 1.0f;
    extreme.telephone = 1.0f;
    extreme.compressorAmount = 1.0f;
    extreme.outputGainDb = 12.0f;

    const auto output = runChain(input, extreme);
    check(allFinite(output), "no NaN or infinity anywhere in the output");
    check(peakOf(output) <= 1.0f, "the limiter holds the output inside full scale");

    // Silence in must give silence out, with no self-oscillation from the reverb or the gate.
    std::vector<float> silence(48000, 0.0f);
    const auto quiet = runChain(silence, extreme);
    check(allFinite(quiet), "silence in gives finite output");
    check(peakOf(quiet) < 1.0e-3f, "the chain does not self-oscillate on silence");
}

void testWhisper() {
    std::printf("Whisperisation removes periodicity but keeps the envelope\n");

    const auto input = makeVowel(120.0f, 800.0f, 48000);

    Params whisper = baseParams();
    whisper.whisper = 1.0f;
    const auto output = runChain(input, whisper);
    check(allFinite(output), "output is finite");

    // A whisper has no fundamental, so the autocorrelation peak should be weak. Compare the
    // periodicity of input and output at the original pitch period.
    auto periodicity = [](const std::vector<float>& v, size_t offset, size_t length, size_t lag) {
        float dot = 0.0f, energyA = 0.0f, energyB = 0.0f;
        for (size_t i = 0; i + lag < length; ++i) {
            dot += v[offset + i] * v[offset + i + lag];
            energyA += v[offset + i] * v[offset + i];
            energyB += v[offset + i + lag] * v[offset + i + lag];
        }
        return dot / (std::sqrt(energyA * energyB) + 1.0e-9f);
    };

    const size_t lag = static_cast<size_t>(kSampleRate / 120.0f);
    const float inputPeriodicity = periodicity(input, 9600, 16384, lag);
    const float outputPeriodicity = periodicity(output, 14400, 16384, lag);
    check(inputPeriodicity > 0.7f, "input is strongly periodic");
    check(outputPeriodicity < inputPeriodicity * 0.6f, "whisper output is much less periodic");

    checkNear(estimateFirstFormant(output, 14400), 800.0f, 260.0f,
              "the vocal tract resonance survives whisperisation");
}

void testPhaseVocoder() {
    std::printf("Phase vocoder (voice message path)\n");

    const auto input = makeVowel(140.0f, 900.0f, 48000);
    PhaseVocoder vocoder;

    const auto stretched = vocoder.process(input, 1.5f, 1.0f, 0.0f);
    check(allFinite(stretched), "stretched output is finite");
    checkNear(static_cast<float>(stretched.size()) / static_cast<float>(input.size()), 1.5f, 0.05f,
              "1.5x stretch produces 1.5x the samples");
    checkNear(estimateF0(stretched, 9600, 16384), 140.0f, 6.0f,
              "time stretching does not change pitch");

    // Stretch then resample is how the offline path shifts pitch: +12 st means stretch by 2 and
    // play back at twice the rate.
    const auto stretchedForOctave = vocoder.process(input, 2.0f, 1.0f, 0.0f);
    const auto pitched = resampleHermite(stretchedForOctave, 2.0f);
    check(allFinite(pitched), "pitched output is finite");
    checkNear(static_cast<float>(pitched.size()) / static_cast<float>(input.size()), 1.0f, 0.05f,
              "duration is restored after resampling");
    checkNear(estimateF0(pitched, 9600, 16384), 280.0f, 14.0f, "+12 st doubles the fundamental");

    // The resampling doubled the formants too, so the vocoder must warp them back by 1/2 to
    // leave them where they started.
    const auto compensated = vocoder.process(input, 2.0f, 0.5f, 0.0f);
    const auto pitchedFlat = resampleHermite(compensated, 2.0f);
    checkNear(estimateFirstFormant(pitchedFlat, 9600), 900.0f, 220.0f,
              "formant compensation keeps the envelope at 900 Hz through an octave shift");
}

void testResampler() {
    std::printf("Resampler\n");

    std::vector<float> input(4800);
    for (size_t i = 0; i < input.size(); ++i) {
        input[i] = std::sin(kTwoPi * 200.0f * static_cast<float>(i) / kSampleRate);
    }

    const auto faster = resampleHermite(input, 2.0f);
    checkNear(static_cast<float>(faster.size()), 2400.0f, 2.0f, "2x rate halves the length");
    checkNear(estimateF0(faster, 200, 2000), 400.0f, 12.0f, "2x rate doubles the frequency");

    const auto exact = resampleToLength(input, 1000);
    check(exact.size() == 1000, "resampleToLength hits the requested length");
    check(allFinite(exact), "output is finite");
}

void testFormantWarpUnity() {
    std::printf("Formant warp at unity reproduces the source envelope\n");

    const auto vowel = makeVowel(120.0f, 800.0f, 8192);
    Lpc lpc;
    lpc.init(20, 512);
    lpc.analyze(vowel.data() + 4096);

    FormantWarp warp;
    warp.init(512, 128);
    std::vector<float> taps(128, 0.0f);
    warp.compute(lpc.coefficients(), 20, 1.0f, taps.data());

    check(allFinite(taps), "filter taps are finite");
    check(std::fabs(taps[0]) > 1.0e-4f, "the impulse response has energy at the front");

    // Feed the filter with an impulse train and confirm the resonance comes back out at 800 Hz.
    std::vector<float> impulse(4096, 0.0f);
    for (size_t i = 0; i < impulse.size(); i += 400) impulse[i] = 1.0f;
    std::vector<float> filtered(impulse.size(), 0.0f);
    for (size_t i = 0; i < impulse.size(); ++i) {
        float acc = 0.0f;
        for (size_t t = 0; t < taps.size() && t <= i; ++t) acc += taps[t] * impulse[i - t];
        filtered[i] = acc;
    }
    checkNear(estimateFirstFormant(filtered, 1024), 800.0f, 160.0f,
              "unity warp puts the resonance back at 800 Hz");

    // And a 1.5x warp should move it up by 1.5x.
    warp.compute(lpc.coefficients(), 20, 1.5f, taps.data());
    for (size_t i = 0; i < impulse.size(); ++i) {
        float acc = 0.0f;
        for (size_t t = 0; t < taps.size() && t <= i; ++t) acc += taps[t] * impulse[i - t];
        filtered[i] = acc;
    }
    checkNear(estimateFirstFormant(filtered, 1024), 1200.0f, 240.0f,
              "1.5x warp moves the resonance to 1200 Hz");
}

void testLatencyBudget() {
    std::printf("Latency budget\n");

    VoiceChain chain;
    chain.init(kSampleRate, kBlock);

    const auto input = makeVowel(120.0f, 800.0f, 48000);
    std::vector<float> output(kBlock, 0.0f);
    Params p = baseParams();
    p.pitchSemitones = 5.0f;
    p.formantSemitones = 3.0f;

    for (size_t i = 0; i + kBlock <= input.size(); i += kBlock) {
        chain.processBlock(input.data() + i, output.data(), kBlock, p);
    }

    const float latency = chain.algorithmicLatencyMs();
    std::printf("  info  algorithmic latency: %.2f ms\n", latency);
    check(latency > 0.0f && latency < 20.0f,
          "algorithmic latency stays inside the live budget (< 20 ms)");
}

void testBypassIsTimeAligned() {
    std::printf("A/B bypass is time aligned with the processed path\n");

    const auto input = makeVowel(130.0f, 750.0f, 24000);

    Params bypassed = baseParams();
    bypassed.bypass = true;
    bypassed.pitchSemitones = 5.0f;
    const auto output = runChain(input, bypassed);

    check(allFinite(output), "bypassed output is finite");
    checkNear(estimateF0(output, 4800, 12000), 130.0f, 5.0f,
              "bypass really does give back the raw pitch");

    // The dry path is delayed to match the processed path, so the alignment offset should be
    // close to the WSOLA nominal lag rather than zero.
    float bestScore = -1.0f;
    size_t bestOffset = 0;
    for (size_t offset = 0; offset < 1200; ++offset) {
        float dot = 0.0f, energyA = 0.0f, energyB = 0.0f;
        for (size_t i = 6000; i < 12000; ++i) {
            const float a = input[i];
            const float b = output[i + offset];
            dot += a * b;
            energyA += a * a;
            energyB += b * b;
        }
        const float score = dot / (std::sqrt(energyA * energyB) + 1.0e-9f);
        if (score > bestScore) {
            bestScore = score;
            bestOffset = offset;
        }
    }
    check(bestScore > 0.95f, "bypassed output matches the input waveform");
    std::printf("  info  dry alignment delay: %zu samples (%.2f ms)\n", bestOffset,
                static_cast<float>(bestOffset) * 1000.0f / kSampleRate);
}

}  // namespace

int main() {
    std::printf("Kolan DSP host tests\n====================\n\n");

    testRingBuffer();
    testFft();
    testResampler();
    testFormantWarpUnity();
    testUnityIsTransparent();
    testPitchShiftMovesF0();
    testPitchShiftLeavesFormantsAlone();
    testFormantShiftLeavesPitchAlone();
    testWhisper();
    testChainStability();
    testPhaseVocoder();
    testLatencyBudget();
    testBypassIsTimeAligned();

    std::printf("\n%d checks, %d failures\n", gChecks, gFailures);
    return gFailures == 0 ? 0 : 1;
}
