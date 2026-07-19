#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace aucampro {

// Non-RT recording-thread converter: anti-alias filters integer-ratio hi-res PCM down to
// the AAC rate, then applies TPDF dither and quantizes to signed 16-bit PCM. Keeping this
// hot loop in native code is intentional: on Sony SO-51C at 192kHz, the equivalent Kotlin
// Decimator + Random.Default path consumed 15-18ms of each 21.3ms audio block after the
// device warmed up, leaving too little budget for MediaCodec and causing the capture ring
// to overflow after tens of seconds.
class EncoderPcmConverter {
public:
    static constexpr int kMaxChannels = 2;

    bool reset(int32_t inputSampleRateHz, int32_t outputSampleRateHz,
               int32_t channelCount, uint32_t randomSeed);

    // input contains frameCount interleaved float frames. output must hold at least
    // ceil(frameCount/factor) * channelCount samples. Returns output frames.
    size_t process(const float *input, size_t frameCount, int16_t *output);

    int32_t factor() const { return factor_; }
    size_t outputFrameUpperBound(size_t inputFrames) const {
        return factor_ > 0
                   ? (inputFrames + static_cast<size_t>(factor_) - 1) /
                         static_cast<size_t>(factor_)
                   : 0;
    }

private:
    struct Coefficients {
        float b0 = 0.0f;
        float b1 = 0.0f;
        float b2 = 0.0f;
        float a1 = 0.0f;
        float a2 = 0.0f;
    };

    struct History {
        float x1 = 0.0f;
        float x2 = 0.0f;
        float y1 = 0.0f;
        float y2 = 0.0f;
    };

    float filterSample(float sample, int stage, int channel);
    uint32_t nextRandom();
    float nextUniform();
    int16_t quantize(float sample);

    int32_t factor_ = 0;
    int32_t channelCount_ = 0;
    uint64_t inputFrameCounter_ = 0;
    uint32_t randomState_ = 0x6d2b79f5U;
    std::array<Coefficients, 4> coefficients_{};
    std::array<std::array<History, kMaxChannels>, 4> history_{};
};

}  // namespace aucampro
