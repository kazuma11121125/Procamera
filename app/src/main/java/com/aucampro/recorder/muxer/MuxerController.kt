package com.aucampro.recorder.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.aucampro.recorder.camera.CameraSessionMetrics
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one [MediaMuxer] for the complete recording.
 *
 * Samples are buffered until both encoder formats are known. All muxer operations run on
 * one bounded I/O worker because [MediaMuxer.writeSampleData] is not documented as safe
 * for concurrent audio/video callers.
 */
class MuxerController(
    private val outputPath: String,
    /**
     * MP4 rotation matrix hint (0/90/180/270), sampled once at recording start and
     * applied before the muxer starts.
     */
    private val orientationHintDegrees: Int = 0,
    /**
     * Reported at most once per instance (first failure only — see [reportError]), mirroring
     * [com.aucampro.recorder.encoder.VideoEncoder.Callback.onError]/
     * [com.aucampro.recorder.encoder.AudioEncoder.Callback.onError]'s contract so
     * [com.aucampro.recorder.pipeline.RecordingPipeline] can route all three through the
     * same `onEncoderError` cleanup path. Previously [tryStart]/[write] had no error
     * handling at all — a [MediaMuxer] exception there (e.g. a monotonicity violation
     * slipping past [PtsClockDomain], or a storage error) was silently swallowed by the I/O
     * executor thread's default uncaught-exception handler, with no diagnostic trail and no
     * pipeline cleanup.
     */
    private val onError: (Exception) -> Unit = {},
    /** docs/CAMERA_SESSION_LATENCY_2026-07-21.md Phase 1 — see [VideoEncoder]'s identically-
     * reasoned constructor parameter doc: captured once here rather than read dynamically
     * from `CameraSessionMetrics.activeRecordingAttemptId()` on the I/O executor thread at
     * write() time, so a late write from a still-draining previous attempt can never close
     * the *next* attempt's still-open `AuCam:recordingToFirstMuxerVideoSample` span. */
    private val recordingAttemptId: Int = CameraSessionMetrics.activeRecordingAttemptId(),
) {
    private data class PendingSample(
        val isVideo: Boolean,
        val buffer: ByteBuffer,
        val bufferInfo: MediaCodec.BufferInfo,
    )

    private val stopStarted = AtomicBoolean(false)
    private val stopLatch = CountDownLatch(1)
    private val submissionLock = Any()
    private val muxerErrorReported = AtomicBoolean(false)

    // docs/AUDIO_INSTABILITY_INVESTIGATION_2026-07-18.md's own risk table (§10:
    // "segment境界やstorage負荷で悪化: Muxer backpressure → encoder遅延 → RingBuffer
    // overrun") was ruled out against the *old* SegmentedMuxerController via a
    // videoBackpressureEvents=0 test — that test predates this class, so the same scenario
    // against *this* queue.put()-blocking path is untested. These counters make a real
    // block (storage stall exhausting the 64-slot/~400ms queue below) visible instead of a
    // silent stall on the caller thread (which can be the high-priority AudioEncoderDrain
    // thread — see [enqueueSample]).
    private val ioQueueBlockCount = AtomicLong(0)
    private val ioQueueBlockMaxNanos = AtomicLong(0)

    /** Cumulative times a submitter had to block waiting for I/O queue space. Any thread. */
    fun ioQueueBlockCount(): Long = ioQueueBlockCount.get()

    /** Longest single block on the I/O queue so far, in nanoseconds. Any thread. */
    fun ioQueueBlockMaxNanos(): Long = ioQueueBlockMaxNanos.get()

    // A bounded queue prevents synchronous storage stalls from causing unbounded copied
    // sample allocations. Rejected late samples after stop are intentionally discarded.
    private val ioExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(MAX_QUEUED_IO_TASKS),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        rejectedExecutionHandler =
            java.util.concurrent.RejectedExecutionHandler { runnable, executor ->
                if (!executor.isShutdown && !stopStarted.get()) {
                    val blockStartNanos = System.nanoTime()
                    executor.queue.put(runnable)
                    val blockedNanos = System.nanoTime() - blockStartNanos
                    ioQueueBlockCount.incrementAndGet()
                    var currentMax = ioQueueBlockMaxNanos.get()
                    while (blockedNanos > currentMax &&
                        !ioQueueBlockMaxNanos.compareAndSet(currentMax, blockedNanos)
                    ) {
                        currentMax = ioQueueBlockMaxNanos.get()
                    }
                }
            }
    }

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var started = false
    private val pendingSamples = mutableListOf<PendingSample>()

    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1

    fun onVideoFormatChanged(format: MediaFormat) {
        synchronized(submissionLock) {
            if (stopStarted.get()) return
            ioExecutor.execute {
                videoFormat = format
                tryStart()
            }
        }
    }

    fun onAudioFormatChanged(format: MediaFormat) {
        synchronized(submissionLock) {
            if (stopStarted.get()) return
            ioExecutor.execute {
                audioFormat = format
                tryStart()
            }
        }
    }

    private fun tryStart() {
        if (started) return
        val vf = videoFormat ?: return
        val af = audioFormat ?: return

        try {
            val newMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            newMuxer.setOrientationHint(orientationHintDegrees)
            videoTrack = newMuxer.addTrack(vf)
            audioTrack = newMuxer.addTrack(af)
            newMuxer.start()
            muxer = newMuxer
            started = true
        } catch (e: Exception) {
            reportError(e)
            return
        }

        for (pending in pendingSamples) {
            write(pending.isVideo, pending.buffer, pending.bufferInfo)
        }
        pendingSamples.clear()
    }

    fun onVideoSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        enqueueSample(isVideo = true, buffer, bufferInfo)
    }

    fun onAudioSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        enqueueSample(isVideo = false, buffer, bufferInfo)
    }

    private fun enqueueSample(
        isVideo: Boolean,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        synchronized(submissionLock) {
            if (stopStarted.get()) return
            val sample = toPendingSample(isVideo, buffer, bufferInfo)
            ioExecutor.execute {
                if (!started) {
                    pendingSamples += sample
                } else {
                    write(sample.isVideo, sample.buffer, sample.bufferInfo)
                }
            }
        }
    }

    /** Caller sends and drains EOS from both encoders before normal shutdown. */
    fun stop() {
        if (stopStarted.compareAndSet(false, true)) {
            synchronized(submissionLock) {
                ioExecutor.execute {
                    try {
                        val current = muxer
                        if (current != null) {
                            try {
                                if (started) current.stop()
                            } finally {
                                current.release()
                            }
                        }
                        muxer = null
                        started = false
                        pendingSamples.clear()
                    } finally {
                        stopLatch.countDown()
                    }
                }
            }
        }
        stopLatch.await()
        ioExecutor.shutdown()
    }

    private fun write(
        isVideo: Boolean,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        val current = muxer ?: return
        val trackIndex = if (isVideo) videoTrack else audioTrack
        try {
            current.writeSampleData(trackIndex, buffer, bufferInfo)
            // docs/CAMERA_SESSION_LATENCY_2026-07-21.md Phase 1 — one-shot per recording
            // attempt (CameraSessionMetrics.endFirstMuxerVideoSample no-ops after the first
            // call), so unconditionally calling this on every video write is cheap and
            // correct without an extra guard here.
            if (isVideo) {
                CameraSessionMetrics.endFirstMuxerVideoSample(recordingAttemptId)
            }
        } catch (e: Exception) {
            reportError(e)
        }
    }

    /** First failure only (matches VideoEncoder/AudioEncoder's single-notification
     * `onError` contract) — a storage/codec failure on one sample usually means every
     * subsequent write will fail too, and the caller's cleanup only needs to run once. */
    private fun reportError(exception: Exception) {
        Log.e(TAG, "MuxerController I/O failure", exception)
        if (muxerErrorReported.compareAndSet(false, true)) {
            onError(exception)
        }
    }

    private fun toPendingSample(
        isVideo: Boolean,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ): PendingSample {
        // Encoder output buffers and BufferInfo objects are reused after the callback, so
        // the asynchronous I/O queue must own deep copies of both.
        val bufferCopy = ByteBuffer.allocateDirect(bufferInfo.size)
        val original = buffer.duplicate()
        original.position(bufferInfo.offset)
        original.limit(bufferInfo.offset + bufferInfo.size)
        bufferCopy.put(original)
        bufferCopy.flip()

        val infoCopy = MediaCodec.BufferInfo().apply {
            set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
        }
        return PendingSample(isVideo, bufferCopy, infoCopy)
    }

    private companion object {
        const val TAG = "MuxerController"

        // Roughly 400ms of combined video and audio output at the supported rates.
        const val MAX_QUEUED_IO_TASKS = 64
    }
}
