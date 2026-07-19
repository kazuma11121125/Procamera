#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include <oboe/Oboe.h>

#include "buffer/SpscRingBuffer.h"
#include "common/Result.h"
#include "dsp/BiquadEq.h"
#include "dsp/EncoderPcmConverter.h"
#include "dsp/HighPassFilter.h"
#include "dsp/InputGain.h"
#include "dsp/MakeupGain.h"
#include "dsp/PeakRmsMeter.h"
#include "dsp/SafetyLimiter.h"
#include "engine/MonitorOutputPath.h"

namespace aucampro {

// Owns the Oboe input stream (mic capture, always on while recording) and an optional
// output stream (headphone monitor passthrough, §4.2). The input callback runs the DSP chain (input gain ->
// high-pass filter -> EQ -> makeup gain -> safety limiter -> meter) and pushes the result into a lock-free ring buffer that
// the (non-RT) Audio Encoder thread drains from Kotlin, plus an entirely separate monitor
// FIFO. The output callback pulls from that monitor FIFO and performs bounded clock-drift
// correction. Every method other than onAudioReady() /
// onErrorAfterClose() runs on a non-RT caller (UI/coroutine thread via JNI) and may take
// locks / allocate freely; onAudioReady() itself touches nothing but already-owned
// objects and their RT-safe methods.
class OboeFullDuplexEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    OboeFullDuplexEngine();
    ~OboeFullDuplexEngine() override;

    static constexpr int32_t kStandardSampleRate = 48000;
    // Hi-res ladder ceiling (docs/HIRES_AUDIO_DESIGN.md §2) — the ring buffer is sized for
    // this regardless of the currently-active rate (see kRingBufferCapacityFrames) so a
    // rate change never needs to resize/recreate it mid-session.
    static constexpr int32_t kMaxSampleRate = 192000;
    static constexpr int32_t kChannelCount = 2;
    // At least 20 seconds of stereo audio headroom between the RT producer and the
    // Encoder-thread consumer at the maximum supported rate. SpscRingBuffer rounds this
    // hint to 2^22 frames, which is ~21.8s at 192kHz and a 32MiB fixed allocation.
    // A real SO-51C stress take showed that a ~10.9s buffer could be exhausted by one
    // severe system-load burst even after native conversion made steady-state processing
    // comfortably faster than real time. Losing frames here damages both WAV and MP4, so
    // another 16MiB is a deliberate recording-integrity reserve, not a throughput fix.
    static constexpr size_t kRingBufferCapacityFrames = static_cast<size_t>(kMaxSampleRate) * 20;

    // UI/coroutine thread only. Opens and starts the input stream (and the output stream
    // too, if monitoring was already requested) at [requestedSampleRateHz], implementing
    // both the SharingMode::Exclusive -> Shared and InputPreset::Unprocessed ->
    // VoiceRecognition fallback ladder from §4.2 AND (docs/HIRES_AUDIO_DESIGN.md §3) a
    // sample-rate fallback ladder (192000 -> 96000 -> kStandardSampleRate, entering at
    // whichever of those is <= [requestedSampleRateHz]) — kStandardSampleRate is always
    // the last rung and is guaranteed to succeed on any device this app already supports,
    // so this method never fails purely because a hi-res rate wasn't available; it only
    // fails for the same device-level reasons the pre-hi-res code could already fail for.
    // Every rate/SharingMode/InputPreset downgrade is logged — never silent. The actually-
    // granted rate is queryable afterward via [sampleRateHz].
    Result<void, std::string> start(int32_t preferredInputDeviceId,
                                     int32_t requestedSampleRateHz = kStandardSampleRate);

    // Any thread. The engine's actual current sample rate — what [start] most recently
    // succeeded at, after any fallback. UI/coroutine callers use this (via JNI) to display
    // what hi-res mode actually landed on rather than what was requested (same "never
    // silently fake it" principle as AudioDeviceRouter's device-label reporting).
    int32_t sampleRateHz() const { return sampleRateHz_; }

    // UI/coroutine thread only. Stops and closes both streams.
    void stop();

    // UI/coroutine thread only. Closes and reopens just the input stream on a new device
    // (§4.2 device hot-swap handling). The ring buffer / output stream / DSP state are
    // left untouched so recording continuity (and Audio PTS's cumulative-sample-count
    // basis) is preserved across the switch. This method measures the close/open gap and
    // writes matching silence before starting the new callback, so the SPSC ring never
    // has a second producer racing the Oboe callback.
    Result<void, std::string> reopenInputStream(int32_t deviceId);

