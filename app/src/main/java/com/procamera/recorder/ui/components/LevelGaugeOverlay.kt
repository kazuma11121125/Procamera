package com.procamera.recorder.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procamera.recorder.ui.theme.MeterGreen
import com.procamera.recorder.ui.theme.OnSurfacePrimary
import kotlin.math.abs

private const val LEVEL_THRESHOLD_DEGREES = 1.5f

/**
 * Roll-angle spirit level (Sony Photo Pro/Video Pro's 水準器/レベルメーター — see
 * Sony__________.pdf: "ジャイロセンサーを利用して水平を検知し緑色に点灯するレベルメーター
 * (水準器)"). A horizon line rotates opposite the phone's roll so it visually stays aligned
 * with the true horizon against a fixed center tick, turning green within
 * [LEVEL_THRESHOLD_DEGREES] of level.
 *
 * Uses `TYPE_ROTATION_VECTOR` (sensor-fused, drift-corrected) rather than raw accelerometer
 * — far less jittery for a live overlay. [SensorManager.remapCoordinateSystem] re-bases the
 * sensor's device-relative axes onto the *display's* current rotation, so this reads
 * correctly in both landscape rotations the app's `sensorLandscape` manifest lock allows
 * (a plain accelerometer roll formula would read backwards in one of the two).
 * `WindowManager.defaultDisplay` (not `Context.display`) is used for the rotation query
 * since `Context.display` needs API 30+ and this app's minSdk is 29.
 */
@Composable
fun LevelGaugeOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var rollDegrees by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val remappedMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val (axisX, axisY) = when (display.rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
                SensorManager.getOrientation(remappedMatrix, orientation)
                // orientation[2] = roll (radians). Positive here means the device's right
                // edge (in the current display orientation) is tilted down.
                rollDegrees = Math.toDegrees(orientation[2].toDouble()).toFloat()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    val isLevel = abs(rollDegrees) < LEVEL_THRESHOLD_DEGREES
    val lineColor = if (isLevel) MeterGreen else Color.White

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.size(width = 120.dp, height = 24.dp)) {
            // Fixed center reference tick — drawn unrotated; the horizon line below must
            // visually align with this to read "level".
            drawLine(
                color = Color.White.copy(alpha = 0.6f),
                start = Offset(size.width / 2f, size.height / 2f - 6.dp.toPx()),
                end = Offset(size.width / 2f, size.height / 2f + 6.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
            )
            rotate(degrees = -rollDegrees, pivot = center) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
        Text(
            text = "%+.1f°".format(rollDegrees),
            color = if (isLevel) MeterGreen else OnSurfacePrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
