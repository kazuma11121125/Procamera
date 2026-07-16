package com.procamera.recorder.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TapToMeteringRegionTest {

    @Test
    fun centerTap_noRotation_producesRegionCenteredOnActiveArrayCenter() {
        val region = TapToMeteringRegion.map(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            sensorOrientationDegrees = 0,
        )
        val regionCenterX = region.x + region.width / 2
        val regionCenterY = region.y + region.height / 2
        assertThat(regionCenterX).isWithin(2).of(2000)
        assertThat(regionCenterY).isWithin(2).of(1500)
    }

    @Test
    fun cornerTap_regionStaysWithinActiveArrayBounds() {
        val region = TapToMeteringRegion.map(
            normalizedX = 0f,
            normalizedY = 0f,
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            sensorOrientationDegrees = 0,
        )
        assertThat(region.x).isAtLeast(0)
        assertThat(region.y).isAtLeast(0)
        assertThat(region.x + region.width).isAtMost(4000)
        assertThat(region.y + region.height).isAtMost(3000)

        val opposite = TapToMeteringRegion.map(
            normalizedX = 1f,
            normalizedY = 1f,
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            sensorOrientationDegrees = 0,
        )
        assertThat(opposite.x + opposite.width).isAtMost(4000)
        assertThat(opposite.y + opposite.height).isAtMost(3000)
    }

    @Test
    fun rotation90_mapsTopLeftTapToTopRightOfSensor() {
        // A tap at the top-left of the preview, with a 90-degree sensor orientation,
        // should map near a corner consistent with rotateNormalizedPoint's (y, 1-x) rule:
        // (0,0) -> (0, 1) i.e. bottom-left in the rotated (sensor) space.
        val region = TapToMeteringRegion.map(
            normalizedX = 0f,
            normalizedY = 0f,
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            sensorOrientationDegrees = 90,
        )
        val regionCenterX = region.x + region.width / 2
        val regionCenterY = region.y + region.height / 2
        assertThat(regionCenterX).isLessThan(500) // near x=0
        assertThat(regionCenterY).isGreaterThan(2500) // near y=height (bottom)
    }

    @Test
    fun rotation180_invertsBothAxes() {
        val region = TapToMeteringRegion.map(
            normalizedX = 0.1f,
            normalizedY = 0.1f,
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            sensorOrientationDegrees = 180,
        )
        val regionCenterX = region.x + region.width / 2
        val regionCenterY = region.y + region.height / 2
        // (0.1, 0.1) -> (0.9, 0.9) under 180-degree rotation.
        assertThat(regionCenterX).isGreaterThan(3000)
        assertThat(regionCenterY).isGreaterThan(2200)
    }

    @Test
    fun regionSizeFraction_scalesWithShorterActiveArrayDimension() {
        val region = TapToMeteringRegion.map(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            sensorOrientationDegrees = 0,
            regionSizeFraction = 0.2f,
        )
        // shorter dimension is 3000 (height); region span should be ~20% of that = 600.
        assertThat(region.width).isWithin(1).of(600)
        assertThat(region.height).isWithin(1).of(600)
    }
}
