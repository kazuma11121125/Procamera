package com.procamera.recorder.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.procamera.recorder.audio.NativeEngineBridge
import com.procamera.recorder.muxer.PtsClockDomain
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Dedicated thread (§4.4: "専用エンコーダスレッドがRingBufferからドレインしqueueInputBuffer")
 * draining the native SPSC ring buffer, converting Float32 -> 16-bit PCM via TPDF dither
 * (§4.2, [PcmDither]), and feeding MediaCodec's AAC-LC encoder in synchronous buffer
 * mode. Audio PTS is derived purely from cumulative sample count (§4.3,
 * drift-free) via [ptsClockDomain] — never from this thread's wall-clock timing.
 *
 * Untested framework glue (needs a live MediaCodec + running NativeEngineBridge) — see
 * docs/ARCHITECTURE.md's note on compile-verified-only status pending Phase 4's
 * end-to-end wiring.
 */
class AudioEncoder(
    private val sampleRateHz: Int,
    private val channelCount: Int,
    bitrate: Int,
    private val nativeEngine: NativeEngineBridge,
    private val ptsClockDomain: PtsClockDomain,
    private val callback: Callback,
) {
    interface Callback {
        fun onOutputFormatChanged(format: MediaFormat)
        fun onEncodedFrame(buffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
        fun onError(exception: Exception)
    }

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var cumulativeSampleCount = 0L
    private var formatAnnounced = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    /**
     * Starts the encoder and the drain thread. Callers should seed [ptsClockDomain]'s
     * audio anchor (preferably via `startAudioAnchorFromFrameCorrelation` — see
     * PtsClockDomain's doc for why that's preferred over a raw `startAudioAnchor()` call)
     * before or immediately after calling this.
     */
    fun start() {
        codec.start()
        running.set(true)
        drainThread = thread(name = "AudioEncoderDrain", priority = Thread.NORM_PRIORITY) { drainLoop() }
    }

    /** Signals end-of-stream and waits for the drain thread to finish (§4.4's stop sequence). */
    fun stop() {
        running.set(false)
        drainThread?.join(DRAIN_THREAD_JOIN_TIMEOUT_MS)
        codec.stop()
        codec.release()
    }

    private fun drainLoop() {
        val floatScratch = FloatArray(FRAMES_PER_BLOCK * channelCount)
        val shortScratch = ShortArray(FRAMES_PER_BLOCK * channelCount)
        val byteScratch = java.nio.ByteBuffer.allocateDirect(shortScratch.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (running.get()) {
                drainOutputAvailable(bufferInfo)

                val framesRead = nativeEngine.drainEncoderBuffer(floatScratch, FRAMES_PER_BLOCK)
                if (framesRead == 0) {
                    Thread.sleep(BUFFER_EMPTY_SLEEP_MS)
                    continue
                }
                val sampleCount = framesRead * channelCount

                PcmDither.floatToInt16Tpdf(
                    input = floatScratch.copyOf(sampleCount),
                    output = if (sampleCount == shortScratch.size) shortScratch else ShortArray(sampleCount),
                )

                val ptsUs = ptsClockDomain.normalizeAudioPtsUs(cumulativeSampleCount, sampleRateHz)
                cumulativeSampleCount += framesRead
                if (ptsUs == null) {
                    continue // non-monotonic (shouldn't happen for a purely-increasing counter, but guarded per §4.3)
                }

                byteScratch.clear()
                for (i in 0 until sampleCount) {
                    byteScratch.putShort(shortScratch[i])
                }
                byteScratch.flip()

                queueInput(byteScratch, ptsUs, endOfStream = false)
            }

            // Final EOS buffer, per §4.4's stop sequence (EOS -> drain all pending output -> stop/release).
            val finalPtsUs = ptsClockDomain.normalizeAudioPtsUs(cumulativeSampleCount, sampleRateHz)
                ?: (cumulativeSampleCount * 1_000_000L / sampleRateHz)
            queueInput(java.nio.ByteBuffer.allocateDirect(0), finalPtsUs, endOfStream = true)
            drainOutputUntilEos(bufferInfo)
        } catch (e: Exception) {
            callback.onError(e)
        }
    }

    private fun queueInput(data: java.nio.ByteBuffer, ptsUs: Long, endOfStream: Boolean) {
        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) return
        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(data)
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        codec.queueInputBuffer(inputIndex, 0, data.position(), ptsUs, flags)
    }

    private fun drainOutputAvailable(bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!formatAnnounced) {
                        formatAnnounced = true
                        callback.onOutputFormatChanged(codec.outputFormat)
                    }
                }
                outputIndex >= 0 -> emitOutput(outputIndex, bufferInfo)
                else -> return // INFO_TRY_AGAIN_LATER or INFO_OUTPUT_BUFFERS_CHANGED (deprecated path)
            }
        }
    }

    private fun drainOutputUntilEos(bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!formatAnnounced) {
                    formatAnnounced = true
                    callback.onOutputFormatChanged(codec.outputFormat)
                }
                continue
            }
            if (outputIndex < 0) continue
            emitOutput(outputIndex, bufferInfo)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    private fun emitOutput(outputIndex: Int, bufferInfo: MediaCodec.BufferInfo) {
        val buffer = codec.getOutputBuffer(outputIndex)
        if (buffer != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            callback.onEncodedFrame(buffer, bufferInfo)
        }
        codec.releaseOutputBuffer(outputIndex, false)
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val BUFFER_EMPTY_SLEEP_MS = 2L
        const val DRAIN_THREAD_JOIN_TIMEOUT_MS = 3_000L

        // Drain granularity: ~10.7ms @48kHz, comfortably below the ring buffer's ~10s
        // capacity headroom (OboeFullDuplexEngine::kRingBufferCapacityFrames) and small
        // enough to keep the AAC encoder's input latency low.
        const val FRAMES_PER_BLOCK = 512
    }
}
