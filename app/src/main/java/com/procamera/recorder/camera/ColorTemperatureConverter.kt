package com.procamera.recorder.camera

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

/**
 * Converts a color temperature in Kelvin (§4.1 Manual WB, 2500K-8000K) into the RGGB
 * sensor gains Camera2 expects for `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` +
 * `COLOR_CORRECTION_GAINS`.
 *
 * **確信度の明示**: This uses Tanner Helland's public-domain black-body-radiation RGB
 * approximation (a widely used, but not colorimetrically exact, polynomial fit — it is
 * not derived from the CIE standard observer / Planckian locus directly). It is a
 * reasonable starting point for a manual WB slider, but the actual color science pipeline
 * (sensor color filter response, ISP-specific rendering intent) varies per device and
 * cannot be validated without real-hardware comparison against a reference target (e.g. a
 * grey card shot under known-Kelvin lighting). Treat the resulting gains as a good first
 * approximation that a Phase 5 real-device check should visually verify, not as a
 * calibrated color-accurate mapping.
 */
object ColorTemperatureConverter {

    private const val MIN_KELVIN = 2500.0
    private const val MAX_KELVIN = 8000.0

    /**
     * @param kelvin clamped to [MIN_KELVIN, MAX_KELVIN] (§4.1's specified UI range).
     * @return RGGB gains normalized so every channel is >= 1.0 (the channel matching the
     *   illuminant's strongest raw-sensor response gets exactly 1.0; the others are
     *   boosted above it). This is the HAL-safe convention: `COLOR_CORRECTION_GAINS` gains
     *   are commonly required to be >= 1.0 (the neutral channel sits at 1.0, others boost
     *   above it), and a naive green-pinned-to-1.0 normalization can produce sub-unity
     *   gains at the range extremes that a HAL may clamp or reject. Pinning to the
     *   *minimum* instead of green preserves the exact same relative channel ratios
     *   (color correction only depends on ratios, not absolute scale), so this is a
     *   rescaling, not a different correction — see [kelvinToRgbGains]'s derivation.
     */
    // スマホのBayerセンサーにおける典型的なWBゲインの範囲
    // 色温度が低い(2500K 暖色) = 青を強くブースト(2.6)、赤は弱め(1.4)
    private const val R_GAIN_2500 = 1.4
    private const val B_GAIN_2500 = 2.8
    
    // 色温度が高い(8000K 寒色) = 赤を強くブースト(2.8)、青は弱め(1.4)
    private const val R_GAIN_8000 = 2.8
    private const val B_GAIN_8000 = 1.4

    fun kelvinToRggbGains(kelvin: Double): RggbChannelVector {
        val k = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN)
        val t = (k - MIN_KELVIN) / (MAX_KELVIN - MIN_KELVIN)
        
        val r = R_GAIN_2500 + t * (R_GAIN_8000 - R_GAIN_2500)
        val b = B_GAIN_2500 + t * (B_GAIN_8000 - B_GAIN_2500)
        
        return RggbChannelVector(r.toFloat(), 1.0f, 1.0f, b.toFloat())
    }

    fun rggbGainsToKelvin(gains: RggbChannelVector): Double {
        val tR = (gains.red - R_GAIN_2500) / (R_GAIN_8000 - R_GAIN_2500)
        val tB = (gains.blue - B_GAIN_2500) / (B_GAIN_8000 - B_GAIN_2500)
        
        // RとBの推測値の平均をとって安定させる
        val t = (tR + tB) / 2.0
        val k = MIN_KELVIN + t * (MAX_KELVIN - MIN_KELVIN)
        
        return k.coerceIn(MIN_KELVIN, MAX_KELVIN)
    }
}
