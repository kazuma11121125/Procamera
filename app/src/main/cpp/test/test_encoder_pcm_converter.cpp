#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include "dsp/EncoderPcmConverter.h"

using aucampro::EncoderPcmConverter;

namespace {

std::vector<float> sineWave(int sampleRate, double frequency, int frames,
                            int channels) {
    std::vector<float> result(static_cast<size_t>(frames * channels));
    for (int frame = 0; frame < frames; ++frame) {
        const float sample = static_cast<float>(
            std::sin(2.0 * M_PI * frequency * frame / sampleRate));
        for (int channel = 0; channel < channels; ++channel) {
            result[static_cast<size_t>(frame * channels + channel)] = sample;
        }
    }
    return result;
}

double rms(const std::vector<int16_t> &samples, size_t frames, int channels) {
    double sum = 0.0;
    const size_t start = frames / 4;
    for (size_t frame = start; frame < frames; ++frame) {
        const double sample =
            samples[frame * static_cast<size_t>(channels)] / 32767.0;
        sum += sample * sample;
    }
    return std::sqrt(sum / static_cast<double>(frames - start));
}

}  // namespace

TEST(EncoderPcmConverterTest, FourToOneProducesExpectedFrameCount) {
    EncoderPcmConverter converter;
    ASSERT_TRUE(converter.reset(192000, 48000, 2, 1));
    const auto input = sineWave(192000, 1000.0, 4096, 2);
    std::vector<int16_t> output(2048);
    EXPECT_EQ(converter.process(input.data(), 4096, output.data()), 1024u);
}

TEST(EncoderPcmConverterTest, LowFrequencyPassesAndAboveNyquistIsSuppressed) {
    constexpr int kFrames = 40000;
    const auto passInput = sineWave(192000, 1000.0, kFrames, 2);
    const auto stopInput = sineWave(192000, 30000.0, kFrames, 2);
    std::vector<int16_t> passOutput(static_cast<size_t>(kFrames));
    std::vector<int16_t> stopOutput(static_cast<size_t>(kFrames));

    EncoderPcmConverter passConverter;
    EncoderPcmConverter stopConverter;
    ASSERT_TRUE(passConverter.reset(192000, 48000, 2, 123));
    ASSERT_TRUE(stopConverter.reset(192000, 48000, 2, 123));
    const size_t passFrames =
        passConverter.process(passInput.data(), kFrames, passOutput.data());
    const size_t stopFrames =
        stopConverter.process(stopInput.data(), kFrames, stopOutput.data());

    ASSERT_EQ(passFrames, stopFrames);
    EXPECT_GT(rms(passOutput, passFrames, 2), 0.6);
    EXPECT_LT(rms(stopOutput, stopFrames, 2),
              rms(passOutput, passFrames, 2) * 0.1);
}

TEST(EncoderPcmConverterTest, SplitBlocksMatchSingleCallExactly) {
    constexpr int kFrames = 8192;
    const auto input = sineWave(192000, 1234.0, kFrames, 2);
    std::vector<int16_t> wholeOutput(4096);
    std::vector<int16_t> splitOutput(4096);
    EncoderPcmConverter whole;
    EncoderPcmConverter split;
    ASSERT_TRUE(whole.reset(192000, 48000, 2, 987654));
    ASSERT_TRUE(split.reset(192000, 48000, 2, 987654));

    const size_t wholeFrames =
        whole.process(input.data(), kFrames, wholeOutput.data());
    const size_t firstFrames =
        split.process(input.data(), 4096, splitOutput.data());
    const size_t secondFrames =
        split.process(input.data() + 4096 * 2, 4096,
                      splitOutput.data() + firstFrames * 2);

    ASSERT_EQ(firstFrames + secondFrames, wholeFrames);
    EXPECT_EQ(splitOutput, wholeOutput);
}
