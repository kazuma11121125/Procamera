package com.procamera.recorder.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.procamera.recorder.muxer.PtsClockDomain
import java.nio.ByteBuffer

/**
 * MediaCodec video encoder in async mode using `createInputSurface()` (§4.4): Camera2
 * renders directly into [inputSurface] (handed to the capture session as one of the two
 * output targets, §4.1's dual-surface requirement); encoded output arrives via
 * [Callback.onEncodedFrame] with an already PTS-normalized BufferInfo.
 *
 * **Design constraint (from review) that later Camera2/Service wiring must honor**: with
 * the InputSurface path, frames never pass through `queueInputBuffer` — the Camera HAL's
 * `SENSOR_TIMESTAMP` flows automatically through to the encoder and surfaces as
 * `bufferInfo.presentationTimeUs` on this OUTPUT callback, in the same raw clock domain
 * `PtsClockDomain` calibrates against via `onCaptureCompleted`. This class converts that
 * back to nanoseconds (`* 1000L`) and calls `normalizeVideoPtsUs` itself, on the OUTPUT
 * side — never on input, since there is no input side to hook here. The
 * [ptsClockDomain] instance passed in must be the same one Phase 4's Camera2 session
 * owner feeds calibration samples into via `addUnknownCalibrationSample` (only relevant
 * for `TimestampSource.Unknown`), so calibration state isn't duplicated/inconsistent
 * across the video and audio paths.
 *
 * Untested framework glue (MediaCodec async callbacks require a live codec instance) —
 * see docs/ARCHITECTURE.md's note on this being compile-verified only until Phase 4 wires
 * a real CameraCaptureSession into it.
 */
class VideoEncoder(
    mimeType: String,
    width: Int,
    height: Int,
    frameRate: Int,
    bitrate: Int,
    private val ptsClockDomain: PtsClockDomain,
    private val callback: Callback,
) {
    interface Callback {
        fun onOutputFormatChanged(format: MediaFormat)
        fun onEncodedFrame(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
        fun onError(exception: Exception)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(mimeType)
    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // §4.4: 1s I-frame interval
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Not used: the InputSurface path feeds the encoder directly from the
                // Camera HAL, bypassing queueInputBuffer entirely.
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (info.size == 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                val buffer = codec.getOutputBuffer(index)
                if (buffer == null) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }

                val normalizedPtsUs = ptsClockDomain.normalizeVideoPtsUs(info.presentationTimeUs * 1000L)
                if (normalizedPtsUs == null) {
                    // Dropped: calibration not ready yet (UNKNOWN source, still
                    // collecting samples) or non-monotonic (§4.3's guard). The Muxer's
                    // pending-queue-before-start behavior (§4.4) is what's expected to
                    // absorb the resulting gap at the very start of recording.
                    codec.releaseOutputBuffer(index, false)
                    return
                }

                val adjustedInfo = MediaCodec.BufferInfo().apply {
                    set(info.offset, info.size, normalizedPtsUs, info.flags)
                }
                callback.onEncodedFrame(buffer, adjustedInfo)
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                callback.onError(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                callback.onOutputFormatChanged(format)
            }
        })

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
    }

    /** Call once, from the recording-stop sequence, before draining/stopping (§4.4). */
    fun signalEndOfStream() {
        codec.signalEndOfInputStream()
    }

    fun stop() {
        codec.stop()
        codec.release()
        inputSurface.release()
    }
}
