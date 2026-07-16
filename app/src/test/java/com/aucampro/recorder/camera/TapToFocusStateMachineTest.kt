package com.aucampro.recorder.camera

import android.hardware.camera2.CameraMetadata
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TapToFocusStateMachineTest {

    @Test
    fun initialState_isIdle() {
        val machine = TapToFocusStateMachine()
        assertThat(machine.state).isEqualTo(TapToFocusStateMachine.State.Idle)
    }

    @Test
    fun triggerSubmitted_entersScanningState() {
        val machine = TapToFocusStateMachine()
        machine.onTriggerSubmitted(nowNanos = 1000L)
        assertThat(machine.state).isEqualTo(TapToFocusStateMachine.State.Scanning(1000L))
    }

    @Test
    fun activeScanUpdate_staysInScanningState() {
        val machine = TapToFocusStateMachine()
        machine.onTriggerSubmitted(nowNanos = 0L)
        val result = machine.onAfStateUpdate(
            afState = CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN,
            focusDistanceDiopters = 2.0f,
            nowNanos = 10_000_000L,
        )
        assertThat(result).isInstanceOf(TapToFocusStateMachine.State.Scanning::class.java)
    }

    @Test
    fun focusedLocked_convergesWithFocusedTrue() {
        val machine = TapToFocusStateMachine()
        machine.onTriggerSubmitted(nowNanos = 0L)
        val result = machine.onAfStateUpdate(
            afState = CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
            focusDistanceDiopters = 3.5f,
            nowNanos = 200_000_000L,
        )
        assertThat(result).isEqualTo(TapToFocusStateMachine.State.Converged(focused = true, focusDistanceDiopters = 3.5f))
    }

    @Test
    fun notFocusedLocked_convergesWithFocusedFalse() {
        val machine = TapToFocusStateMachine()
        machine.onTriggerSubmitted(nowNanos = 0L)
        val result = machine.onAfStateUpdate(
            afState = CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            focusDistanceDiopters = 0.0f,
            nowNanos = 200_000_000L,
        )
        assertThat(result).isEqualTo(TapToFocusStateMachine.State.Converged(focused = false, focusDistanceDiopters = 0.0f))
    }

    @Test
    fun timesOutIfNoTerminalStateWithinTimeout() {
        val machine = TapToFocusStateMachine(timeoutNanos = 1_000_000_000L)
        machine.onTriggerSubmitted(nowNanos = 0L)
        val result = machine.onAfStateUpdate(
            afState = CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN,
            focusDistanceDiopters = 1.0f,
            nowNanos = 1_500_000_000L, // past the 1s timeout
        )
        assertThat(result).isEqualTo(TapToFocusStateMachine.State.TimedOut)
    }

    @Test
    fun updatesIgnoredWhenNotScanning() {
        val machine = TapToFocusStateMachine()
        // Idle: no trigger submitted yet.
        val result = machine.onAfStateUpdate(
            afState = CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
            focusDistanceDiopters = 5.0f,
            nowNanos = 0L,
        )
        assertThat(result).isEqualTo(TapToFocusStateMachine.State.Idle)
    }

    @Test
    fun resetReturnsToIdle() {
        val machine = TapToFocusStateMachine()
        machine.onTriggerSubmitted(nowNanos = 0L)
        machine.onAfStateUpdate(CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED, 2.0f, 100L)
        machine.reset()
        assertThat(machine.state).isEqualTo(TapToFocusStateMachine.State.Idle)
    }

    @Test
    fun extraUpdatesAfterConvergenceAreIgnored() {
        val machine = TapToFocusStateMachine()
        machine.onTriggerSubmitted(nowNanos = 0L)
        machine.onAfStateUpdate(CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED, 2.0f, 100L)
        val converged = machine.state
        // A stray extra result arrives after convergence; must not disturb the state.
        machine.onAfStateUpdate(CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN, 9.0f, 200L)
        assertThat(machine.state).isEqualTo(converged)
    }
}
