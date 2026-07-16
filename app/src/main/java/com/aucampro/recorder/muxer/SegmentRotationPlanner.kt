package com.aucampro.recorder.muxer

/**
 * Pure decision logic for §4.4's seamless segment rotation: decides *when* to rotate to a
 * new segment, based on elapsed video PTS and keyframe boundaries — never anything else
 * (audio has no keyframe concept, so only video frames can trigger a rotation decision;
 * audio samples simply follow whichever segment is current when they're processed).
 *
 * Deliberately has zero dependency on `MediaMuxer`/`MediaCodec` so it can be driven by a
 * synthetic sequence of (ptsUs, isKeyframe) events in a JUnit test — see
 * SegmentRotationPlannerTest. The actual MediaMuxer lifecycle (creating the next
 * segment's muxer ahead of the rotation point, addTrack/start, writeSampleData,
 * stop/release the old one) is [SegmentedMuxerController]'s framework-facing glue, which
 * calls this class to decide *when*.
 */
class SegmentRotationPlanner(private val segmentDurationUs: Long) {

    sealed interface Decision {
        /** No rotation this frame; keep writing to [segmentIndex]. */
        data class Continue(val segmentIndex: Int) : Decision

        /**
         * Rotate now: this frame is the first frame of [newSegmentIndex] (and must be
         * routed to the new segment's muxer, not the old one).
         */
        data class Rotate(val newSegmentIndex: Int) : Decision
    }

    private var segmentIndex = 0
    private var segmentStartPtsUs: Long? = null

    val currentSegmentIndex: Int get() = segmentIndex

    /**
     * Call for every video frame, in PTS order. Only ever rotates on a keyframe at or
     * after [segmentDurationUs] has elapsed since the current segment started — never
     * mid-GOP, since a segment that doesn't start on a keyframe wouldn't be independently
     * decodable/seekable (defeating the point of segmenting).
     */
    fun onVideoFrame(ptsUs: Long, isKeyframe: Boolean): Decision {
        val startPtsUs = segmentStartPtsUs
        if (startPtsUs == null) {
            // First frame of the whole recording establishes segment 0's start; segment
            // rotation timing is always measured relative to this, not wall-clock time.
            segmentStartPtsUs = ptsUs
            return Decision.Continue(segmentIndex)
        }

        val elapsedUs = ptsUs - startPtsUs
        if (elapsedUs >= segmentDurationUs && isKeyframe) {
            segmentIndex += 1
            segmentStartPtsUs = ptsUs
            return Decision.Rotate(segmentIndex)
        }
        return Decision.Continue(segmentIndex)
    }
}
