package com.aucampro.recorder.encoder

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams interleaved 32-bit float PCM to a RIFF/WAVE file (`WAVE_FORMAT_IEEE_FLOAT`,
 * format tag 3) — the hi-res audio "サイドカー" sink (docs/HIRES_AUDIO_DESIGN.md §1/§6.2).
 * Fully lossless: the ring buffer this app's audio engine produces is already 32-bit
 * float, so this is header + memcpy, no requantization.
 *
 * The RIFF/fact/data chunk *size* fields are unknown until the stream ends (this is a
 * live stream, not a fixed-size buffer), so they're written as placeholders in the
 * constructor and back-patched in [close]. The header reserves a standard `JUNK` chunk;
 * recordings that exceed RIFF's 32-bit size limit convert that reservation to RF64's
 * `ds64` chunk at close, allowing one WAV to span the complete take. Smaller recordings
 * remain ordinary RIFF/WAVE files for maximum compatibility. This mirrors
 * [com.aucampro.recorder.pipeline.RecordingPipeline]'s `emergencyFinalizeRecording`
 * crash-safety pattern for the MP4/muxer side: [close] must be safe to call from a crash
 * handler mid-stream, not just after a clean stop, so the file is still a valid (if
 * truncated) WAV rather than a header full of zeros.
 *
 * Single-writer, no internal synchronization — matches [AudioEncoder]'s drain-thread
 * ownership (see its class doc on the ring buffer's single-consumer invariant): this class
 * must only ever be driven from that one thread, including [close].
 */
class WavFileWriter(
    path: String,
    private val sampleRateHz: Int,
    private val channelCount: Int,
) : AutoCloseable {
    private val file = RandomAccessFile(path, "rw")
    private var samplesWrittenPerChannel = 0L
    private var closed = false

    // Lazily sized to the caller's actual block size and kept for reuse — avoids a
    // per-write allocation (same reasoning as AudioEncoder.drainLoop's floatScratch/
    // shortScratch/byteScratch reuse) without needing the caller to declare a max block
    // size up front.
    private var scratch = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    private val pendingBytes = ByteArray(WRITE_BUFFER_SIZE_BYTES)
    private var pendingByteCount = 0

    init {
        // A take name collision must replace the old file rather than leave stale PCM
        // bytes after the newly finalized data chunk.
        file.setLength(0L)

        val bitsPerSample = 32
        val byteRate = sampleRateHz * channelCount * (bitsPerSample / 8)
        val blockAlign = channelCount * (bitsPerSample / 8)

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put(ASCII_RIFF)
        header.putInt(0) // RIFF chunk size — back-patched in close()
        header.put(ASCII_WAVE)
        // Reserve exactly enough space for RF64's ds64 payload. For normal-size files it
        // remains an ignorable JUNK chunk; for >4GiB it is rewritten in place at close.
        header.put(ASCII_JUNK)
        header.putInt(DS64_PAYLOAD_SIZE)
        repeat(DS64_PAYLOAD_SIZE) { header.put(0) }
        header.put(ASCII_FMT)
        header.putInt(16) // fmt chunk size (no extension needed for plain IEEE float)
        header.putShort(3) // wFormatTag = WAVE_FORMAT_IEEE_FLOAT
        header.putShort(channelCount.toShort())
        header.putInt(sampleRateHz)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put(ASCII_FACT)
        header.putInt(4) // fact chunk size
        header.putInt(0) // dwSampleLength (per channel) — back-patched in close()
        header.put(ASCII_DATA)
        header.putInt(0) // data chunk size — back-patched in close()
        file.write(header.array())
    }

    /** Writes [frameCount] interleaved frames ([frameCount] * [channelCount] floats,
     * starting at index 0 of [interleaved]) to the stream. */
    fun writeFrames(interleaved: FloatArray, frameCount: Int) {
        val sampleCount = frameCount * channelCount
        val neededBytes = sampleCount * BYTES_PER_SAMPLE
        if (scratch.capacity() < neededBytes) {
            scratch = ByteBuffer.allocate(neededBytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        scratch.clear()
        // Bulk-copy through FloatBuffer rather than one virtual putFloat call per sample.
        // This runs on the same consumer thread that must keep up with a 192kHz producer.
        scratch.asFloatBuffer().put(interleaved, 0, sampleCount)
        appendPending(scratch.array(), neededBytes)
        samplesWrittenPerChannel += frameCount
    }

    /**
     * Coalesces the 32KiB-ish blocks produced every ~21ms at 192kHz into 1MiB writes.
     * RandomAccessFile.write() is synchronous from this drain thread's point of view.
     * Profiling showed these writes were not the primary throughput bottleneck, but
     * reducing ~47 calls/sec to ~1.5 calls/sec lowers filesystem-crossing overhead and
     * exposure to individual storage-latency spikes while preserving the exact byte stream.
     */
    private fun appendPending(source: ByteArray, byteCount: Int) {
        var sourceOffset = 0
        while (sourceOffset < byteCount) {
            val copyCount = minOf(
                byteCount - sourceOffset,
                pendingBytes.size - pendingByteCount,
            )
            System.arraycopy(
                source,
                sourceOffset,
                pendingBytes,
                pendingByteCount,
                copyCount,
            )
            sourceOffset += copyCount
            pendingByteCount += copyCount
            if (pendingByteCount == pendingBytes.size) flushPending()
        }
    }

    private fun flushPending() {
        if (pendingByteCount == 0) return
        file.write(pendingBytes, 0, pendingByteCount)
        pendingByteCount = 0
    }

    /** Total `data` chunk bytes written so far. */
    fun dataBytesWritten(): Long = samplesWrittenPerChannel * channelCount * BYTES_PER_SAMPLE

    /** Back-patches the RIFF/fact/data size fields with the actual amount written, then
     * closes the file. Idempotent — safe to call twice (e.g. once from a crash-safety
     * finalize path and once, harmlessly, from the normal stop path that follows it). */
    override fun close() {
        if (closed) return
        closed = true
        flushPending()
        val dataBytes = dataBytesWritten()
        val riffChunkSize = (HEADER_SIZE - 8L) + dataBytes

        if (requiresRf64(dataBytes, riffChunkSize)) {
            file.seek(CONTAINER_ID_OFFSET)
            file.write(ASCII_RF64)
            file.seek(RIFF_SIZE_OFFSET)
            file.write(intLe(UINT32_MAX_AS_INT))
            file.seek(RESERVED_CHUNK_ID_OFFSET)
            file.write(ASCII_DS64)
            file.seek(DS64_RIFF_SIZE_OFFSET)
            file.write(longLe(riffChunkSize))
            file.write(longLe(dataBytes))
            file.write(longLe(samplesWrittenPerChannel))
            file.write(intLe(0)) // ds64 table length
            file.seek(FACT_SAMPLE_LENGTH_OFFSET)
            file.write(intLe(samplesWrittenPerChannel.coerceAtMost(UINT32_MAX).toInt()))
            file.seek(DATA_SIZE_OFFSET)
            file.write(intLe(UINT32_MAX_AS_INT))
        } else {
            file.seek(RIFF_SIZE_OFFSET)
            file.write(intLe(riffChunkSize.toInt()))
            file.seek(FACT_SAMPLE_LENGTH_OFFSET)
            file.write(intLe(samplesWrittenPerChannel.toInt()))
            file.seek(DATA_SIZE_OFFSET)
            file.write(intLe(dataBytes.toInt()))
        }
        file.close()
    }

    private fun intLe(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun longLe(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private companion object {
        const val BYTES_PER_SAMPLE = 4 // 32-bit float
        const val WRITE_BUFFER_SIZE_BYTES = 1024 * 1024
        const val DS64_PAYLOAD_SIZE = 28
        const val HEADER_SIZE = 92 // RIFF(12) + JUNK/ds64(36) + fmt(24) + fact(12) + data(8)
        const val CONTAINER_ID_OFFSET = 0L
        const val RIFF_SIZE_OFFSET = 4L
        const val RESERVED_CHUNK_ID_OFFSET = 12L
        const val DS64_RIFF_SIZE_OFFSET = 20L
        const val FACT_SAMPLE_LENGTH_OFFSET = 80L
        const val DATA_SIZE_OFFSET = 88L
        const val UINT32_MAX = 0xffff_ffffL
        const val UINT32_MAX_AS_INT = -1

        val ASCII_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        val ASCII_RF64 = "RF64".toByteArray(Charsets.US_ASCII)
        val ASCII_WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
        val ASCII_JUNK = "JUNK".toByteArray(Charsets.US_ASCII)
        val ASCII_DS64 = "ds64".toByteArray(Charsets.US_ASCII)
        val ASCII_FMT = "fmt ".toByteArray(Charsets.US_ASCII)
        val ASCII_FACT = "fact".toByteArray(Charsets.US_ASCII)
        val ASCII_DATA = "data".toByteArray(Charsets.US_ASCII)
    }
}

internal fun requiresRf64(dataBytes: Long, riffChunkSize: Long): Boolean =
    dataBytes > 0xffff_ffffL || riffChunkSize > 0xffff_ffffL
