package com.aucampro.recorder.camera

import android.hardware.camera2.CameraMetadata

/**
 * Pure state machine for §4.1's tap-to-focus hybrid mechanism: base state is
 * `CONTROL_AF_MODE_OFF` with a locked manual focus distance; a tap temporarily switches
 * to `CONTROL_AF_MODE_AUTO` + `AF_TRIGGER_START`, and once the resulting scan converges
 * (or times out), the caller reads the converged `LENS_FOCUS_DISTANCE` and switches back
 * to OFF, locking the UI's manual-focus slider to that value.
 *
 * This class only tracks state transitions from `CONTROL_AF_STATE` values in capture
 * results — it does not itself submit CaptureRequests or read CaptureResults (that's
 * [FocusController], the untested framework-facing glue, since CaptureResult is a
 * framework class this can't be driven by directly in a plain JUnit test).
 */
class TapToFocusStateMachine(private val timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS) {

    sealed interface State {
        /** Base state: MF locked, no scan in progress. */
        data object Idle : State

        /** A tap trigger was just submitted; waiting for AF_STATE to reach a terminal value. */
        data class Scanning(val startedAtNanos: Long) : State

        /** Scan reached a terminal AF_STATE before timing out. */
        data class Converged(val focused: Boolean, val focusDistanceDiopters: Float) : State

        /** Scan did not reach a terminal AF_STATE within [timeoutNanos]. */
        data object TimedOut : State
    }

    var state: State = State.Idle
        private set

    /** Call when a tap-to-focus trigger is submitted. */
    fun onTriggerSubmitted(nowNanos: Long) {
        state = State.Scanning(nowNanos)
    }

    /**
     * Call for every CaptureResult received while [state] is [State.Scanning]. No-op in
     * any other state (extra AF_STATE updates after convergence/timeout/reset are
     * ignored, since the caller has already acted on the terminal state).
     */
    fun onAfStateUpdate(afState: Int, focusDistanceDiopters: Float, nowNanos: Long): State {
        val current = state
        if (current !is State.Scanning) return state

        if (nowNanos - current.startedAtNanos >= timeoutNanos) {
            state = State.TimedOut
            return state
        }

        state = when (afState) {
            CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> State.Converged(true, focusDistanceDiopters)
            CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> State.Converged(false, focusDistanceDiopters)
            else -> current // still scanning (INACTIVE/PASSIVE_*/ACTIVE_SCAN)
        }
        return state
    }

    /** Call after the caller has acted on a terminal state (Converged/TimedOut), or to cancel a scan. */
    fun reset() {
        state = State.Idle
    }

    companion object {
        // §4.1 doesn't specify a numeric timeout; 3s comfortably exceeds typical contrast/
        // phase-detect AF convergence time while still failing fast if the scene has no
        // texture to focus on.
        const val DEFAULT_TIMEOUT_NANOS = 3_000_000_000L
    }
}
