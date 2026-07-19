#include "dsp/EncoderPcmConverter.h"

#include <algorithm>
#include <cmath>

namespace aucampro {
namespace {

constexpr std::array<double, 4> kButterworthStageQ = {
    0.5098, 0.6013, 0.8999, 2.5629,
};
constexpr double kPi = 3.14159265358979323846;
constexpr float kInt16Scale = 32767.0f;
constexpr float kUint24ToUnitFloat = 1.0f / 16777216.0f;

}  // namespace

bool EncoderPcmConverter::reset(int32_t inputSampleRateHz,
                                int32_t outputSampleRateHz,
                                int32_t channelCount,
                                uint32_t randomSeed) {
    if (inputSampleRateHz <= 0 || outputSampleRateHz <= 0 ||
        inputSampleRateHz % outputSampleRateHz != 0 ||
        channelCount <= 0 || channelCount > kMaxChannels) {
        factor_ = 0;
        channelCount_ = 0;
        return false;
    }

    factor_ = inputSampleRateHz / outputSampleRateHz;
    channelCount_ = channelCount;
    inputFrameCounter_ = 0;
    randomState_ = randomSeed != 0 ? randomSeed : 0x6d2b79f5U;
    history_ = {};

    if (factor_ == 1) {
        coefficients_ = {};
        return true;
    }

    const double cutoffHz = static_cast<double>(outputSampleRateHz) * 0.45;
    const double w0 = 2.0 * kPi * cutoffHz /
                      static_cast<double>(inputSampleRateHz);
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    for (size_t stage = 0; stage < coefficients_.size(); ++stage) {
        const double alpha = sinW0 / (2.0 * kButterworthStageQ[stage]);
        const double a0 = 1.0 + alpha;
        coefficients_[stage] = {
            static_cast<float>(((1.0 - cosW0) / 2.0) / a0),
            static_cast<float>((1.0 - cosW0) / a0),
            static_cast<float>(((1.0 - cosW0) / 2.0) / a0),
            static_cast<float>((-2.0 * cosW0) / a0),
            static_cast<float>((1.0 - alpha) / a0),
        };
    }
    return true;
}

size_t EncoderPcmConverter::process(const float *input, size_t frameCount,
                                    int16_t *output) {
    if (factor_ <= 0 || channelCount_ <= 0 || input == nullptr ||
        output == nullptr) {
        return 0;
    }

    size_t outputFrames = 0;
    for (size_t frame = 0; frame < frameCount; ++frame) {
        const bool keep =
            (inputFrameCounter_ % static_cast<uint64_t>(factor_)) == 0;
        for (int channel = 0; channel < channelCount_; ++channel) {
            float sample =
                input[frame * static_cast<size_t>(channelCount_) + channel];
            if (factor_ > 1) {
                for (int stage = 0;
                     stage < static_cast<int>(coefficients_.size()); ++stage) {
                    sample = filterSample(sample, stage, channel);
                }
            }
            if (keep) {
                output[outputFrames * static_cast<size_t>(channelCount_) +
                       static_cast<size_t>(channel)] = quantize(sample);
            }
        }
        if (keep) {
            ++outputFrames;
        }
        ++inputFrameCounter_;
    }
    return outputFrames;
}

float EncoderPcmConverter::filterSample(float sample, int stage, int channel) {
    const Coefficients &c = coefficients_[static_cast<size_t>(stage)];
    History &h =
        history_[static_cast<size_t>(stage)][static_cast<size_t>(channel)];
    const float result = c.b0 * sample + c.b1 * h.x1 + c.b2 * h.x2 -
                         c.a1 * h.y1 - c.a2 * h.y2;
    h.x2 = h.x1;
    h.x1 = sample;
    h.y2 = h.y1;
    h.y1 = result;
    return result;
}

uint32_t EncoderPcmConverter::nextRandom() {
    uint32_t value = randomState_;
    value ^= value << 13U;
    value ^= value >> 17U;
    value ^= value << 5U;
    randomState_ = value;
    return value;
}

float EncoderPcmConverter::nextUniform() {
    return static_cast<float>(nextRandom() >> 8U) * kUint24ToUnitFloat;
}

int16_t EncoderPcmConverter::quantize(float sample) {
    const float clamped = std::clamp(sample, -1.0f, 1.0f);
    const float tpdf = (nextUniform() - 0.5f) + (nextUniform() - 0.5f);
    const long rounded = std::lround(clamped * kInt16Scale + tpdf);
    return static_cast<int16_t>(
        std::clamp(rounded,
                   static_cast<long>(INT16_MIN),
                   static_cast<long>(INT16_MAX)));
}

}  // namespace aucampro
