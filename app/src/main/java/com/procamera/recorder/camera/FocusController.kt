package com.procamera.recorder.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.CameraCharacteristics

/**
 * Orchestrates §4.1's tap-to-focus hybrid mechanism end-to-end: on [onTap], builds a
 * metering region ([TapToMeteringRegion]) and submits an AF trigger via
 * [requestSubmitter]; on each subsequent capture result, advances
 * [TapToFocusStateMachine] and, once it converges (or times out), switches back to
 * locked manual focus at the converged distance.
 *
 * This is the untested framework-facing glue the pure logic in
 * [TapToFocusStateMachine]/[TapToMeteringRegion] plugs into — it needs a live
 * CameraCaptureSession to actually exercise, which doesn't exist until the Foreground
 * Service (Phase 4) owns the camera lifecycle. [RequestSubmitter] is the seam: Phase 4's
 * session-owning class implements it.
 */
class FocusController(
    private val characteristics: CameraCharacteristics,
    private val captureRequestFactory: ManualCaptureRequestFactory,
    private val requestSubmitter: RequestSubmitter,
    private val onFocusLocked: (focusDistanceDiopters: Float) -> Unit,
) {
    /** Seam to the real CameraCaptureSession, implemented by the Phase 4 session owner. */
    fun interface RequestSubmitter {
        fun submitSingleRequest(configure: (CaptureRequest.Builder) -> Unit)
    }

    private val stateMachine = TapToFocusStateMachine()

    private val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    private val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    /**
     * Call on a preview tap. [normalizedX]/[normalizedY] are in [0,1] preview-view
     * coordinates (see [TapToMeteringRegion] for the letterboxing-correction contract).
     */
    fun onTap(normalizedX: Float, normalizedY: Float, nowNanos: Long) {
        val activeArray = activeArraySize ?: return
        val region = TapToMeteringRegion.map(
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            activeArrayWidth = activeArray.width(),
            activeArrayHeight = activeArray.height(),
            sensorOrientationDegrees = sensorOrientation,
        )
        val meteringRectangle = MeteringRectangle(
            region.x,
            region.y,
            region.width,
            region.height,
            MeteringRectangle.METERING_WEIGHT_MAX,
        )

        stateMachine.onTriggerSubmitted(nowNanos)
        requestSubmitter.submitSingleRequest { builder ->
            captureRequestFactory.applyTapToFocusTrigger(builder, meteringRectangle)
        }
    }

    /**
     * Call for every CaptureResult received while a tap-to-focus scan may be in
     * progress. No-op if [TapToFocusStateMachine] isn't in the Scanning state (cheap to
     * call unconditionally from the session's result callback).
     */
    fun onCaptureResult(result: CaptureResult, nowNanos: Long) {
        val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
        val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return

        when (val newState = stateMachine.onAfStateUpdate(afState, focusDistance, nowNanos)) {
            is TapToFocusStateMachine.State.Converged -> lockFocusAndNotify(newState.focusDistanceDiopters)
            TapToFocusStateMachine.State.TimedOut -> lockFocusAndNotify(focusDistance)
            else -> Unit // still scanning, or not currently in a scan
        }
    }

    private fun lockFocusAndNotify(focusDistanceDiopters: Float) {
        stateMachine.reset()
        requestSubmitter.submitSingleRequest { builder ->
            captureRequestFactory.applyFocus(builder, focusDistanceDiopters, afAuto = false)
        }
        onFocusLocked(focusDistanceDiopters)
    }
}
