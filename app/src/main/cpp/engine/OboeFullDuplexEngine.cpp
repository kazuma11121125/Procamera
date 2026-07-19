#include "engine/OboeFullDuplexEngine.h"

#include <ctime>
#include <vector>

#include "common/Log.h"

namespace aucampro {

OboeFullDuplexEngine::OboeFullDuplexEngine()
    : highPassFilter_(kStandardSampleRate, kChannelCount),
      eq_(kStandardSampleRate, kChannelCount),
      limiter_(-1.0f),
      meter_(kStandardSampleRate),
      ringBuffer_(kRingBufferCapacityFrames) {}

OboeFullDuplexEngine::~OboeFullDuplexEngine() { stop(); }

Result<std::shared_ptr<oboe::AudioStream>, std::string> OboeFullDuplexEngine::openInputStreamLocked(
    int32_t deviceId, int32_t sampleRateHz) {
    // Fallback ladder per §4.2: try the ideal config first, then relax SharingMode, then
    // relax InputPreset. Every downgrade is logged — never silent.
    //
    // Hi-res capability detection (docs/HIRES_AUDIO_DESIGN.md §3): for a non-standard
    // (hi-res) request, sample-rate conversion is disabled below — Oboe's own docs note
    // "No conversion by Oboe. Underlying APIs may still do conversion", so this alone
    // isn't a hardware guarantee, but combined with this method's post-open
    // getSampleRate()-vs-requested guard (below) it's what makes [start]'s rate fallback
    // ladder trustworthy instead of silently accepting an OS-upsampled fake hi-res stream.
    // The standard (48kHz) path keeps the original Medium-quality conversion behavior
    // unchanged — that's the real-device-proven config this app already shipped with, and
    // still needs conversion allowed for e.g. a mono mic being channel-converted to stereo.
    const bool isHiRes = sampleRateHz != kStandardSampleRate;
    const oboe::SampleRateConversionQuality rateConversionQuality =
        isHiRes ? oboe::SampleRateConversionQuality::None : oboe::SampleRateConversionQuality::Medium;
    struct Attempt {
        oboe::SharingMode sharingMode;
        oboe::InputPreset inputPreset;
        const char *description;
    };
    const Attempt attempts[] = {
        // §4.2's InputPreset fallback ladder. R-channel investigation update (2026-07-16,
        // real-device): InputPreset was never the cause of the R-channel-silent finding —
        // switching it (Unprocessed <-> Camcorder) made no difference on real hardware,
        // confirmed with both the built-in mic and an external mic. The actual cause was
        // PerformanceMode::LowLatency (see below); reverted to Unprocessed-first since
        // that's genuinely the better default (avoids OS-level AGC fighting this app's own
        // InputGain/SafetyLimiter — see OboeFullDuplexEngine.h's doc) now that Camcorder's
        // only justification is gone.
        {oboe::SharingMode::Exclusive, oboe::InputPreset::Unprocessed, "Exclusive+Unprocessed"},
        {oboe::SharingMode::Shared, oboe::InputPreset::Unprocessed, "Shared+Unprocessed (SharingMode fallback)"},
        {oboe::SharingMode::Shared, oboe::InputPreset::VoiceRecognition,
         "Shared+VoiceRecognition (InputPreset fallback)"},
    };

    for (const Attempt &attempt : attempts) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
            // R-channel investigation (2026-07-16, real-device): the MMAP/low-latency
            // capture path on this hardware (Sony SO-51C) silently delivers only the
            // primary mic capsule — L has real signal, R sits at the noise floor
            // (~-98dBFS) — regardless of InputPreset, and regardless of built-in vs.
            // external mic, ruling out "the mic itself is mono". Sony's own Video Pro app
            // gets real stereo because it doesn't use this low-latency path. None (the
            // legacy/non-MMAP path) trades some input latency for correct stereo capture;
            // acceptable since A/V sync goes through PtsClockDomain's PTS math, not
            // wall-clock assumptions about this latency. Monitoring's output stream is
            // deliberately left on LowLatency below — this only affects capture.
            ->setPerformanceMode(oboe::PerformanceMode::None)
            ->setSharingMode(attempt.sharingMode)
            ->setFormat(oboe::AudioFormat::Float)
            ->setSampleRate(sampleRateHz)
            ->setChannelCount(kChannelCount)
            ->setInputPreset(attempt.inputPreset)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true)
            ->setSampleRateConversionQuality(rateConversionQuality)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (deviceId != oboe::kUnspecified) {
            builder.setDeviceId(deviceId);
        }

