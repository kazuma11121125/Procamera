#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

#include "buffer/SpscRingBuffer.h"

namespace aucampro {

struct StereoFrame {
    float left;
    float right;
};
static_assert(sizeof(StereoFrame) == sizeof(float) * 2);

// A monitor-only SPSC queue and adaptive pull resampler.
//
// The input callback is the sole producer and never talks to the output stream. The
// output callback is the sole consumer and requests exactly the number of frames the
// playback HAL needs. A PI controller adjusts the source/destination ratio within +/-2%
// so independently scheduled/rate-converted input and output paths do not eventually
// empty or fill the queue. None of this state participates in the recording ring buffer.
class MonitorOutputPath {
public:
    static constexpr int32_t kMaxSampleRate = 192000;
    static constexpr size_t kCapacityFrames = static_cast<size_t>(kMaxSampleRate) / 4;
    static constexpr size_t kMaxRenderFrames = 16384;

    MonitorOutputPath();

    // Non-RT, called while no output callback is running.
    void configure(int32_t sampleRateHz, int32_t inputFramesPerBurst,
                   int32_t outputFramesPerBurst);

    // Any thread. State reset/queue clearing is performed later by the output consumer.
    void setEnabled(bool enabled);
    void requestReset();

    // Input callback (producer) only.
    void push(const StereoFrame *frames, size_t frameCount);

    // Output callback (consumer) only.
    void render(StereoFrame *output, size_t frameCount);

    int32_t fillFrames() const { return fillFrames_.load(std::memory_order_relaxed); }
    int32_t targetFrames() const { return targetFrames_; }
    int32_t correctionPpm() const {
        return correctionPpm_.load(std::memory_order_relaxed);
    }
    int32_t underflowEventCount() const {
        return underflowEventCount_.load(std::memory_order_relaxed);
    }
    int64_t underflowFrameCount() const {
        return underflowFrameCount_.load(std::memory_order_relaxed);
    }
    int32_t overflowEventCount() const {
        return overflowEventCount_.load(std::memory_order_relaxed);
    }
    int64_t overflowDroppedFrameCount() const {
        return overflowDroppedFrameCount_.load(std::memory_order_relaxed);
    }
    int32_t resyncCount() const { return resyncCount_.load(std::memory_order_relaxed); }
    int64_t inputCallbackFrameCount() const {
        return inputCallbackFrameCount_.load(std::memory_order_relaxed);
    }
    int64_t outputCallbackFrameCount() const {
        return outputCallbackFrameCount_.load(std::memory_order_relaxed);
    }

private:
    static StereoFrame cubic(const StereoFrame &p0, const StereoFrame &p1,
                             const StereoFrame &p2, const StereoFrame &p3, float t);
    void resetConsumerState();
    void renderFadeToSilence(StereoFrame *output, size_t frameCount);
    void applyFadeIn(StereoFrame *output, size_t frameCount);

    SpscRingBuffer<StereoFrame> fifo_;
    std::array<StereoFrame, kMaxRenderFrames + 4> scratch_{};

    std::atomic<bool> enabled_{false};
    std::atomic<bool> resetRequested_{true};
    std::atomic<bool> overflowPending_{false};

    int32_t sampleRateHz_ = 48000;
    int32_t targetFrames_ = 1920;
    bool priming_ = true;
    bool previousFrameValid_ = false;
    StereoFrame previousFrame_{0.0f, 0.0f};
    StereoFrame lastOutput_{0.0f, 0.0f};
    double phase_ = 0.0;
    double integralPpm_ = 0.0;
    double smoothedCorrectionPpm_ = 0.0;
    size_t fadeInRemainingFrames_ = 0;

    std::atomic<int32_t> fillFrames_{0};
    std::atomic<int32_t> correctionPpm_{0};
    std::atomic<int32_t> underflowEventCount_{0};
    std::atomic<int64_t> underflowFrameCount_{0};
    std::atomic<int32_t> overflowEventCount_{0};
    std::atomic<int64_t> overflowDroppedFrameCount_{0};
    std::atomic<int32_t> resyncCount_{0};
    std::atomic<int64_t> inputCallbackFrameCount_{0};
    std::atomic<int64_t> outputCallbackFrameCount_{0};
};

}  // namespace aucampro
