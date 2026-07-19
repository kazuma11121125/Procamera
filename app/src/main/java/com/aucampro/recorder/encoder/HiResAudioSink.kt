package com.aucampro.recorder.encoder

/**
 * Owns the hi-res WAV "サイドカー" output (docs/HIRES_AUDIO_DESIGN.md §1/§6.2/§6.4) — a
 * plain, lossless 32-bit float WAV recorded alongside (never instead of) the existing
 * AAC/MP4 path. Driven entirely from [AudioEncoder]'s own drain thread, the same float
 * blocks it reads from the ring buffer for the AAC path — never a second ring-buffer
 * consumer (see [AudioEncoder]'s SPSC single-consumer invariant doc).
 *
 * One sink owns one WAV file for the complete recording. It never rotates by elapsed
 * time; this mirrors the MP4 path's one-file-per-recording behavior.
 */
class HiResAudioSink(
    outputPath: String,
    sampleRateHz: Int,
    channelCount: Int,
) {
    private val writer = WavFileWriter(outputPath, sampleRateHz, channelCount)

    /** Drain-thread only (see class doc). */
    fun writeFrames(interleaved: FloatArray, frameCount: Int) {
        writer.writeFrames(interleaved, frameCount)
    }

    /** Drain-thread only. Idempotent via [WavFileWriter.close]'s own idempotency — safe to
     * call from both the normal stop path and a crash-safety finalize path (which may run
     * on a different thread mid-write; same accepted best-effort risk as
     * [com.aucampro.recorder.pipeline.RecordingPipeline.emergencyFinalizeRecording]'s
     * muxer finalize — goal is a playable file, not a guaranteed-complete one). */
    fun close() {
        writer.close()
    }
}