        std::shared_ptr<oboe::AudioStream> stream;
        const oboe::Result openResult = builder.openStream(stream);
        if (openResult != oboe::Result::OK) {
            AUCAMPRO_LOGW("openInputStream attempt [%s] failed: %s", attempt.description,
                            oboe::convertToText(openResult));
            continue;
        }

        if (&attempt != &attempts[0]) {
            AUCAMPRO_LOGW("Input stream opened via fallback: %s", attempt.description);
        }
        // The device may still silently grant a different config than requested even on
        // success (documented AAudio behavior) — surface that too rather than assume.
        if (stream->getSharingMode() != attempt.sharingMode) {
            AUCAMPRO_LOGW("Input stream SharingMode downgraded by OS to %d",
                            static_cast<int>(stream->getSharingMode()));
        }
        if (stream->getInputPreset() != attempt.inputPreset) {
            AUCAMPRO_LOGW("Input stream InputPreset downgraded by OS to %d",
                            static_cast<int>(stream->getInputPreset()));
        }

        // The entire DSP chain (EQ/limiter/meter) and the ring buffer's frame math
        // hard-assume kChannelCount/sampleRateHz interleaved float frames. setFormat/
        // SampleRate/ChannelConversionAllowed(true) above ask Oboe to make that true
        // regardless of the device's native config, but this has not been verified on a
        // real device (§ "確信度の明示" — no hardware available in this environment), so
        // fail loudly here rather than silently misinterpret e.g. a mono stream as
        // interleaved stereo (which would corrupt every sample, not just degrade quality).
        if (stream->getChannelCount() != kChannelCount || stream->getSampleRate() != sampleRateHz) {
            AUCAMPRO_LOGE("Input stream opened with unexpected config: channels=%d rate=%d (expected %d/%d)",
                            stream->getChannelCount(), stream->getSampleRate(), kChannelCount, sampleRateHz);
            stream->close();
            return Result<std::shared_ptr<oboe::AudioStream>, std::string>::Err(
                "Input stream channel/sample-rate conversion did not produce the expected format");
        }

        // TEMPORARY diagnostic (2026-07-15 real-device R-channel investigation) — logs
        // which physical input device AAudio actually routed to, and its declared channel
        // mask, so this can be cross-referenced against `dumpsys audio`'s device list to
        // check whether the "stereo" stream we open is genuinely a 2-mic array device or a
        // mono device AAudio is silently channel-converting (which would explain a
        // consistently-silent second channel far better than the InputPreset choice does).
        AUCAMPRO_LOGI("Input stream opened: deviceId=%d channelCount=%d sampleRate=%d",
                        stream->getDeviceId(), stream->getChannelCount(), stream->getSampleRate());

        return Result<std::shared_ptr<oboe::AudioStream>, std::string>::Ok(stream);
    }

    return Result<std::shared_ptr<oboe::AudioStream>, std::string>::Err(
        "All input stream open attempts failed (Exclusive/Shared x Unprocessed/VoiceRecognition)");
}

