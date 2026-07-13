package com.procamera.recorder.muxer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SegmentRotationPlannerTest {

    private val fiveMinutesUs = 5 * 60 * 1_000_000L

    @Test
    fun firstFrame_establishesSegmentStartWithoutRotating() {
        val planner = SegmentRotationPlanner(fiveMinutesUs)
        val decision = planner.onVideoFrame(ptsUs = 0L, isKeyframe = true)
        assertThat(decision).isEqualTo(SegmentRotationPlanner.Decision.Continue(0))
        assertThat(planner.currentSegmentIndex).isEqualTo(0)
    }

    @Test
    fun noRotationBeforeDurationElapsed() {
        val planner = SegmentRotationPlanner(fiveMinutesUs)
        planner.onVideoFrame(ptsUs = 0L, isKeyframe = true)
        val decision = planner.onVideoFrame(ptsUs = fiveMinutesUs - 1, isKeyframe = true)
        assertThat(decision).isEqualTo(SegmentRotationPlanner.Decision.Continue(0))
    }

    @Test
    fun noRotationAtDurationElapsedIfNotKeyframe_waitsForNextKeyframe() {
        val planner = SegmentRotationPlanner(fiveMinutesUs)
        planner.onVideoFrame(ptsUs = 0L, isKeyframe = true)

        // Duration has elapsed but this frame isn't a keyframe -> must not rotate mid-GOP.
        val notKeyframeDecision = planner.onVideoFrame(ptsUs = fiveMinutesUs + 1000, isKeyframe = false)
        assertThat(notKeyframeDecision).isEqualTo(SegmentRotationPlanner.Decision.Continue(0))

        // The next keyframe, still past the duration threshold, triggers the rotation.
        val keyframeDecision = planner.onVideoFrame(ptsUs = fiveMinutesUs + 33_000, isKeyframe = true)
        assertThat(keyframeDecision).isEqualTo(SegmentRotationPlanner.Decision.Rotate(1))
    }

    @Test
    fun rotatesExactlyAtKeyframeOnOrAfterDuration() {
        val planner = SegmentRotationPlanner(fiveMinutesUs)
        planner.onVideoFrame(ptsUs = 0L, isKeyframe = true)
        val decision = planner.onVideoFrame(ptsUs = fiveMinutesUs, isKeyframe = true)
        assertThat(decision).isEqualTo(SegmentRotationPlanner.Decision.Rotate(1))
        assertThat(planner.currentSegmentIndex).isEqualTo(1)
    }

    @Test
    fun multipleRotationsIncrementSegmentIndexSequentially() {
        val planner = SegmentRotationPlanner(fiveMinutesUs)
        planner.onVideoFrame(ptsUs = 0L, isKeyframe = true)
        val first = planner.onVideoFrame(ptsUs = fiveMinutesUs, isKeyframe = true)
        val second = planner.onVideoFrame(ptsUs = fiveMinutesUs * 2, isKeyframe = true)
        val third = planner.onVideoFrame(ptsUs = fiveMinutesUs * 3, isKeyframe = true)

        assertThat(first).isEqualTo(SegmentRotationPlanner.Decision.Rotate(1))
        assertThat(second).isEqualTo(SegmentRotationPlanner.Decision.Rotate(2))
        assertThat(third).isEqualTo(SegmentRotationPlanner.Decision.Rotate(3))
    }

    @Test
    fun segmentTimingResetsRelativeToRotationPoint_notOriginalStart() {
        val planner = SegmentRotationPlanner(fiveMinutesUs)
        planner.onVideoFrame(ptsUs = 0L, isKeyframe = true)
        planner.onVideoFrame(ptsUs = fiveMinutesUs, isKeyframe = true) // rotates to segment 1

        // Only 1 minute past the rotation point: must NOT rotate again yet, even though
        // it's well past 5 minutes from the *original* recording start.
        val tooEarly = planner.onVideoFrame(ptsUs = fiveMinutesUs + 60_000_000L, isKeyframe = true)
        assertThat(tooEarly).isEqualTo(SegmentRotationPlanner.Decision.Continue(1))
    }

    @Test
    fun simulatedThirtyFpsStream_rotatesCloseToTargetDuration() {
        val planner = SegmentRotationPlanner(fiveMinutesUs)
        val frameDurationUs = 1_000_000L / 30
        var ptsUs = 0L
        var rotated = false
        var rotationPtsUs = 0L
        // A keyframe every 1 second (30 frames), matching the spec's 1s I-frame interval (§4.4).
        for (frameIndex in 0 until 30 * 60 * 10) { // 10 simulated minutes
            val isKeyframe = frameIndex % 30 == 0
            val decision = planner.onVideoFrame(ptsUs, isKeyframe)
            if (!rotated && decision is SegmentRotationPlanner.Decision.Rotate) {
                rotated = true
                rotationPtsUs = ptsUs
            }
            ptsUs += frameDurationUs
        }
        assertThat(rotated).isTrue()
        // Rotation must happen at/after 5 minutes, and within one keyframe-interval (1s)
        // of it (never early, never delayed by more than one GOP).
        assertThat(rotationPtsUs).isAtLeast(fiveMinutesUs)
        assertThat(rotationPtsUs).isLessThan(fiveMinutesUs + 1_000_000L)
    }
}
