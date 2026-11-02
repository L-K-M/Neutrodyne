// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** Independent reference producer: these are the pinned Android processors, never the ports. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class Md0DspReferenceTest {
    @Test
    fun exportAndroidReferencePcm() {
        val directory = Path.of(requireNotNull(System.getProperty("neutrodyne.moduleDir")), "build/md0-reference")
        Files.createDirectories(directory)
        val rows = mutableListOf("# Media3 1.11.1; PCM s16le; 44100 Hz; stereo; synthetic public-domain input")
        for (kind in listOf("speech", "music", "silence")) {
            for (chunk in listOf(127, 1_024, 8_192)) {
                val input = input(kind)
                val skip = SilenceSkippingAudioProcessor(250_000, 0.2f, 400_000, 10, 1_024)
                skip.setEnabled(true)
                val silence = process(skip, input, chunk)
                rows += row("skip", kind, chunk, 1f, silence)
                for (speed in listOf(0.5f, 1f, 1.5f, 2f, 3f)) {
                    val sonic = SonicAudioProcessor()
                    sonic.setSpeed(speed)
                    // 1x is intentionally active too, by resampling to 48 kHz.
                    sonic.setOutputSampleRateHz(48_000)
                    rows += row("sonic", kind, chunk, speed, process(sonic, input, chunk))
                }
            }
        }
        val text = rows.joinToString("\n", postfix = "\n")
        Files.writeString(directory.resolve("dsp-reference.tsv"), text)
        println(text)
        assertTrue(rows.size > 50)
        val root = Path.of(requireNotNull(System.getProperty("neutrodyne.rootDir")))
        val expected = Files.readAllLines(root.resolve("playback/engine/src/test/resources/dsp-reference.tsv"))
            .filterNot { it.startsWith("#") || it.isBlank() }
        assertEquals(expected, rows.filterNot { it.startsWith("#") }, "the pinned Android reference changed")
    }

    private fun process(processor: AudioProcessor, input: ByteArray, chunkFrames: Int): ByteArray {
        processor.configure(AudioProcessor.AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val result = ByteArrayOutputStream()
        fun drain() {
            val output = processor.output
            val bytes = ByteArray(output.remaining())
            output.get(bytes)
            result.write(bytes)
        }
        var offset = 0
        while (offset < input.size) {
            val size = minOf(chunkFrames * CHANNELS * 2, input.size - offset)
            val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            buffer.put(input, offset, size).flip()
            while (buffer.hasRemaining()) {
                processor.queueInput(buffer)
                drain()
            }
            offset += size
        }
        processor.queueEndOfStream()
        while (!processor.isEnded) drain()
        processor.reset()
        return result.toByteArray()
    }

    private fun row(stage: String, kind: String, chunk: Int, speed: Float, pcm: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) }
        return "$stage\t$kind\t$chunk\t$speed\t${pcm.size / (CHANNELS * 2)}\t$hash"
    }

    private fun input(kind: String): ByteArray {
        val buffer = ByteBuffer.allocate(RATE * CHANNELS * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(RATE) { frame ->
            val silent = kind == "silence" || frame in 12_000 until 31_000
            val value =
                when {
                    silent -> 0
                    kind == "speech" -> ((frame * 73 % 20_003) - 10_001) * (frame / 300 % 3 + 1) / 3
                    else -> (frame * 131 % 16_001) - 8_000 + (frame * 37 % 4_001) - 2_000
                }
            buffer.putShort(value.toShort())
            buffer.putShort((-value).toShort())
        }
        return buffer.array()
    }

    private companion object {
        const val RATE = 44_100
        const val CHANNELS = 2
    }
}