Result<void, std::string> OboeFullDuplexEngine::start(int32_t preferredInputDeviceId,
                                                          int32_t requestedSampleRateHz) {
    std::lock_guard<std::mutex> lock(streamMutex_);

    // 実機で発見 (2026-07-18, monitor-noise investigation): this used to assign straight
    // into inputStream_/leave outputStream_ untouched, silently trusting that callers never
    // call [start] again while a stream from a previous call is still live. That assumption
    // is false in practice — confirmed on-device via logcat, this app's own init sequence
    // opens the input stream once at the default 48kHz, opens the monitor output stream
    // (if monitoring was restored on from saved settings) against *that* rate, and only
    // *then* re-calls [start] at the user's actual saved Hi-Res rate, with no [stop] in
    // between. The old inputStream_ leaked (never closed — its callback could in theory
    // still fire concurrently with the new stream's), and the monitor output stream kept
    // running at the stale rate for the rest of the session — onAudioReady()'s passthrough
    // write() always assumes numFrames is tagged at the *current* sampleRateHz_, so this is
    // exactly what turned into the reported "モニター音声がノイズだらけ→その後無音"
    // (garbled audio from the rate mismatch, then silence once the output stream's internal
    // buffer stayed permanently backed up). Closing both unconditionally here — whatever
    // rate this call ends up granting, neither stale stream may survive it.
    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }
    if (outputStream_) {
        monitorOutputPath_.setEnabled(false);
        outputStream_->requestStop();
        outputStream_->close();
        outputStream_.reset();
    }

    // Rate fallback ladder (docs/HIRES_AUDIO_DESIGN.md §3): descend from
    // requestedSampleRateHz through the standard rungs, skipping any rung above the
    // request. kStandardSampleRate is always included and tried last, so this always has
    // at least one candidate and (barring a device-level failure unrelated to rate) always
    // succeeds — same "never silently fail to record" guarantee as the SharingMode/
    // InputPreset ladder inside openInputStreamLocked.
    static constexpr int32_t kRateLadder[] = {192000, 96000, kStandardSampleRate};
    std::string lastError = "no candidate sample rate attempted";
    for (int32_t candidateRateHz : kRateLadder) {
        if (candidateRateHz > requestedSampleRateHz) {
            continue;
        }
        auto openResult = openInputStreamLocked(preferredInputDeviceId, candidateRateHz);
        if (openResult.isErr()) {
            lastError = openResult.error();
            continue;
        }

        if (candidateRateHz != requestedSampleRateHz) {
            AUCAMPRO_LOGW("Hi-res sample rate fallback: requested %d, granted %d",
                            requestedSampleRateHz, candidateRateHz);
        }
        inputStream_ = openResult.value();
        sampleRateHz_ = candidateRateHz;
        // Keeps the user's existing HighPass/EQ settings intact across the rate change —
        // see BiquadEq.h/HighPassFilter.h's setSampleRate() docs for why this recomputes
        // in place rather than needing these objects reconstructed.
        highPassFilter_.setSampleRate(sampleRateHz_);
        eq_.setSampleRate(sampleRateHz_);
        meter_.setSampleRate(sampleRateHz_);

        const oboe::Result startResult = inputStream_->requestStart();
        if (startResult != oboe::Result::OK) {
            inputStream_->close();
            inputStream_.reset();
            return Result<void, std::string>::Err(std::string("requestStart failed: ") +
                                                    oboe::convertToText(startResult));
        }
        return Result<void, std::string>::Ok();
    }

    return Result<void, std::string>::Err("All sample-rate fallback attempts failed: " + lastError);
}

void OboeFullDuplexEngine::stop() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    monitorOutputPath_.setEnabled(false);

    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }

    if (outputStream_) {
        outputStream_->requestStop();
        outputStream_->close();
        outputStream_.reset();
    }
}

