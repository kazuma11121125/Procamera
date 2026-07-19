#include <gtest/gtest.h>

#include <algorithm>
#include <vector>

#include "engine/MonitorOutputPath.h"

using aucampro::MonitorOutputPath;
using aucampro::StereoFrame;

namespace {

bool isSilent(const std::vector<StereoFrame> &frames) {
    return std::all_of(frames.begin(), frames.end(), [](const StereoFrame &frame) {
        return frame.left == 0.0f && frame.right == 0.0f;
    });
}

}  // namespace

TEST(MonitorOutputPathTest, PrimesBeforeRenderingAndFadesIn) {
    MonitorOutputPath path;
    path.configure(48000, 960, 240);
    path.setEnabled(true);

    std::vector<StereoFrame> output(480);
    path.render(output.data(), output.size());  // applies the requested reset
    EXPECT_TRUE(isSilent(output));

    std::vector<StereoFrame> input(5000, StereoFrame{0.5f, -0.25f});
    path.push(input.data(), input.size());
    path.render(output.data(), output.size());

    EXPECT_GT(output.back().left, 0.49f);
    EXPECT_LT(output.back().right, -0.24f);
    EXPECT_GT(output.front().left, 0.0f);
    EXPECT_LT(output.front().left, output.back().left);
    EXPECT_EQ(path.underflowEventCount(), 0);
}

TEST(MonitorOutputPathTest, UnderflowSilencesAndReprimes) {
    MonitorOutputPath path;
    path.configure(48000, 240, 240);
    path.setEnabled(true);

    std::vector<StereoFrame> output(480);
    path.render(output.data(), output.size());
    std::vector<StereoFrame> input(5000, StereoFrame{0.25f, 0.25f});
    path.push(input.data(), input.size());

    for (int i = 0; i < 12; ++i) {
        path.render(output.data(), output.size());
    }

    EXPECT_EQ(path.underflowEventCount(), 1);
    EXPECT_EQ(path.underflowFrameCount(), 480);
    EXPECT_EQ(path.resyncCount(), 1);
    EXPECT_FLOAT_EQ(output.back().left, 0.0f);
    EXPECT_FLOAT_EQ(output.back().right, 0.0f);

    path.render(output.data(), output.size());
    EXPECT_TRUE(isSilent(output));
}

TEST(MonitorOutputPathTest, OverflowIsReportedAndResynchronizedByConsumer) {
    MonitorOutputPath path;
    path.configure(192000, 3840, 192);
    path.setEnabled(true);

    std::vector<StereoFrame> output(192);
    path.render(output.data(), output.size());

    std::vector<StereoFrame> tooLarge(70000, StereoFrame{0.1f, 0.1f});
    path.push(tooLarge.data(), tooLarge.size());
    EXPECT_EQ(path.overflowEventCount(), 1);
    EXPECT_EQ(path.overflowDroppedFrameCount(), 70000);

    path.render(output.data(), output.size());
    EXPECT_EQ(path.resyncCount(), 1);
    EXPECT_TRUE(isSilent(output));
}

TEST(MonitorOutputPathTest, HighFillSpeedsConsumerWithinBoundedCorrection) {
    MonitorOutputPath path;
    path.configure(48000, 240, 240);
    path.setEnabled(true);

    std::vector<StereoFrame> output(480);
    path.render(output.data(), output.size());
    std::vector<StereoFrame> input(6000, StereoFrame{0.1f, -0.1f});
    path.push(input.data(), input.size());
    path.render(output.data(), output.size());

    EXPECT_GT(path.correctionPpm(), 0);
    EXPECT_LE(path.correctionPpm(), 20000);
}

TEST(MonitorOutputPathTest, ControllerLearnsMismatchBeyondCrystalDriftRange) {
    MonitorOutputPath path;
    path.configure(48000, 240, 240);
    path.setEnabled(true);

    std::vector<StereoFrame> output(480);
    path.render(output.data(), output.size());
    std::vector<StereoFrame> initial(5000, StereoFrame{0.1f, -0.1f});
    path.push(initial.data(), initial.size());
    path.render(output.data(), output.size());

    // Model an input path delivering ~1% more callback frames than output requests.
    std::vector<StereoFrame> inputBlock(485, StereoFrame{0.1f, -0.1f});
    for (int i = 0; i < 200; ++i) {
        path.push(inputBlock.data(), inputBlock.size());
        path.render(output.data(), output.size());
    }

    EXPECT_GT(path.correctionPpm(), 1000);
    EXPECT_LE(path.correctionPpm(), 20000);
    EXPECT_EQ(path.resyncCount(), 0);
}

TEST(MonitorOutputPathTest, ExcessiveLateBacklogIsDiscardedAndReprimed) {
    MonitorOutputPath path;
    path.configure(48000, 240, 240);
    path.setEnabled(true);

    std::vector<StereoFrame> output(480);
    path.render(output.data(), output.size());
    std::vector<StereoFrame> delayedBacklog(10000, StereoFrame{0.2f, 0.2f});
    path.push(delayedBacklog.data(), delayedBacklog.size());
    path.render(output.data(), output.size());

    EXPECT_EQ(path.resyncCount(), 1);
    EXPECT_EQ(path.fillFrames(), 0);
    EXPECT_TRUE(isSilent(output));
}
