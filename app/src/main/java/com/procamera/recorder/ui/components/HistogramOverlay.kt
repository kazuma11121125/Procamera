package com.procamera.recorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.procamera.recorder.ui.theme.Amber
import com.procamera.recorder.ui.theme.SurfaceBlack
import com.procamera.recorder.ui.theme.SurfaceVariant

/**
 * Luminance histogram (Sony__________.pdf's "ヒストグラム(輝度分布グラフ)" UI assist) —
 * a plain bar-graph readout of [bins] (normalized [0,1] per bucket, dark-to-bright left to
 * right), matching Sony's own framing of this as a luminance distribution rather than
 * per-channel RGB. See [com.procamera.recorder.camera.LuminanceHistogramReader] for how the
 * data is sampled; this composable is purely a renderer.
 *
 * `null` while no sample has arrived yet (before the first preview frame, or once a
 * recording starts — see that class's doc for why it freezes rather than updating during
 * recording) — nothing is drawn in that case rather than showing a misleading empty graph.
 */
@Composable
fun HistogramOverlay(bins: FloatArray?, modifier: Modifier = Modifier) {
    if (bins == null || bins.isEmpty()) return

    Canvas(
        modifier = modifier
            .size(width = 160.dp, height = 56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceBlack.copy(alpha = 0.55f))
            .border(1.dp, SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(4.dp),
    ) {
        val barWidth = size.width / bins.size
        for (i in bins.indices) {
            val barHeight = size.height * bins[i]
            drawRect(
                color = Amber.copy(alpha = 0.85f),
                topLeft = Offset(i * barWidth, size.height - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}