Result<void, std::string> OboeFullDuplexEngine::reopenInputStream(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(streamMutex_);

    timespec gapStart{};
    clock_gettime(CLOCK_MONOTONIC, &gapStart);

    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }
    monitorOutputPath_.requestReset();

    // Device hot-swap only — keeps the engine's current sampleRateHz_ (set by [start]),
    // never changes rate here. A rate change is a distinct, explicit user action (Settings
    // quality picker) handled entirely by re-calling [start], not by this method.
    auto openResult = openInputStreamLocked(deviceId, sampleRateHz_);
    if (openResult.isErr()) {
        return Result<void, std::string>::Err(openResult.error());
    }
    inputStream_ = openResult.value();

    // Cover the capture gap before the new callback producer starts. The old Kotlin path
    // called insertSilence() only after requestStart(), so this nominally-SPSC ring had
    // two concurrent producers (the new Oboe callback and the caller thread). Producing
    // the silence here, while the old stream is closed and the new one has not started,
    // preserves the single-producer invariant.
    timespec gapEnd{};
    clock_gettime(CLOCK_MONOTONIC, &gapEnd);
    const int64_t gapNanos =
        (static_cast<int64_t>(gapEnd.tv_sec) - static_cast<int64_t>(gapStart.tv_sec)) * 1'000'000'000LL +
        (static_cast<int64_t>(gapEnd.tv_nsec) - static_cast<int64_t>(gapStart.tv_nsec));
    const size_t gapFrames = static_cast<size_t>(
        (gapNanos * static_cast<int64_t>(sampleRateHz_)) / 1'000'000'000LL);
    if (gapFrames > 0) {
        std::vector<StereoFrame> silence(gapFrames, StereoFrame{0.0f, 0.0f});
        const size_t written = ringBuffer_.write(silence.data(), silence.size());
        if (written < silence.size()) {
            ringBufferOverrunCount_.fetch_add(1, std::memory_order_relaxed);
            ringBufferDroppedFrameCount_.fetch_add(
                static_cast<int64_t>(silence.size() - written), std::memory_order_relaxed);
        }
        updateRingBufferHighWater(ringBuffer_.availableToRead());
    }

    const oboe::Result startResult = inputStream_->requestStart();
    if (startResult != oboe::Result::OK) {
        inputStream_->close();
        inputStream_.reset();
        return Result<void, std::string>::Err(std::string("requestStart failed: ") +
                                                oboe::convertToText(startResult));
    }
    return Result<void, std::string>::Ok();
}

