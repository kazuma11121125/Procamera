package com.procamera.recorder.camera

/** Plain (x, y, width, height) in sensor active-array pixel coordinates. */
data class MeteringRegion(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Pure coordinate-mapping math for §4.1's tap-to-focus: converts a tap point in
 * normalized preview-view coordinates into a metering region in sensor active-array
 * coordinates, accounting for sensor orientation. Kept separate from
 * `android.hardware.camera2.params.MeteringRectangle` (a framework class) so this is
 * unit-testable without Robolectric — the thin conversion to MeteringRectangle happens in
 * [FocusController].
 */
object TapToMeteringRegion {

    /**
     * @param normalizedX,[normalizedY] tap position in [0,1] preview-view coordinates
     *   (top-left origin), already corrected by the caller for any aspect-ratio
     *   letterboxing between the preview view and the sensor's active array.
     * @param sensorOrientationDegrees `CameraCharacteristics.SENSOR_ORIENTATION` (0, 90,
     *   180, or 270) — the clockwise rotation of the sensor's native readout relative to
     *   the device's natural (preview) orientation, which Camera2 requires callers to
     *   compensate for manually when mapping UI coordinates to sensor coordinates.
     * @param regionSizeFraction size of the metering region as a fraction of the active
     *   array's shorter dimension (a common convention: a small square region around the
     *   tap point, not a single pixel — real AF algorithms need area to evaluate contrast/
     *   phase-detect signal over).
     */
    fun map(
        normalizedX: Float,
        normalizedY: Float,
        activeArrayWidth: Int,
        activeArrayHeight: Int,
        sensorOrientationDegrees: Int,
        regionSizeFraction: Float = 0.1f,
    ): MeteringRegion {
        val (rotatedX, rotatedY) = rotateNormalizedPoint(normalizedX, normalizedY, sensorOrientationDegrees)

        val centerX = (rotatedX * activeArrayWidth).toInt()
        val centerY = (rotatedY * activeArrayHeight).toInt()

        val regionSpan = (minOf(activeArrayWidth, activeArrayHeight) * regionSizeFraction).toInt().coerceAtLeast(1)
        val regionWidth = regionSpan.coerceAtMost(activeArrayWidth)
        val regionHeight = regionSpan.coerceAtMost(activeArrayHeight)

        val x = (centerX - regionWidth / 2).coerceIn(0, activeArrayWidth - regionWidth)
        val y = (centerY - regionHeight / 2).coerceIn(0, activeArrayHeight - regionHeight)

        return MeteringRegion(x, y, regionWidth, regionHeight)
    }

    private fun rotateNormalizedPoint(x: Float, y: Float, degrees: Int): Pair<Float, Float> {
        return when (((degrees % 360) + 360) % 360) {
            90 -> Pair(y, 1f - x)
            180 -> Pair(1f - x, 1f - y)
            270 -> Pair(1f - y, x)
            else -> Pair(x, y)
        }
    }
}
