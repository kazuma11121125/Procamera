#include "engine/MonitorOutputPath.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace aucampro {
namespace {
// SO-51C at a requested 192 kHz was measured delivering ~0.7% more input callback
// frames than output callback frames over 35 seconds, with short scheduling windows
// reaching ~4%. A conventional +/-1000 ppm crystal-drift range therefore cannot hold
// this device's queue: Oboe's independent rate-conversion/scheduling paths add a much
// larger effective mismatch. +/-2% covers the sustained error while unusually long
// stalls still take the explicit fade/re-prime path below.
constexpr double kMaxCorrectionPpm = 20000.0;
constexpr double kMaxIntegralPpm = 10000.0;
constexpr double kProportionalPpmPerSecond = 100000.0;
constexpr double kIntegralPpmPerSecondSquared = 50000.0;
constexpr double kCorrectionSmoothingSeconds = 0.5;
// The SO-51C output callback occasionally pauses for longer than one 40ms input/output
// cushion even while AAudio reports no output xrun. An 80ms target keeps that scheduling
// jitter out of the audible path while remaining below the application's monitor-latency
// budget; the FIFO is still bounded and independently clock-corrected.
constexpr double kTargetLatencySeconds = 0.080;
// A long output scheduling stall can leave valid but now noticeably late monitor audio
// in the FIFO. Even the widened correction range would turn a sufficiently large backlog
// into prolonged excess latency, so discard/re-prime instead.
constexpr double kMaxBacklogBeyondTargetSeconds = 0.100;
constexpr double kFadeSeconds = 0.005;

float clampSample(float value) {
    return std::clamp(value, -1.0f, 1.0f);
}
}  // namespace

MonitorOutputPath::MonitorOutputPath() : fifo_(kCapacityFrames) {}

void MonitorOutputPath::configure(int32_t sampleRateHz, int32_t inputFramesPerBurst,
                                  int32_t outputFramesPerBurst) {
    sampleRateHz_ = std::clamp(sampleRateHz, 8000, kMaxSampleRate);
    const int32_t latencyTarget =
        static_cast<int32_t>(std::lround(sampleRateHz_ * kTargetLatencySeconds));
    const int32_t burstTarget =
        std::max(inputFramesPerBurst, outputFramesPerBurst) * 2;
    targetFrames_ = std::clamp(std::max(latencyTarget, burstTarget),
                               sampleRateHz_ / 50, sampleRateHz_ / 10);
    resetRequested_.store(true, std::memory_order_release);
}

void MonitorOutputPath::setEnabled(bool enabled) {
    enabled_.store(enabled, std::memory_order_release);
    resetRequested_.store(true, std::memory_order_release);
}

void MonitorOutputPath::requestReset() {
    resetRequested_.store(true, std::memory_order_release);
}

void MonitorOutputPath::push(const StereoFrame *frames, size_t frameCount) {
    if (!enabled_.load(std::memory_order_acquire) || frameCount == 0) {
        return;
    }
    inputCallbackFrameCount_.fetch_add(static_cast<int64_t>(frameCount),
                                       std::memory_order_relaxed);
    if (fifo_.writeAllOrNothing(frames, frameCount) != frameCount) {
        overflowEventCount_.fetch_add(1, std::memory_order_relaxed);
        overflowDroppedFrameCount_.fetch_add(
            static_cast<int64_t>(frameCount), std::memory_order_relaxed);
        overflowPending_.store(true, std::memory_order_release);
    }
    fillFrames_.store(static_cast<int32_t>(fifo_.availableToRead()),
                      std::memory_order_relaxed);
}

void MonitorOutputPath::resetConsumerState() {
    fifo_.clear();
    priming_ = true;
    previousFrameValid_ = false;
    previousFrame_ = {0.0f, 0.0f};
    phase_ = 0.0;
    integralPpm_ = 0.0;
    smoothedCorrectionPpm_ = 0.0;
    fadeInRemainingFrames_ = 0;
    correctionPpm_.store(0, std::memory_order_relaxed);
    fillFrames_.store(0, std::memory_order_relaxed);
}

void MonitorOutputPath::renderFadeToSilence(StereoFrame *output, size_t frameCount) {
    const size_t fadeFrames = std::min(
        frameCount, static_cast<size_t>(std::max(1.0, sampleRateHz_ * kFadeSeconds)));
    for (size_t i = 0; i < fadeFrames; ++i) {
        const float gain = 1.0f - static_cast<float>(i + 1) /
                                     static_cast<float>(fadeFrames);
        output[i] = {lastOutput_.left * gain, lastOutput_.right * gain};
    }
    if (frameCount > fadeFrames) {
        std::memset(output + fadeFrames, 0,
                    (frameCount - fadeFrames) * sizeof(StereoFrame));
    }
    lastOutput_ = {0.0f, 0.0f};
}

void MonitorOutputPath::applyFadeIn(StereoFrame *output, size_t frameCount) {
    if (fadeInRemainingFrames_ == 0) {
        return;
    }
    const size_t totalFadeFrames =
        static_cast<size_t>(std::max(1.0, sampleRateHz_ * kFadeSeconds));
    const size_t applyFrames = std::min(frameCount, fadeInRemainingFrames_);
    const size_t alreadyApplied = totalFadeFrames - fadeInRemainingFrames_;
    for (size_t i = 0; i < applyFrames; ++i) {
        const float gain = static_cast<float>(alreadyApplied + i + 1) /
                           static_cast<float>(totalFadeFrames);
        output[i].left *= gain;
        output[i].right *= gain;
    }
    fadeInRemainingFrames_ -= applyFrames;
}