Result<void, std::string> OboeFullDuplexEngine::setMonitoringEnabled(bool enabled, int32_t outputDeviceId) {
    std::lock_guard<std::mutex> lock(streamMutex_);

    if (!enabled) {
        monitorOutputPath_.setEnabled(false);
        return Result<void, std::string>::Ok();
    }

    if (outputStream_) {
        monitorOutputPath_.setEnabled(true);
        return Result<void, std::string>::Ok();
    }

    // sampleRateHz_ (the engine's current *input* rate), not a fixed constant — a
    // real-device-relevant fix (docs/HIRES_AUDIO_DESIGN.md §4/§6.5): onAudioReady() below
    // writes each callback's numFrames straight through to this output stream, so if the
    // engine is running hi-res (e.g. 96kHz) while this stream opened at a stale 48kHz, the
    // monitor passthrough would play back at roughly 2x speed/pitch.
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        // The output is callback-driven now, so it no longer has to fit an entire
        // PerformanceMode::None input callback into one non-blocking write. LowLatency
        // gives the HAL small regular pull blocks while the separate FIFO provides the
        // deliberate ~40ms jitter/drift reserve.
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setSampleRate(sampleRateHz_)
        ->setChannelCount(kChannelCount)
        ->setUsage(oboe::Usage::Media)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(this)
        // Diagnostic only (2026-07-18, monitor OFF→ON silence investigation) — unlike
        // the input stream, onErrorAfterClose() doesn't attempt any live recovery for
        // this direction yet. This makes a genuine real-device disconnect of the
        // monitor output visible in logcat instead of silent.
        ->setErrorCallback(this);
    if (outputDeviceId != oboe::kUnspecified) {
        builder.setDeviceId(outputDeviceId);
    }

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result openResult = builder.openStream(stream);
    if (openResult != oboe::Result::OK) {
        return Result<void, std::string>::Err(std::string("monitor output openStream failed: ") +
                                                oboe::convertToText(openResult));
    }

    // 実機で発見 (2026-07-18): unlike openInputStreamLocked, this never verified what
    // Oboe/AAudio actually granted vs. what was requested (sampleRateHz_) — the same
    // "the OS may silently grant a different config even on success" risk this header's
    // own doc already calls out for a hi-res rate ("if this stream opened at a stale
    // 48kHz [...] monitor passthrough would play back at roughly 2x speed/pitch"), just
    // never actually guarded against. onAudioReady()'s passthrough write() below always
    // assumes the output stream's rate equals sampleRateHz_ (it just forwards each
    // callback's numFrames as-is) — a silent mismatch is exactly what turns into the
    // reported "モニター音声がノイズだらけ" at hi-res rates. Fail loudly instead, same as
    // the input path: [RecordingPipeline.setMonitoringEnabled] already treats a non-null
    // error here as "leave monitoring off and snap the UI toggle back."
    if (stream->getChannelCount() != kChannelCount || stream->getSampleRate() != sampleRateHz_) {
        AUCAMPRO_LOGE("Monitor output stream opened with unexpected config: channels=%d rate=%d (expected %d/%d)",
                        stream->getChannelCount(), stream->getSampleRate(), kChannelCount, sampleRateHz_);
        stream->close();
        return Result<void, std::string>::Err(
            "Monitor output stream channel/sample-rate did not match the input engine's current rate");
    }

    const int32_t inputFramesPerCallback =
        inputStream_ ? inputStream_->getFramesPerBurst() : 0;
    const int32_t targetBufferFrames = stream->getFramesPerBurst() * 2;
    if (targetBufferFrames > 0) {
        const auto setBufferResult = stream->setBufferSizeInFrames(targetBufferFrames);
        if (!setBufferResult) {
            AUCAMPRO_LOGW("Monitor output setBufferSizeInFrames(%d) failed: %s", targetBufferFrames,
                            oboe::convertToText(setBufferResult.error()));
        }
    }

    monitorOutputPath_.configure(sampleRateHz_, inputFramesPerCallback,
                                 stream->getFramesPerBurst());
    const oboe::Result startResult = stream->requestStart();
    if (startResult != oboe::Result::OK) {
        monitorOutputPath_.setEnabled(false);
        stream->close();
        return Result<void, std::string>::Err(std::string("monitor output requestStart failed: ") +
                                                oboe::convertToText(startResult));
    }

    // Diagnostic (2026-07-18, monitor-noise investigation): the success path used to log
    // nothing at all, making "monitoring is silently on and working" indistinguishable in
    // logcat from "the toggle never actually reached this code." Cheap and permanent, same
    // spirit as openInputStreamLocked's analogous AUCAMPRO_LOGI.
    // TEMPORARY: also logs the input stream's own burst/buffer sizing alongside the
    // output side's (2026-07-18, monitor-noise investigation) — the two streams are
    // opened independently (separate AAudio streams, possibly separate physical clocks),
    // so a burst-size or capacity mismatch between them would explain shortfalls that
    // persist even after giving the output stream its own multi-burst buffer headroom.
    AUCAMPRO_LOGI("Monitor output stream opened: deviceId=%d channelCount=%d sampleRate=%d "
                    "framesPerBurst=%d bufferSizeInFrames=%d bufferCapacityInFrames=%d "
                    "| input framesPerBurst=%d bufferSizeInFrames=%d bufferCapacityInFrames=%d",
                    stream->getDeviceId(), stream->getChannelCount(), stream->getSampleRate(),
                    stream->getFramesPerBurst(), stream->getBufferSizeInFrames(), stream->getBufferCapacityInFrames(),
                    inputStream_ ? inputStream_->getFramesPerBurst() : -1,
                    inputStream_ ? inputStream_->getBufferSizeInFrames() : -1,
                    inputStream_ ? inputStream_->getBufferCapacityInFrames() : -1);

    outputStream_ = stream;
    // Enable production only after the output callback is actually running. Enabling
    // before requestStart() lets the 3840-frame input callback fill the monitor FIFO
    // while the blocking start handshake is still in progress.
    monitorOutputPath_.setEnabled(true);
    return Result<void, std::string>::Ok();
}

void OboeFullDuplexEngine::setEqBandParams(int band, float freqHz, float q, float gainDb) {
    eq_.setBandParams(band, freqHz, q, gainDb);
}

int32_t OboeFullDuplexEngine::hardwareXRunCount() const {
    // §9: inputStream_ is reassigned under streamMutex_ by start()/stop()/
    // reopenInputStream() (e.g. on device hot-swap), so reading it without the same lock
    // is a real shared_ptr data race, not just a stale-value risk.
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!inputStream_) {
        return 0;
    }
    oboe::ResultWithValue<int32_t> result = inputStream_->getXRunCount();
    return result ? result.value() : 0;
}

int32_t OboeFullDuplexEngine::monitorOutputXRunCount() const {
    if (!outputStream_) {
        return 0;
    }
    const oboe::ResultWithValue<int32_t> result = outputStream_->getXRunCount();
    return result ? result.value() : 0;
}

