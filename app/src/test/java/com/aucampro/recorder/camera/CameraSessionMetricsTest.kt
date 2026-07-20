package com.aucampro.recorder.camera

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * Only the pure logic ([CameraSessionMetrics.bucketIndexFor]/[CameraSessionMetrics.approxPercentileNanos])
 * and plain-Kotlin counter bookkeeping are host-testable here — everything else in
 * [CameraSessionMetrics] touches `android.os.Trace`/`SystemClock`, which throw under plain
 * JUnit (no Robolectric in this project — matches the existing `android.util.Range`
 * constraint documented on [CaptureRangeClamperTest]).
 */
class CameraSessionMetricsTest {

    @After
    fun tearDown() {
        CameraSessionMetrics.resetForTest()
    }

    private val bounds = longArrayOf(100L, 200L, 300L)

    @Test
    fun bucketIndexFor_valueBelowFirstBound_returnsFirstBucket() {
        assertThat(CameraSessionMetrics.bucketIndexFor(50L, bounds)).isEqualTo(0)
    }

    @Test
    fun bucketIndexFor_valueExactlyAtBound_fallsIntoThatBucket() {
        assertThat(CameraSessionMetrics.bucketIndexFor(100L, bounds)).isEqualTo(0)
        assertThat(CameraSessionMetrics.bucketIndexFor(200L, bounds)).isEqualTo(1)
    }

    @Test
    fun bucketIndexFor_valueJustAboveBound_fallsIntoNextBucket() {
        assertThat(CameraSessionMetrics.bucketIndexFor(101L, bounds)).isEqualTo(1)
    }

    @Test
    fun bucketIndexFor_valueAboveLastBound_returnsLastBucket() {
        assertThat(CameraSessionMetrics.bucketIndexFor(9_999L, bounds)).isEqualTo(bounds.lastIndex)
    }

    @Test
    fun approxPercentileNanos_emptyHistogram_returnsZero() {
        val buckets = longArrayOf(0L, 0L, 0L)
        assertThat(CameraSessionMetrics.approxPercentileNanos(buckets, 0L, bounds, 0.50)).isEqualTo(0.0)
    }

    @Test
    fun approxPercentileNanos_allSamplesInFirstBucket_p50AndP95BothReportFirstBound() {
        val buckets = longArrayOf(10L, 0L, 0L)
        assertThat(CameraSessionMetrics.approxPercentileNanos(buckets, 10L, bounds, 0.50)).isEqualTo(100.0)
        assertThat(CameraSessionMetrics.approxPercentileNanos(buckets, 10L, bounds, 0.95)).isEqualTo(100.0)
    }

    @Test
    fun approxPercentileNanos_spreadAcrossBuckets_picksBucketWhereCumulativeCrossesTarget() {
        // 100 samples: 80 in bucket0 (<=100), 15 in bucket1 (<=200), 5 in bucket2 (<=300).
        // p50 target=50 -> cumulative after bucket0 (80) already >= 50 -> bucket0's bound.
        // p95 target=95 -> cumulative after bucket0 (80) < 95, after bucket1 (95) >= 95 -> bucket1's bound.
        val buckets = longArrayOf(80L, 15L, 5L)
        assertThat(CameraSessionMetrics.approxPercentileNanos(buckets, 100L, bounds, 0.50)).isEqualTo(100.0)
        assertThat(CameraSessionMetrics.approxPercentileNanos(buckets, 100L, bounds, 0.95)).isEqualTo(200.0)
    }

    @Test
    fun approxPercentileNanos_targetInTopUnboundedBucket_reportsDoubledPreviousBound() {
        val topBounds = longArrayOf(100L, 200L, Long.MAX_VALUE)
        val buckets = longArrayOf(0L, 0L, 10L)
        // All 10 samples land in the unbounded top bucket — approximated as 2x the previous
        // finite bound (200) rather than Long.MAX_VALUE, which would be useless in a report.
        assertThat(CameraSessionMetrics.approxPercentileNanos(buckets, 10L, topBounds, 0.50)).isEqualTo(400.0)
    }

    @Test
    fun onSetRepeatingRequest_countsAreIndependentPerReason() {
        CameraSessionMetrics.onSetRepeatingRequest(CameraSessionMetrics.RepeatingRequestReason.START_REPEATING)
        CameraSessionMetrics.onSetRepeatingRequest(CameraSessionMetrics.RepeatingRequestReason.START_REPEATING)
        CameraSessionMetrics.onSetRepeatingRequest(CameraSessionMetrics.RepeatingRequestReason.PARAM_UPDATE)

        assertThat(CameraSessionMetrics.setRepeatingRequestCount(CameraSessionMetrics.RepeatingRequestReason.START_REPEATING))
            .isEqualTo(2L)
        assertThat(CameraSessionMetrics.setRepeatingRequestCount(CameraSessionMetrics.RepeatingRequestReason.PARAM_UPDATE))
            .isEqualTo(1L)
        assertThat(CameraSessionMetrics.setRepeatingRequestCount(CameraSessionMetrics.RepeatingRequestReason.SESSION_RECONFIGURE))
            .isEqualTo(0L)
        assertThat(CameraSessionMetrics.setRepeatingRequestCount(CameraSessionMetrics.RepeatingRequestReason.FOCUS_REQUEST))
            .isEqualTo(0L)
    }
}