    // UI/coroutine thread only. Per §4.2, Kotlin's AudioDeviceRouter is the sole
    // authority on "is the current output device wired/USB headphones" — this method
    // trusts that decision and does not re-derive it; passing enabled=true while routed
    // to the built-in speaker would violate the anti-howling requirement, so callers must
    // gate this correctly before calling.
    //
    // Once opened, the output callback remains alive for the session and emits silence
    // while disabled. Re-enabling resets and primes the monitor FIFO, avoiding stale
    // audio and keeping toggle operations out of both RT callbacks.
    Result<void, std::string> setMonitoringEnabled(bool enabled, int32_t outputDeviceId);

    // UI/coroutine thread only.
    void setEqBandParams(int band, float freqHz, float q, float gainDb);

    // UI/coroutine thread only. §4.2 風切り音/ハンドリングノイズ対策のローカット —
    // see dsp/HighPassFilter.h for why this is first in the chain (before the EQ) and how
    // enable/disable avoids a click.
    void setHighPassEnabled(bool enabled) { highPassFilter_.setEnabled(enabled); }
    void setHighPassCutoffHz(float cutoffHz) { highPassFilter_.setCutoffHz(cutoffHz); }

    // UI/coroutine thread only. Manual record-level control (audio.pdf調査の§Layer1相当
    // — InputPreset::Unprocessedで無効化したOSのAGCの代わりに、110-125dB SPLのような
    // 大音量ライブの現場でユーザー自身がレベルを追い込むための唯一の手段)。
    // See dsp/InputGain.h for what this can and cannot do.
    void setInputGainDb(float gainDb) { inputGain_.setGainDb(gainDb); }

    // UI/coroutine thread only. Optional post-EQ loudness boost, default 0dB/bypass — see
    // dsp/MakeupGain.h for how this differs from setInputGainDb above (opposite end of the
    // gain-staging range: boosting a source too quiet for InputGain's own limited headroom
    // to fully compensate, at the cost of also raising the noise floor).
    void setMakeupGainDb(float gainDb) { makeupGain_.setGainDb(gainDb); }

    // Any thread (JNI pull accessors). channel: 0 = left, 1 = right.
    float peakDb(int channel) const { return meter_.peakDb(channel); }
    float rmsDb(int channel) const { return meter_.rmsDb(channel); }
    int32_t ringBufferOverrunCount() const { return ringBufferOverrunCount_.load(std::memory_order_relaxed); }
    int64_t ringBufferDroppedFrameCount() const {
        return ringBufferDroppedFrameCount_.load(std::memory_order_relaxed);
    }
    int32_t ringBufferFillFrames() const {
        return static_cast<int32_t>(ringBuffer_.availableToRead());
    }
    int32_t ringBufferHighWaterFrames() const {
        return ringBufferHighWaterFrames_.load(std::memory_order_relaxed);
    }
    int32_t hardwareXRunCount() const;

    // Monitor-only diagnostics. None of these counters describe the recording path.
    int32_t monitorBufferFillFrames() const { return monitorOutputPath_.fillFrames(); }
    int32_t monitorBufferTargetFrames() const { return monitorOutputPath_.targetFrames(); }
    int32_t monitorCorrectionPpm() const { return monitorOutputPath_.correctionPpm(); }
    int32_t monitorUnderflowCount() const { return monitorOutputPath_.underflowEventCount(); }
    int64_t monitorUnderflowFrameCount() const {
        return monitorOutputPath_.underflowFrameCount();
    }
    int32_t monitorOverflowCount() const { return monitorOutputPath_.overflowEventCount(); }
    int64_t monitorOverflowDroppedFrameCount() const {
        return monitorOutputPath_.overflowDroppedFrameCount();
    }
    int32_t monitorResyncCount() const { return monitorOutputPath_.resyncCount(); }
    int64_t monitorInputCallbackFrameCount() const {
        return monitorOutputPath_.inputCallbackFrameCount();
    }
    int64_t monitorOutputCallbackFrameCount() const {
        return monitorOutputPath_.outputCallbackFrameCount();
    }
    int32_t monitorOutputXRunCount() const;

    // UI/coroutine thread only — NOT the audio callback thread (Oboe's own docs advise
    // against calling getTimestamp() from onAudioReady, and it isn't needed there: this
    // exists to seed Kotlin's PtsClockDomain audio anchor once, shortly after start(),
    // not to be polled per callback). Correlates a frame position to a true CLOCK_MONOTONIC
    // capture time, which Kotlin uses to back-calculate the true capture time of sample 0
    // (anchorNanos = timeNanos - framePosition * 1e9 / sampleRate) — this avoids anchoring
    // to the wall-clock arrival time of the first callback, which would be offset from the
    // true capture time by the audio pipeline's input latency (§4.3's A/V sync budget is
    // tight enough that this offset matters; see docs/ARCHITECTURE.md). Returns false if
    // the platform/stream doesn't support it yet (e.g. queried too soon after start()).
    bool getInputTimestamp(int64_t *outFramePosition, int64_t *outTimeNanos) const;