StereoFrame MonitorOutputPath::cubic(const StereoFrame &p0, const StereoFrame &p1,
                                     const StereoFrame &p2, const StereoFrame &p3,
                                     float t) {
    const auto interpolate = [t](float a, float b, float c, float d) {
        const float t2 = t * t;
        const float t3 = t2 * t;
        return 0.5f * ((2.0f * b) + (-a + c) * t +
                       (2.0f * a - 5.0f * b + 4.0f * c - d) * t2 +
                       (-a + 3.0f * b - 3.0f * c + d) * t3);
    };
    return {clampSample(interpolate(p0.left, p1.left, p2.left, p3.left)),
            clampSample(interpolate(p0.right, p1.right, p2.right, p3.right))};
}

void MonitorOutputPath::render(StereoFrame *output, size_t frameCount) {
    if (frameCount == 0) {
        return;
    }
    if (enabled_.load(std::memory_order_acquire)) {
        outputCallbackFrameCount_.fetch_add(static_cast<int64_t>(frameCount),
                                            std::memory_order_relaxed);
    }
    if (frameCount > kMaxRenderFrames) {
        std::memset(output, 0, frameCount * sizeof(StereoFrame));
        underflowEventCount_.fetch_add(1, std::memory_order_relaxed);
        underflowFrameCount_.fetch_add(static_cast<int64_t>(frameCount),
                                       std::memory_order_relaxed);
        resetRequested_.store(true, std::memory_order_release);
        return;
    }

    const bool reset = resetRequested_.exchange(false, std::memory_order_acq_rel);
    const bool overflow = overflowPending_.exchange(false, std::memory_order_acq_rel);
    if (reset || overflow) {
        renderFadeToSilence(output, frameCount);
        resetConsumerState();
        if (overflow) {
            resyncCount_.fetch_add(1, std::memory_order_relaxed);
        }
        return;
    }

    if (!enabled_.load(std::memory_order_acquire)) {
        renderFadeToSilence(output, frameCount);
        if (fifo_.availableToRead() != 0) {
            resetConsumerState();
        }
        return;
    }

    const size_t available = fifo_.availableToRead();
    fillFrames_.store(static_cast<int32_t>(available), std::memory_order_relaxed);
    const size_t highFillLimit =
        static_cast<size_t>(targetFrames_) +
        static_cast<size_t>(sampleRateHz_ * kMaxBacklogBeyondTargetSeconds);
    if (available > highFillLimit) {
        renderFadeToSilence(output, frameCount);
        resyncCount_.fetch_add(1, std::memory_order_relaxed);
        resetConsumerState();
        return;
    }
    if (priming_) {
        if (available < static_cast<size_t>(targetFrames_) + 3) {
            std::memset(output, 0, frameCount * sizeof(StereoFrame));
            lastOutput_ = {0.0f, 0.0f};
            return;
        }
        priming_ = false;
        previousFrameValid_ = false;
        fadeInRemainingFrames_ =
            static_cast<size_t>(std::max(1.0, sampleRateHz_ * kFadeSeconds));
    }

    const double callbackSeconds =
        static_cast<double>(frameCount) / static_cast<double>(sampleRateHz_);
    const double errorSeconds =
        (static_cast<double>(available) - targetFrames_) / sampleRateHz_;
    integralPpm_ = std::clamp(
        integralPpm_ + kIntegralPpmPerSecondSquared * errorSeconds * callbackSeconds,
        -kMaxIntegralPpm, kMaxIntegralPpm);
    const double requestedPpm = std::clamp(
        kProportionalPpmPerSecond * errorSeconds + integralPpm_,
        -kMaxCorrectionPpm, kMaxCorrectionPpm);
    const double alpha =
        std::clamp(callbackSeconds / kCorrectionSmoothingSeconds, 0.0, 1.0);
    smoothedCorrectionPpm_ += alpha * (requestedPpm - smoothedCorrectionPpm_);
    correctionPpm_.store(static_cast<int32_t>(std::lround(smoothedCorrectionPpm_)),
                         std::memory_order_relaxed);

    const double ratio = 1.0 + smoothedCorrectionPpm_ * 1.0e-6;
    const double endPosition = phase_ + static_cast<double>(frameCount) * ratio;
    const size_t consumed = static_cast<size_t>(std::floor(endPosition));
    const size_t needed = consumed + 3;
    if (needed > scratch_.size() || fifo_.peek(scratch_.data(), needed) != needed) {
        renderFadeToSilence(output, frameCount);
        underflowEventCount_.fetch_add(1, std::memory_order_relaxed);
        underflowFrameCount_.fetch_add(static_cast<int64_t>(frameCount),
                                       std::memory_order_relaxed);
        resyncCount_.fetch_add(1, std::memory_order_relaxed);
        resetConsumerState();
        return;
    }

    double position = phase_;
    for (size_t i = 0; i < frameCount; ++i) {
        const size_t sourceIndex = static_cast<size_t>(position);
        const float fraction =
            static_cast<float>(position - static_cast<double>(sourceIndex));
        const StereoFrame &p1 = scratch_[sourceIndex];
        const StereoFrame &p0 =
            sourceIndex == 0
                ? (previousFrameValid_ ? previousFrame_ : p1)
                : scratch_[sourceIndex - 1];
        output[i] = cubic(p0, p1, scratch_[sourceIndex + 1],
                          scratch_[sourceIndex + 2], fraction);
        position += ratio;
    }

    if (consumed != 0) {
        previousFrame_ = scratch_[consumed - 1];
        previousFrameValid_ = true;
    }
    fifo_.discard(consumed);
    phase_ = endPosition - static_cast<double>(consumed);
    applyFadeIn(output, frameCount);
    lastOutput_ = output[frameCount - 1];
    fillFrames_.store(static_cast<int32_t>(fifo_.availableToRead()),
                      std::memory_order_relaxed);
}

}  // namespace aucampro
