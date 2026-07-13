package com.procamera.recorder.ui.components

import android.Manifest
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.procamera.recorder.ui.viewmodel.CameraControlViewModel

/**
 * Compose wrapper around a [SurfaceView] that serves as the camera preview surface.
 *
 * Camera2 streams frames directly to the [SurfaceHolder.getSurface] of this view, so the
 * preview is zero-copy from the HAL — exactly [§4.1]'s "independent path" requirement for
 * the preview stream (the recording encoder stream uses a separate MediaCodec InputSurface
 * added to the same Camera2 session but not rendered here).
 *
 * Surface lifecycle:
 * - [SurfaceHolder.Callback.surfaceCreated]: calls [CameraControlViewModel.attachPreviewSurface]
 *   so the ViewModel can open the camera session with this surface.
 * - [SurfaceHolder.Callback.surfaceDestroyed]: calls [CameraControlViewModel.detachPreviewSurface]
 *   so the ViewModel can clean up before the Surface is invalidated.
 *
 * The [CAMERA] permission must have been granted by the caller before this composable is
 * included in the hierarchy (MainActivity / MainScreen manages the permission flow).
 *
 * @param viewModel The ViewModel that owns the [RecordingPipeline].
 * @param aspectRatio Width / height of the camera sensor output in the current orientation
 *   (e.g. 16/9 for 4K HEVC in landscape — but note the camera may be physically rotated
 *   90° relative to the sensor, so pass `9f/16f` for a portrait-mounted phone with a
 *   landscape sensor). Default `9f/16f` matches most portrait-orientation phone cameras.
 * @param modifier Modifier for the outer layout.
 */
@androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
@Composable
fun PreviewSurfaceView(
    viewModel: CameraControlViewModel,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 9f / 16f, // portrait phone with landscape-sensor camera (16:9 → 9:16)
) {
    val context = LocalContext.current

    // SurfaceView is created once and reused across recompositions.
    val surfaceView = remember { SurfaceView(context) }

    DisposableEffect(surfaceView) {
        val callback = object : SurfaceHolder.Callback {
            @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
            override fun surfaceCreated(holder: SurfaceHolder) {
                viewModel.attachPreviewSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Camera2 handles resolution; we don't need to do anything here.
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                viewModel.detachPreviewSurface()
            }
        }
        surfaceView.holder.addCallback(callback)
        onDispose {
            surfaceView.holder.removeCallback(callback)
        }
    }

    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    AndroidView(
        factory = { surfaceView },
        modifier = modifier
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = isLandscape),
    )
}