    // Audio Encoder thread only. Drains up to maxFrames frames (interleaved stereo) from
    // the ring buffer; returns the number of frames actually read.
    size_t drainEncoderBuffer(float *dst, size_t maxFrames);
    bool resetEncoderPcmConverter(int32_t inputSampleRateHz,
                                  int32_t outputSampleRateHz,
                                  int32_t channelCount,
                                  uint32_t randomSeed) {
        return encoderPcmConverter_.reset(inputSampleRateHz, outputSampleRateHz,
                                          channelCount, randomSeed);
    }
    size_t convertEncoderPcm(const float *input, size_t frameCount,
                             int16_t *output) {
        return encoderPcmConverter_.process(input, frameCount, output);
    }
    size_t encoderPcmOutputFrameUpperBound(size_t inputFrames) const {
        return encoderPcmConverter_.outputFrameUpperBound(inputFrames);
    }

    // UI/coroutine thread only, before an Audio Encoder starts draining. Discards whatever
    // is currently sitting in the ring buffer (§4.2's persistent audio engine means it may
    // hold a stale backlog — up to kRingBufferCapacityFrames worth, frozen there since
    // nothing drains it during preview-only — or nothing at all if preview ran long enough
    // to overflow it and every write since has been silently dropped as an overrun). Either
    // way, a fresh AudioEncoder must start its cumulative-sample-count basis (§4.3) from the
    // stream's *current* frame position (see getInputTimestamp's framePosition), not from
    // whatever stale data happened to be sitting here — otherwise the PTS this recording
    // computes for its first frames lands seconds-to-minutes in the past relative to
    // recordingStartNanos and normalizeAudioPtsUs's monotonic guard silently drops them
    // until enough new frames drain to close that gap, which for a long preview-then-record
    // gap can silently eat the entire recording (real-device finding, see
    // docs/ARCHITECTURE.md).
    void flushRingBuffer();

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    Result<std::shared_ptr<oboe::AudioStream>, std::string> openInputStreamLocked(int32_t deviceId,
                                                                                     int32_t sampleRateHz);
    void updateRingBufferHighWater(size_t fillFrames);

    // Runtime engine rate (docs/HIRES_AUDIO_DESIGN.md §4/§6.5) — only ever written from
    // [start] (streamMutex_ held), after a stream has actually been granted this rate.
    // highPassFilter_/eq_/meter_ are kept in sync with it via their own setSampleRate()
    // calls in [start] (see that method) rather than being destroyed/reconstructed, so a
    // rate change doesn't lose the user's HighPass/EQ settings.
    int32_t sampleRateHz_ = kStandardSampleRate;

    InputGain inputGain_;
    HighPassFilter highPassFilter_;
    ThreeBandEq eq_;
    MakeupGain makeupGain_;
    SafetyLimiter limiter_;
    PeakRmsMeter meter_;
    // Audio Encoder thread only; reset once per take before conversion begins.
    EncoderPcmConverter encoderPcmConverter_;
    // Frame-typed rather than scalar-float-typed: even under backpressure, a write/read
    // can never split L from R and permanently shift the channel interleave.
    SpscRingBuffer<StereoFrame> ringBuffer_;
    MonitorOutputPath monitorOutputPath_;

    // Guards stream open/close/reopen sequencing; never held during onAudioReady().
    // mutable: also taken by the const diagnostic getters below (hardwareXRunCount(),
    // getInputTimestamp()) so they can't race a concurrent reopenInputStream()/start()/
    // stop() reassigning inputStream_ out from under an unlocked read (real UB, not just
    // a stale-value risk — see those methods' definitions in the .cpp).
    mutable std::mutex streamMutex_;
    // Only ever touched from UI-thread methods under streamMutex_; onAudioReady() reads
    // its `stream` parameter from Oboe directly and never dereferences this field.
    std::shared_ptr<oboe::AudioStream> inputStream_;

    // UI-thread-owned. The output callback receives its stream pointer directly from
    // Oboe and only touches monitorOutputPath_; the input callback never dereferences
    // outputStream_, so stream close no longer races a cross-stream write.
    std::shared_ptr<oboe::AudioStream> outputStream_;

    std::atomic<int32_t> ringBufferOverrunCount_{0};
    std::atomic<int64_t> ringBufferDroppedFrameCount_{0};
    std::atomic<int32_t> ringBufferHighWaterFrames_{0};
};

}  // namespace aucampro