bool OboeFullDuplexEngine::getInputTimestamp(int64_t *outFramePosition, int64_t *outTimeNanos) const {
    // §9: same race as hardwareXRunCount() above — this also feeds AudioEncoder's PTS
    // anchor (seedAudioAnchor()), so an unsynchronized read here could in theory corrupt
    // recorded-audio timing, not just report a stale diagnostic.
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!inputStream_) {
        return false;
    }
    oboe::ResultWithValue<oboe::FrameTimestamp> result = inputStream_->getTimestamp(CLOCK_MONOTONIC);
    if (!result) {
        return false;
    }
    *outFramePosition = result.value().position;
    *outTimeNanos = result.value().timestamp;
    return true;
}

size_t OboeFullDuplexEngine::drainEncoderBuffer(float *dst, size_t maxFrames) {
    auto *frames = reinterpret_cast<StereoFrame *>(dst);
    return ringBuffer_.read(frames, maxFrames);
}

void OboeFullDuplexEngine::flushRingBuffer() {
    ringBuffer_.clear();
    ringBufferOverrunCount_.store(0, std::memory_order_relaxed);
    ringBufferDroppedFrameCount_.store(0, std::memory_order_relaxed);
    ringBufferHighWaterFrames_.store(0, std::memory_order_relaxed);
}

void OboeFullDuplexEngine::updateRingBufferHighWater(size_t fillFrames) {
    const int32_t fill = static_cast<int32_t>(fillFrames);
    int32_t previous = ringBufferHighWaterFrames_.load(std::memory_order_relaxed);
    while (fill > previous &&
           !ringBufferHighWaterFrames_.compare_exchange_weak(
               previous, fill, std::memory_order_relaxed, std::memory_order_relaxed)) {
    }
}

oboe::DataCallbackResult OboeFullDuplexEngine::onAudioReady(oboe::AudioStream *stream, void *audioData,
                                                              int32_t numFrames) {
    if (stream->getDirection() == oboe::Direction::Output) {
        monitorOutputPath_.render(static_cast<StereoFrame *>(audioData),
                                  static_cast<size_t>(numFrames));
        return oboe::DataCallbackResult::Continue;
    }

    auto *samples = static_cast<float *>(audioData);
    const size_t frameCount = static_cast<size_t>(numFrames);
    const size_t sampleCount = frameCount * kChannelCount;

    inputGain_.process(samples, sampleCount);
    highPassFilter_.process(samples, static_cast<size_t>(numFrames));
    eq_.process(samples, static_cast<size_t>(numFrames));
    makeupGain_.process(samples, sampleCount);
    limiter_.process(samples, sampleCount);
    meter_.process(samples, static_cast<size_t>(numFrames), kChannelCount);

    const auto *frames = reinterpret_cast<const StereoFrame *>(samples);
    const size_t writtenFrames = ringBuffer_.writeAllOrNothing(frames, frameCount);
    if (writtenFrames != frameCount) {
        // Encoder thread is falling behind the mic; this is the same kind of
        // backpressure condition §4.4 asks us to surface for the Muxer queue, applied
        // here to the audio ring buffer. Drop the complete callback rather than a scalar
        // prefix: splitting a stereo frame would shift L/R alignment and turn a single
        // overrun into persistent garbled noise. RT-safe: atomics only.
        ringBufferOverrunCount_.fetch_add(1, std::memory_order_relaxed);
        ringBufferDroppedFrameCount_.fetch_add(
            static_cast<int64_t>(frameCount), std::memory_order_relaxed);
    } else {
        updateRingBufferHighWater(ringBuffer_.availableToRead());
    }
    monitorOutputPath_.push(frames, frameCount);

    return oboe::DataCallbackResult::Continue;
}

void OboeFullDuplexEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    // Runs on an Oboe-internal thread, not the audio callback thread itself, but still
    // treated as non-blocking/log-only here; Kotlin's AudioDeviceRouter (§4.2) requests
    // the reopen from a normal coroutine context. reopenInputStream() measures and fills
    // the gap before the replacement callback starts, preserving the SPSC ring contract.
    AUCAMPRO_LOGW("Stream closed after error: %s (direction=%d)", oboe::convertToText(error),
                    static_cast<int>(stream->getDirection()));
}

}  // namespace aucampro
