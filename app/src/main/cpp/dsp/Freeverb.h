#pragma once

#include <cstddef>
#include <vector>

namespace kolan {

/**
 * Mono Freeverb: eight parallel damped comb filters into four series allpasses.
 *
 * The original tunings are quoted at 44.1 kHz; they are scaled here to whatever rate the engine
 * is running at, otherwise the modal density shifts and the tail sounds metallic at 48 kHz.
 */
class Freeverb {
public:
    void init(float sampleRate);
    void reset();

    /** `size` 0..1 sets the tail length, `mix` 0..1 the wet/dry balance. */
    void process(const float* in, float* out, size_t n, float size, float mix);

private:
    struct Comb {
        std::vector<float> buffer;
        size_t index = 0;
        float filterState = 0.0f;
        float process(float input, float feedback, float damp);
        void reset();
    };

    struct Allpass {
        std::vector<float> buffer;
        size_t index = 0;
        float process(float input, float feedback);
        void reset();
    };

    Comb combs_[8];
    Allpass allpasses_[4];
};

}  // namespace kolan
