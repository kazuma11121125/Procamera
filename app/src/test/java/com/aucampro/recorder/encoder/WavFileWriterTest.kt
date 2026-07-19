package com.aucampro.recorder.encoder

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class WavFileWriterTest {

    @Test
    fun close_flushesBufferedPcmAndBackPatchesHeader() {
        val output = File.createTempFile("aucampro-wav-writer", ".wav")
        try {
            val frameCount = 150_000 // 1.2MiB stereo Float32: full buffer + partial tail.
            val samples = FloatArray(frameCount * 2) { index ->
                ((index % 2000) - 1000) / 1000f
            }

            WavFileWriter(output.absolutePath, sampleRateHz = 192_000, channelCount = 2)
                .use { writer ->
                    writer.writeFrames(samples, frameCount)
                    assertThat(writer.dataBytesWritten()).isEqualTo(samples.size * 4L)
                }

            assertThat(output.length()).isEqualTo(92L + samples.size * 4L)
            RandomAccessFile(output, "r").use { file ->
                assertThat(readIntLe(file, 4L)).isEqualTo((output.length() - 8L).toInt())
                assertThat(readAscii(file, 0L)).isEqualTo("RIFF")
                assertThat(readAscii(file, 12L)).isEqualTo("JUNK")
                assertThat(readIntLe(file, 80L)).isEqualTo(frameCount)
                assertThat(readIntLe(file, 88L)).isEqualTo(samples.size * 4)

                file.seek(92L)
                assertThat(readFloatLe(file)).isEqualTo(samples.first())
                file.seek(output.length() - 4L)
                assertThat(readFloatLe(file)).isEqualTo(samples.last())
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun rf64Threshold_accountsForRiffHeaderOverhead() {
        assertThat(
            requiresRf64(
                dataBytes = 0xffff_ffffL - 84L,
                riffChunkSize = 0xffff_ffffL,
            ),
        ).isFalse()
        assertThat(
            requiresRf64(
                dataBytes = 0xffff_ffffL - 83L,
                riffChunkSize = 0x1_0000_0000L,
            ),
        ).isTrue()
    }

    private fun readAscii(file: RandomAccessFile, offset: Long): String {
        file.seek(offset)
        return ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
    }

    private fun readIntLe(file: RandomAccessFile, offset: Long): Int {
        file.seek(offset)
        return ByteBuffer.wrap(ByteArray(4).also(file::readFully))
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }

    private fun readFloatLe(file: RandomAccessFile): Float =
        ByteBuffer.wrap(ByteArray(4).also(file::readFully))
            .order(ByteOrder.LITTLE_ENDIAN)
            .float
}
