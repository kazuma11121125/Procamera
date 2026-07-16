package com.aucampro.recorder.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Owns the MediaMuxer lifecycle for §4.4: buffers samples in a pending queue until both
 * tracks' formats are known (`INFO_OUTPUT_FORMAT_CHANGED` from both encoders), then
 * writes to a segment file, seamlessly rotating to a new file at video keyframe
 * boundaries per [SegmentRotationPlanner]'s timing decisions — the crash-safety
 * requirement being that an unclosed `MediaMuxer` loses its entire file (no moov box),
 * so bounding each segment's duration bounds the damage.
 *
 * `MediaMuxer.writeSampleData` is not documented as thread-safe for concurrent callers,
 * and video samples arrive from the Video Encoder Callback thread while audio samples
 * arrive from the Audio Encoder drain thread (see docs/ARCHITECTURE.md's thread model) —
 * rather than a shared lock (which made the calling encoder thread block on synchronous
 * disk I/O inside the critical section), every method that touches muxer state instead
 * runs exclusively on [ioExecutor]'s single worker thread; callers only do the minimal
 * thread-safe copy ([toPendingSample]) before handing off. See [ioExecutor]'s own doc for
 * why the queue is bounded and the rejection handler blocks rather than runs inline.
 *
 * **確信度の明示 / known limitation**: rotating both tracks at *exactly* the same
 * instant with zero loss/duplication requires routing in-flight samples from either
 * thread against the exact rotation boundary PTS, which this implements via
 * [rotationBoundaryPtsUs] comparison — but the two encoder threads are not
 * hard-synchronized with each other, so a genuinely adversarial interleaving (e.g. the
 * audio thread stalls for an unusually long time right at a rotation boundary) is not
 * fully ruled out by this implementation alone. This is exactly the kind of
 * cross-thread-timing correctness that can't be verified by compiling or by a host unit
 * test — Phase 5's real-device verification must specifically stress-test segment
 * rotation (e.g. record continuously across several rotation boundaries and verify each
 * resulting segment file has no A/V gap or duplicate frame at its start/end).
 */
class SegmentedMuxerController(
    private val outputPathForSegment: (segmentIndex: Int) -> String,
    segmentDurationUs: Long = DEFAULT_SEGMENT_DURATION_US,
    /**
     * MP4 rotation matrix hint (0/90/180/270), applied to every segment's [MediaMuxer] via
     * [MediaMuxer.setOrientationHint] before `start()`. This app's window is locked to
     * `sensorLandscape` (real-device feedback: the UI itself should stay landscape-only),
     * but the camera's encoded frames are always in the sensor's fixed native orientation
     * regardless of how the user physically holds the phone — so a portrait-held
     * recording needs this container-level tag for players to display it upright, since
     * nothing about the actual pixel data changes. Sampled once at recording start (see
     * `RecordingPipeline.startRecording`'s call site) rather than re-evaluated per
     * segment — matching ordinary camera app behavior, where rotating the phone mid-take
     * doesn't retroactively change already-committed segments' orientation tag.
     */
    private val orientationHintDegrees: Int = 0,
) {
    private data class PendingSample(
        val isVideo: Boolean,
        val buffer: ByteBuffer,
        val bufferInfo: MediaCodec.BufferInfo,
    )

    private val rotationPlanner = SegmentRotationPlanner(segmentDurationUs)

    // Single worker thread is what lets every method below mutate currentMuxer/started/
    // pendingSamples/rotation state without synchronized — only ever one thread touches
    // them. The queue is bounded (unlike Executors.newSingleThreadExecutor()'s unbounded
    // LinkedBlockingQueue) so a stalled writeSampleData (slow storage, segment-rotation
    // MediaMuxer.start() hiccup) can't let queued per-sample allocateDirect() copies grow
    // without limit. The custom handler blocks the *producer* thread (video/audio encoder
    // callback thread) on queue.put() when full, rather than using CallerRunsPolicy — that
    // would run the rejected task on the producer thread while the worker thread might
    // still be mid-task, i.e. two threads touching the unsynchronized state at once. A
    // late sample arriving after stop() has already shut the executor down (real race:
    // AudioEncoder.stop()'s 3s join can time out and return while its drain thread is
    // still emitting callbacks — see RecordingPipeline's stop sequence) is discarded
    // silently instead of throwing RejectedExecutionException.
    private val ioExecutor = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0L,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue(MAX_QUEUED_IO_TASKS),
        java.util.concurrent.RejectedExecutionHandler { runnable, executor ->
            if (!executor.isShutdown) {
                executor.queue.put(runnable)
            }
        },
    )

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var started = false
    private val pendingSamples = mutableListOf<PendingSample>()

    private var currentMuxer: MediaMuxer? = null
    private var currentVideoTrack = -1
    private var currentAudioTrack = -1

    // Set when a rotation decision fires; cleared once the old muxer is confirmed drained
    // (see rotate()/routeToNew()).
    private var rotationBoundaryPtsUs: Long? = null
    private var oldMuxer: MediaMuxer? = null
    private var oldVideoTrack = -1
    private var oldAudioTrack = -1
    private var samplesRoutedToNewSinceRotation = 0

    fun onVideoFormatChanged(format: MediaFormat) {
        ioExecutor.execute {
            videoFormat = format
            tryStart()
        }
    }

    fun onAudioFormatChanged(format: MediaFormat) {
        ioExecutor.execute {
            audioFormat = format
            tryStart()
        }
    }

    private fun tryStart() {
        if (started) return
        val vf = videoFormat ?: return
        val af = audioFormat ?: return

        val muxer = MediaMuxer(outputPathForSegment(0), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(orientationHintDegrees)
        currentVideoTrack = muxer.addTrack(vf)
        currentAudioTrack = muxer.addTrack(af)
        muxer.start()
        currentMuxer = muxer
        started = true

        // §4.4: samples that arrived before both formats were known are held, not
        // dropped, and written out now in their original order.
        for (pending in pendingSamples) {
            writeToCurrent(pending.isVideo, pending.buffer, pending.bufferInfo)
        }
        pendingSamples.clear()
    }

    fun onVideoSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val sample = toPendingSample(true, buffer, bufferInfo)
        ioExecutor.execute {
            if (!started) {
                pendingSamples += sample
                return@execute
            }

            // A rotation may be mid-flight: route via MuxerSampleRouter, which encodes the
            // straggler rule — a sample from before the boundary (the other track's thread
            // hadn't caught up yet when the video thread triggered rotation) MUST go to the
            // old muxer, never the new one, or that segment's audio/video would gain samples
            // that belong to the previous file.
            val boundary = rotationBoundaryPtsUs
            if (boundary != null) {
                routeDuringRotation(true, sample.buffer, sample.bufferInfo, boundary)
                return@execute
            }

            val isKeyframe = (sample.bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val decision = rotationPlanner.onVideoFrame(sample.bufferInfo.presentationTimeUs, isKeyframe)
            if (decision is SegmentRotationPlanner.Decision.Rotate) {
                rotate(sample.bufferInfo.presentationTimeUs, decision.newSegmentIndex)
                routeDuringRotation(true, sample.buffer, sample.bufferInfo, sample.bufferInfo.presentationTimeUs)
            } else {
                writeToCurrent(true, sample.buffer, sample.bufferInfo)
            }
        }
    }

    fun onAudioSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val sample = toPendingSample(false, buffer, bufferInfo)
        ioExecutor.execute {
            if (!started) {
                pendingSamples += sample
                return@execute
            }

            val boundary = rotationBoundaryPtsUs
            if (boundary != null) {
                routeDuringRotation(false, sample.buffer, sample.bufferInfo, boundary)
            } else {
                writeToCurrent(false, sample.buffer, sample.bufferInfo)
            }
        }
    }

    /** Only valid while [rotationBoundaryPtsUs] is non-null (a rotation is mid-flight). */
    private fun routeDuringRotation(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, boundaryPtsUs: Long) {
        val route = MuxerSampleRouter.decide(
            started = true,
            boundaryPtsUs = boundaryPtsUs,
            samplePtsUs = bufferInfo.presentationTimeUs,
            oldMuxerAvailable = oldMuxer != null,
        )
        if (route == MuxerSampleRouter.Route.OLD) {
            writeToOld(isVideo, buffer, bufferInfo)
        } else {
            routeToNew(isVideo, buffer, bufferInfo)
        }
    }

    /** §4.4's stop sequence: caller has already sent EOS to both encoders and drained them. */
    fun stop() {
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            try {
                currentMuxer?.let { muxer ->
                    muxer.stop()
                    muxer.release()
                }
                oldMuxer?.let { muxer ->
                    muxer.stop()
                    muxer.release()
                }
                currentMuxer = null
                oldMuxer = null
                started = false
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        ioExecutor.shutdown()
    }

    private fun rotate(boundaryPtsUs: Long, newSegmentIndex: Int) {
        val next = MediaMuxer(outputPathForSegment(newSegmentIndex), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        next.setOrientationHint(orientationHintDegrees)
        val nextVideoTrack = next.addTrack(requireNotNull(videoFormat))
        val nextAudioTrack = next.addTrack(requireNotNull(audioFormat))
        next.start()

        oldMuxer = currentMuxer
        oldVideoTrack = currentVideoTrack
        oldAudioTrack = currentAudioTrack

        currentMuxer = next
        currentVideoTrack = nextVideoTrack
        currentAudioTrack = nextAudioTrack

        rotationBoundaryPtsUs = boundaryPtsUs
        samplesRoutedToNewSinceRotation = 0
    }

    private fun routeToNew(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        writeToCurrent(isVideo, buffer, bufferInfo)
        samplesRoutedToNewSinceRotation += 1
        // Heuristic drain-complete signal: once several samples have landed cleanly in
        // the new segment, any straggling old-segment samples (from the other track's
        // thread, still mid-flight at the rotation instant) have almost certainly already
        // been written too — see the class doc's confidence caveat on this not being a
        // hard guarantee.
        if (samplesRoutedToNewSinceRotation >= OLD_MUXER_DRAIN_GRACE_SAMPLES) {
            oldMuxer?.let { muxer ->
                muxer.stop()
                muxer.release()
            }
            oldMuxer = null
            rotationBoundaryPtsUs = null
        }
    }

    private fun writeToCurrent(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val muxer = currentMuxer ?: return
        val trackIndex = if (isVideo) currentVideoTrack else currentAudioTrack
        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
    }

    private fun writeToOld(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val muxer = oldMuxer ?: return
        val trackIndex = if (isVideo) oldVideoTrack else oldAudioTrack
        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
    }

    private fun toPendingSample(isVideo: Boolean, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): PendingSample {
        // Both the encoder's output ByteBuffer AND its BufferInfo are only valid /
        // non-aliased until the caller's next callback iteration (encoder wrappers
        // commonly reuse a single BufferInfo instance across calls — see AudioEncoder's
        // drain loop) — samples held in the pending queue (before both track formats are
        // known) must deep-copy both, not reference either.
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

    companion object {
        const val DEFAULT_SEGMENT_DURATION_US = 5 * 60 * 1_000_000L // §4.4 default: 5 minutes
        const val OLD_MUXER_DRAIN_GRACE_SAMPLES = 8

        // ~400ms of combined video+audio samples at worst-case rates (60fps video +
        // ~94 audio blocks/sec @ 48kHz/512-frame blocks) — enough headroom to absorb a
        // brief I/O stall asynchronously before backpressure kicks in.
        const val MAX_QUEUED_IO_TASKS = 64
    }
}
