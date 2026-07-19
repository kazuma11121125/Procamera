package com.aucampro.recorder.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import com.aucampro.recorder.audio.NativeEngineBridge
import com.aucampro.recorder.muxer.PtsClockDomain
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Dedicated thread (§4.4: "専用エンコーダスレッドがRingBufferからドレインしqueueInputBuffer")
 * draining the native SPSC ring buffer, converting Float32 -> 16-bit PCM with anti-alias
 * decimation and TPDF dither in [NativeEngineBridge], and feeding MediaCodec's AAC-LC
 * encoder in synchronous buffer mode. Audio PTS is derived purely from cumulative sample count (§4.3,
 * drift-free) via [ptsClockDomain] — never from this thread's wall-clock timing.
 *
 * Untested framework glue (needs a live MediaCodec + running NativeEngineBridge) — see
 * docs/ARCHITECTURE.md's note on compile-verified-only status pending Phase 4's
 * end-to-end wiring.
 *
 * **Hi-res audio (docs/HIRES_AUDIO_DESIGN.md)**: [captureSampleRateHz] is the *engine's*
 * actual running rate (may exceed [sampleRateHz], which stays the AAC/MP4 track's fixed
 * 48kHz target — see that doc's §4 "大原則"). When they differ, this class is the fan-out
 * point (the *sole* consumer of the SPSC ring buffer — see [NativeEngineBridge]'s class
 * doc): every drained block is written to [hiResSink] unmodified (raw, at
 * [captureSampleRateHz]), then converted natively down to [sampleRateHz] before
 * encoding to AAC. [cumulativeSampleCount] and every
 * PTS derived from it stay in **48kHz-equivalent frame units** throughout — see
 * [seedAudioAnchor]'s doc for the one place that distinction is easy to get backwards.
 */
class AudioEncoder(
    private val sampleRateHz: Int,
    private val channelCount: Int,
    bitrate: Int,
    private val nativeEngine: NativeEngineBridge,
    private val ptsClockDomain: PtsClockDomain,
    private val callback: Callback,
    private val captureSampleRateHz: Int = sampleRateHz,
    private val hiResSink: HiResAudioSink? = null,
) {
    interface Callback {
        fun onOutputFormatChanged(format: MediaFormat)
        fun onEncodedFrame(buffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
        fun onError(exception: Exception)
    }

    init {
        require(captureSampleRateHz % sampleRateHz == 0) {
            "captureSampleRateHz ($captureSampleRateHz) must be an integer multiple of sampleRateHz ($sampleRateHz)"
        }
    }

    private val decimationFactor = captureSampleRateHz / sampleRateHz

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var cumulativeSampleCount = 0L
    private var formatAnnounced = false

    // §12/§14 of docs/AUDIO_INSTABILITY_INVESTIGATION_2026-07-18.md called for these as
    // "必須診断値" but only a per-event log line existed (queueInput()'s retry-recovered
    // Log.w) — nothing queryable to correlate against an audible glitch's timestamp during
    // a live repro session. Written only from [drainThread] (inside [queueInput]); read
    // from any thread (e.g. a periodic diagnostic poller), same pattern as
    // [NativeEngineBridge]'s ring-buffer counters.
    private val aacInputRetryCount = AtomicLong(0)
    private val aacInputMaxWaitNanos = AtomicLong(0)
    private val aacInputTimeoutFailureCount = AtomicLong(0)

    /** Cumulative [MediaCodec.dequeueInputBuffer] retries this recording has needed so far
     * (0 under normal conditions). Any thread. */
    fun aacInputRetryCount(): Long = aacInputRetryCount.get()

    /** Longest single [queueInput] call so far, including all its retries. Any thread. */
    fun aacInputMaxWaitNanos(): Long = aacInputMaxWaitNanos.get()

    /** How many times [queueInput] exhausted its retry budget and threw (each such
     * failure also stops the recording via [Callback.onError], so this is normally either
     * 0 or 1 per take). Any thread. */
    fun aacInputTimeoutFailureCount(): Long = aacInputTimeoutFailureCount.get()

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    /**
     * Starts the encoder and the drain thread. [seedAudioAnchor] (which retries against
     * [NativeEngineBridge.getInputTimestamp] for frame-correlation accuracy — see its doc
     * for why a raw `startAudioAnchor()` call must not be substituted there, since that
     * would silently reintroduce the input-latency offset the frame-correlation path
     * exists to remove) runs on [drainThread], not here: real-device finding — this
     * caller is [com.aucampro.recorder.pipeline.RecordingPipeline.startRecording], which
     * runs the camera session reconfiguration right after this call returns, so blocking
     * here for the anchor's up-to-~2s retry budget delayed that reconfiguration by the
     * same amount, showing up as the first few seconds of video being frozen/blacked out
     * after tapping record. Callers must ensure [nativeEngine] is already started (audio
     * callbacks flowing) before calling this, so a correlation becomes available within
     * the retry budget.
     */
    fun start() {
        check(
            nativeEngine.resetEncoderPcmConverter(
                inputSampleRateHz = captureSampleRateHz,
                outputSampleRateHz = sampleRateHz,
                channelCount = channelCount,
                randomSeed = System.nanoTime().toInt(),
            ),
        ) {
            "Failed to configure native encoder PCM converter: " +
                "${captureSampleRateHz}Hz -> ${sampleRateHz}Hz, channels=$channelCount"
        }
        codec.start()
        running.set(true)
        drainThread = thread(name = "AudioEncoderDrain", priority = Thread.MAX_PRIORITY) {
            // This is a recording-critical real-time consumer, not background encoding:
            // if Android starves it while the 192kHz capture callback keeps producing,
            // the finite native ring eventually overruns and both WAV and MP4 lose the
            // same interval. A five-minute SO-51C stress take reproduced exactly that
            // under ~92% total CPU load (the normal-priority thread fell ~18s behind).
            // Android's audio priority keeps capture draining during those system-load
            // bursts; MediaCodec and file I/O remain non-RT operations on this thread.
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            seedAudioAnchor()
            drainLoop()
        }
    }

    /**
     * Real-device finding: [NativeEngineBridge]'s input stream now outlives individual
     * recordings (started once at preview, kept running across record start/stop for
     * continuous metering — see [com.aucampro.recorder.pipeline.RecordingPipeline
     * .ensureAudioEngineStarted]'s doc). That means by the time a *second-or-later*
     * recording's [AudioEncoder] starts, the ring buffer can be holding a stale backlog —
     * up to its full capacity, frozen there since nothing drained it during preview-only
     * (or nothing at all if preview ran long enough to overflow it, silently dropping
     * every write since as an overrun; see SpscRingBuffer's write()). Confirmed on a real
     * device: a recording started ~87s into preview lost effectively all of its audio
     * (0.064s out of a 30s take) — [cumulativeSampleCount] started at 0 while the anchor
     * below correctly points at the *stream's* true frame 0 (up to 87s earlier), so every
     * PTS this encoder computed landed far in the past relative to recordingStartNanos and
     * was silently dropped by [PtsClockDomain.normalizeAudioPtsUs]'s monotonic guard until
     * enough frames drained to close that gap — which for a short recording never happens.
     *
     * Fixed by (1) flushing the stale backlog so draining starts from "now", and (2)
     * seeding [cumulativeSampleCount] from the correlation's own frame position instead of
     * 0, so it lines up with the same frame-0 the anchor below is computed against.
     *
     * **Hi-res dual-rate note (docs/HIRES_AUDIO_DESIGN.md §4)**: [correlation]'s
     * `framePosition` is in the *engine's* frame units ([captureSampleRateHz]), because
     * that's the domain the native ring buffer/stream counts in — so the rate argument
     * passed to [PtsClockDomain.startAudioAnchorFromFrameCorrelation] must stay
     * [captureSampleRateHz], NOT [sampleRateHz] (sample 0's wall-clock time is rate-
     * independent, but converting a frame *count* to a time offset is not). But
     * [cumulativeSampleCount] itself must stay in 48kHz-equivalent units (everything else
     * that reads it — PTS math, the AAC encoder's own frame accounting — assumes
     * [sampleRateHz]), hence the `/ decimationFactor` below: framePosition frames at
     * [captureSampleRateHz] is `framePosition / decimationFactor` frames at [sampleRateHz].
     */
    private fun seedAudioAnchor() {
        nativeEngine.flushRingBuffer()

        // Captured BEFORE the retry loop, not after: the ring buffer has been filling
        // since nativeEngine.start() (called by our caller before this), so if correlation
        // fails, "now" at the *start* of this method is a much closer approximation of
        // sample 0's true capture time than "now" after burning the retry budget — see the
        // real-device finding this fixes, in this method's class doc / ARCHITECTURE.md.
        val fallbackAnchorNanos = System.nanoTime()
        repeat(ANCHOR_CORRELATION_MAX_ATTEMPTS) {
            val correlation = nativeEngine.getInputTimestamp()
            if (correlation != null) {
                val (framePosition, timeNanos) = correlation
                ptsClockDomain.startAudioAnchorFromFrameCorrelation(framePosition, timeNanos, captureSampleRateHz)
                // Aligns this encoder's own frame counter to the stream's true position
                // (this method's doc) rather than assuming draining starts at frame 0.
                cumulativeSampleCount = framePosition / decimationFactor
                return
            }
            Thread.sleep(ANCHOR_CORRELATION_RETRY_SLEEP_MS)
        }
        // Correlation never became available within the retry budget (e.g. the native
        // engine hasn't produced its first input callback yet) — fall back to wall-clock
        // anchoring so recording doesn't hard-fail, at the cost of reintroducing the
        // input-latency offset (see PtsClockDomain.startAudioAnchor's doc).
        // cumulativeSampleCount is left at 0 here — consistent with this fallback also
        // treating "now" (fallbackAnchorNanos) as sample 0.
        Log.w(TAG, "getInputTimestamp() never returned a correlation after " +
            "$ANCHOR_CORRELATION_MAX_ATTEMPTS attempts; falling back to wall-clock audio anchor " +
            "(A/V sync may be off by the audio input pipeline's latency)")
        ptsClockDomain.startAudioAnchor(nowNanos = fallbackAnchorNanos)
    }

    /**
     * Signals end-of-stream and waits for the drain thread to finish (§4.4's stop sequence).
     *
     * Real-device finding (Sony SO-51C): this used to call `codec.stop()`/`codec.release()`
     * itself right after a *timed* `join()`. `Thread.join(timeout)` returns whether or not
     * the thread actually finished — under load (heavy background GC, muxer lock
     * contention from [com.aucampro.recorder.muxer.MuxerController]'s shared
     * lock — see its class doc) [drainThread]'s own post-EOS drain could still be mid
     * `queueInput()`/`dequeueInputBuffer()` when the timeout elapsed, so the caller's
     * `codec.release()` raced it and threw `IllegalStateException: codec is released
     * already` from inside [queueInput] — reproduced twice on-device (once as a full ANR,
     * since this runs on the caller's dispatcher, which for
     * [com.aucampro.recorder.pipeline.RecordingPipeline]'s current callers is the Main
     * thread). Fixed by moving `codec.stop()`/`release()` into [drainLoop]'s own `finally`
     * so only the thread that was still using the codec ever calls those on it.
     *
     * **実機で発見 (2026-07-18)**: [drainThread] is still alive after the timeout, this used
     * to just log and return, leaving the codec cleanup "a resource-lifetime delay, not a
     * crash" — true for the codec, but [RecordingPipeline]'s stop sequence calls
     * [exportWavIfRequested] right after this returns, which copies [hiResSink]'s `.wav`
     * file out via `MediaStore`. That file's RIFF/`fact`/`data` size fields are only
     * back-patched in [HiResAudioSink.close] → [WavFileWriter.close], which runs in
     * [drainLoop]'s `finally` — i.e. after this thread was still draining. Returning early
     * let the export race that back-patch, confirmed on-device via a captured `.wav` whose
     * header still had all-zero RIFF/data sizes (players see zero-length audio — the
     * reported 「WAVファイルの破損」) despite the PCM data itself being intact. Since both of
     * this method's callers already run it off the main thread (`RecordingPipeline`'s
     * `Dispatchers.IO`-dispatched stop path, or its teardown path which this class's own
     * stop-sequence design no longer needs to race for main-thread safety — see above).
     * a bounded-but-generous wait can't fully close the gap on a slow enough device, so we
     * wait unconditionally: [drainThread]'s own loop is bounded (drains the ring buffer,
     * sends EOS, waits for the ack, then closes) and always terminates on its own.
     */
    fun stop() {
        running.set(false)
        drainThread?.join(DRAIN_THREAD_JOIN_TIMEOUT_MS)
        if (drainThread?.isAlive == true) {
            Log.w(TAG, "AudioEncoderDrain still running after " +
                "${DRAIN_THREAD_JOIN_TIMEOUT_MS}ms; waiting for it to fully finish " +
                "(callers depend on hiResSink's file being closed) rather than returning early")
            drainThread?.join()
        }
    }

    private fun drainLoop() {
        // Sized in *capture-rate* frames — [framesPerBlock] frames raw drain from the ring
        // buffer, then converted natively down to ~[FRAMES_PER_BLOCK] frames' worth of AAC
        // input, keeping this loop's steady-state cadence the same regardless of
        // [captureSampleRateHz] (docs/HIRES_AUDIO_DESIGN.md §6.5).
        val framesPerBlock = FRAMES_PER_BLOCK * decimationFactor
        val rawScratch = FloatArray(framesPerBlock * channelCount)
        val shortScratch = ShortArray(FRAMES_PER_BLOCK * channelCount)
        val byteScratch = java.nio.ByteBuffer.allocateDirect(shortScratch.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val bufferInfo = MediaCodec.BufferInfo()
        val profileStartNanos = System.nanoTime()
        var profileBlockCount = 0L
        var profileRawFrames = 0L
        var drainNanos = 0L
        var wavNanos = 0L
        var converterNanos = 0L
        var packNanos = 0L
        var codecInputNanos = 0L
        var codecOutputNanos = 0L
        var suppressedPtsBlockCount = 0L
        var lastSuppressedPtsLogNanos = 0L

        fun logDrainProfileIfDue() {
            if (profileBlockCount == 0L ||
                profileBlockCount % PROFILE_LOG_INTERVAL_BLOCKS != 0L
            ) {
                return
            }
            val wallMs = (System.nanoTime() - profileStartNanos) / 1_000_000L
            val capturedMs = profileRawFrames * 1000L / captureSampleRateHz
            fun averageUs(totalNanos: Long): Long =
                totalNanos / profileBlockCount / 1_000L
            Log.i(
                TAG,
                "Audio drain profile: blocks=$profileBlockCount rawFrames=$profileRawFrames " +
                    "capturedMs=$capturedMs wallMs=$wallMs " +
                    "avgUs[drain=${averageUs(drainNanos)},wav=${averageUs(wavNanos)}," +
                    "nativeConvert=${averageUs(converterNanos)}," +
                    "pack=${averageUs(packNanos)},codecIn=${averageUs(codecInputNanos)}," +
                    "codecOut=${averageUs(codecOutputNanos)}] " +
                    "ringFill=${nativeEngine.ringBufferFillFrames()} " +
                    "ringDropped=${nativeEngine.ringBufferDroppedFrameCount()}",
            )
        }

        // One drain+encode attempt. Returns false only when the ring buffer was empty (the
        // "nothing left to do right now" case both the steady-state loop and the final
        // drain below need to distinguish from "encoded a block, possibly non-monotonic").
        fun drainOneBlock(): Boolean {
            var stageStartNanos = System.nanoTime()
            val rawFramesRead = nativeEngine.drainEncoderBuffer(rawScratch, framesPerBlock)
            drainNanos += System.nanoTime() - stageStartNanos
            if (rawFramesRead == 0) return false
            profileRawFrames += rawFramesRead

            // Fan-out per docs/HIRES_AUDIO_DESIGN.md §4: the WAV sidecar gets the raw,
            // undecimated block exactly as captured — this is the *only* other consumer of
            // this block, driven from this same drain thread (never a second ring-buffer
            // reader; see NativeEngineBridge's class doc on the SPSC invariant).
            stageStartNanos = System.nanoTime()
            hiResSink?.writeFrames(rawScratch, rawFramesRead)
            wavNanos += System.nanoTime() - stageStartNanos

            // The old Kotlin Decimator + Random.Default TPDF path consumed 15-18ms of
            // this block's 21.3ms real-time budget after the SO-51C warmed up. Native
            // conversion performs the identical 8th-order anti-alias filter, decimation,
            // TPDF dither, and Int16 quantization in one JNI call.
            stageStartNanos = System.nanoTime()
            val framesRead =
                nativeEngine.convertEncoderPcm(rawScratch, rawFramesRead, shortScratch)
            converterNanos += System.nanoTime() - stageStartNanos
            // A very small raw block can produce no output at an integer decimation
            // boundary. Data was consumed, so keep draining instead of sleeping.
            if (framesRead == 0) return true
            val sampleCount = framesRead * channelCount

            val ptsUs = ptsClockDomain.normalizeAudioPtsUs(cumulativeSampleCount, sampleRateHz)
            cumulativeSampleCount += framesRead
            if (ptsUs == null) {
                // Audio captured before the first video frame establishes epoch zero is
                // intentionally suppressed. Once video has started, rate-limit any
                // continued monotonic-guard suppression; per-block logging here used to
                // produce hundreds of logcat writes during camera-session warmup.
                suppressedPtsBlockCount += 1
                val nowNanos = System.nanoTime()
                if (ptsClockDomain.isStarted() &&
                    nowNanos - lastSuppressedPtsLogNanos >=
                    SUPPRESSED_PTS_LOG_INTERVAL_NS
                ) {
                    Log.w(
                        TAG,
                        "Audio PTS blocks suppressed=$suppressedPtsBlockCount " +
                            "(waiting for audio timeline to reach video epoch; " +
                            "cumulativeSampleCount=$cumulativeSampleCount)",
                    )
                    lastSuppressedPtsLogNanos = nowNanos
                }
                return true
            }

            stageStartNanos = System.nanoTime()
            byteScratch.clear()
            for (i in 0 until sampleCount) {
                byteScratch.putShort(shortScratch[i])
            }
            byteScratch.flip()
            packNanos += System.nanoTime() - stageStartNanos

            stageStartNanos = System.nanoTime()
            queueInput(byteScratch, ptsUs, endOfStream = false, bufferInfo)
            codecInputNanos += System.nanoTime() - stageStartNanos
            profileBlockCount += 1
            logDrainProfileIfDue()
            return true
        }

        try {
            while (running.get()) {
                val outputStartNanos = System.nanoTime()
                drainOutputAvailable(bufferInfo)
                codecOutputNanos += System.nanoTime() - outputStartNanos
                if (!drainOneBlock()) {
                    Thread.sleep(BUFFER_EMPTY_SLEEP_MS)
                }
            }

            // stop() flips `running` and returns immediately without waiting for the ring
            // buffer to empty — real-device finding: without this, whatever was still
            // buffered (the last ~0.1-0.5s in practice) got silently truncated instead of
            // reaching the encoder. drainOutputAvailable keeps pace alongside so this
            // doesn't exhaust MediaCodec's input buffer pool if the backlog is large.
            while (drainOneBlock()) {
                val outputStartNanos = System.nanoTime()
                drainOutputAvailable(bufferInfo)
                codecOutputNanos += System.nanoTime() - outputStartNanos
            }

            // Final EOS buffer, per §4.4's stop sequence (EOS -> drain all pending output -> stop/release).
            val finalPtsUs = ptsClockDomain.normalizeAudioPtsUs(cumulativeSampleCount, sampleRateHz)
                ?: (cumulativeSampleCount * 1_000_000L / sampleRateHz)
            queueInput(
                java.nio.ByteBuffer.allocateDirect(0),
                finalPtsUs,
                endOfStream = true,
                bufferInfo,
            )
            drainOutputUntilEos(bufferInfo)
        } catch (e: Exception) {
            callback.onError(e)
        } finally {
            // One line per take is enough to diagnose whether the capture producer ever
            // outran this consumer. Logging the high-water mark from the preview meter
            // loop would be misleading/noisy because no encoder consumer exists before
            // REC and start() deliberately flushes that stale preview backlog.
            Log.i(
                TAG,
                "Recording audio ring summary: overruns=" +
                    nativeEngine.ringBufferOverrunCount() +
                    ", droppedFrames=" + nativeEngine.ringBufferDroppedFrameCount() +
                    ", highWaterFrames=" + nativeEngine.ringBufferHighWaterFrames() +
                    ", finalFillFrames=" + nativeEngine.ringBufferFillFrames() +
                    ", aacInputRetries=" + aacInputRetryCount.get() +
                    ", aacInputMaxWaitMs=" + (aacInputMaxWaitNanos.get() / 1_000_000L) +
                    ", aacInputTimeoutFailures=" + aacInputTimeoutFailureCount.get(),
            )
            // Only this thread ever calls dequeue/queueInputBuffer, so it's the only safe
            // owner of stop()/release() too — see [stop]'s doc for the cross-thread race
            // this replaced. Same reasoning extends to hiResSink: this is the only thread
            // that ever calls writeFrames() on it, so it's the only safe owner of close()
            // too — swallows exceptions so a WAV I/O failure never prevents the codec
            // cleanup right below it from running.
            try {
                hiResSink?.close()
            } catch (e: Exception) {
                Log.e(TAG, "hiResSink close failed", e)
            }
            codec.stop()
            codec.release()
        }
    }

    /**
     * Queues one complete PCM block without silently discarding it.
     *
     * A busy codec is normal under momentary system load: dequeueInputBuffer() returning
     * INFO_TRY_AGAIN_LATER means "retry", not that the caller-owned PCM may be dropped.
     * The previous single 10ms attempt returned without telling [drainOneBlock], which had
     * already advanced [cumulativeSampleCount]. That produced an AAC timestamp hole and
     * an audible skip/fast-forward while the WAV path still contained that particular
     * block. Drain available output between retries so the codec can recycle an input
     * buffer even when its output side was the source of backpressure.
     */
    private fun queueInput(
        data: java.nio.ByteBuffer,
        ptsUs: Long,
        endOfStream: Boolean,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        val size = data.remaining()
        val callStartNanos = System.nanoTime()
        val deadlineNanos = callStartNanos + CODEC_INPUT_WAIT_TIMEOUT_NS
        var retryCount = 0
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                    ?: throw IllegalStateException(
                        "MediaCodec returned input index $inputIndex without a buffer",
                    )
                check(size <= inputBuffer.capacity()) {
                    "PCM block ($size bytes) exceeds codec input capacity " +
                        "(${inputBuffer.capacity()} bytes)"
                }
                inputBuffer.clear()
                inputBuffer.put(data)
                val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                codec.queueInputBuffer(inputIndex, 0, size, ptsUs, flags)
                if (retryCount > 0) {
                    recordQueueInputRetry(retryCount, System.nanoTime() - callStartNanos)
                    Log.w(
                        TAG,
                        "MediaCodec input recovered after $retryCount retries " +
                            "(ptsUs=$ptsUs, bytes=$size, eos=$endOfStream)",
                    )
                }
                return
            }

            retryCount += 1
            drainOutputAvailable(bufferInfo)
            if (System.nanoTime() >= deadlineNanos) {
                recordQueueInputRetry(retryCount, System.nanoTime() - callStartNanos)
                aacInputTimeoutFailureCount.incrementAndGet()
                throw IllegalStateException(
                    "Timed out waiting for MediaCodec input; refusing to silently drop " +
                        "$size PCM bytes at ptsUs=$ptsUs after $retryCount retries",
                )
            }
        }
    }

    /** Drain-thread only (see [queueInput]'s only caller). */
    private fun recordQueueInputRetry(retryCount: Int, waitNanos: Long) {
        aacInputRetryCount.addAndGet(retryCount.toLong())
        var currentMax = aacInputMaxWaitNanos.get()
        while (waitNanos > currentMax && !aacInputMaxWaitNanos.compareAndSet(currentMax, waitNanos)) {
            currentMax = aacInputMaxWaitNanos.get()
        }
    }

    private fun drainOutputAvailable(bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!formatAnnounced) {
                        formatAnnounced = true
                        callback.onOutputFormatChanged(codec.outputFormat)
                    }
                }
                outputIndex >= 0 -> emitOutput(outputIndex, bufferInfo)
                else -> return // INFO_TRY_AGAIN_LATER or INFO_OUTPUT_BUFFERS_CHANGED (deprecated path)
            }
        }
    }

    private fun drainOutputUntilEos(bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!formatAnnounced) {
                    formatAnnounced = true
                    callback.onOutputFormatChanged(codec.outputFormat)
                }
                continue
            }
            if (outputIndex < 0) continue
            emitOutput(outputIndex, bufferInfo)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    private fun emitOutput(outputIndex: Int, bufferInfo: MediaCodec.BufferInfo) {
        val buffer = codec.getOutputBuffer(outputIndex)
        if (buffer != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            callback.onEncodedFrame(buffer, bufferInfo)
        }
        codec.releaseOutputBuffer(outputIndex, false)
    }

    private companion object {
        const val TAG = "AudioEncoder"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        // One dequeue attempt remains short so output can be drained between retries.
        // queueInput() applies the overall bounded wait and never treats a single timeout
        // as permission to discard caller-owned PCM.
        const val CODEC_INPUT_WAIT_TIMEOUT_NS = 2_000_000_000L
        // Roughly every 10.9s at the AAC-LC 1024-frame cadence. The temporary 64-block
        // interval used during profiling was intentionally noisy (~1.3s).
        const val PROFILE_LOG_INTERVAL_BLOCKS = 512L
        const val SUPPRESSED_PTS_LOG_INTERVAL_NS = 1_000_000_000L

        // 5ms rather than the original 2ms — PERF_INVESTIGATION_2026-07-17.md P7: this
        // thread runs for the whole preview/recording lifetime (not just while actively
        // draining), so its steady-state wakeup rate is a real, continuous cost. A block is
        // ~21.3ms of audio (FRAMES_PER_BLOCK @48kHz), so polling at 5ms still drains with
        // more than 4x headroom before the ring buffer could back up.
        const val BUFFER_EMPTY_SLEEP_MS = 5L
        const val DRAIN_THREAD_JOIN_TIMEOUT_MS = 3_000L

        // One native AAC-LC access unit is 1024 PCM frames. Matching that granularity
        // halves JNI, PCM conversion, WAV write, and MediaCodec calls compared with the
        // old 512-frame blocks while staying at only ~21.3ms @48kHz. At 192kHz the raw
        // drain scales to 4096 frames and still represents the same wall-clock duration.
        const val FRAMES_PER_BLOCK = 1024

        // 200 attempts * 10ms = 2000ms budget. A 250ms budget was empirically too short
        // on real hardware (Sony SO-51C): AAudio's getTimestamp() reliably returned
        // ErrorInvalidState until the input stream had processed several bursts' worth
        // of frames, which took closer to 1s in practice — the 250ms budget was silently
        // falling back to wall-clock anchoring on every real recording (see
        // docs/ARCHITECTURE.md's judgment log), reintroducing the input-latency offset
        // this mechanism exists to remove. 2s is a one-time recording-start cost, not a
        // steady-state one, so it's an acceptable trade for reliably getting the accurate
        // anchor.
        const val ANCHOR_CORRELATION_MAX_ATTEMPTS = 200
        const val ANCHOR_CORRELATION_RETRY_SLEEP_MS = 10L
    }
}
