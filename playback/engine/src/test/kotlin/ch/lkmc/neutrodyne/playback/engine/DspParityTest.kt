// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SilenceSkipperParityTest {
    @Test fun samplesMatchThePinnedAndroidProcessor() = assertParity("skip", 9)
}

class SonicParityTest {
    @Test fun samplesMatchThePinnedAndroidProcessor() = assertParity("sonic", 45)
}

/** References were produced on Android's processors in CI, not by these ports. */
private fun assertParity(stage: String, expectedRows: Int) {
    val manifest = Path.of(requireNotNull(System.getProperty("neutrodyne.moduleDir")), "src/test/resources/dsp-reference.tsv")
    val rows = Files.readAllLines(manifest).filter { it.startsWith("$stage\t") }
    assertEquals(expectedRows, rows.size, "missing or empty Android reference")
    val failures = mutableListOf<String>()
    for (row in rows) {
        val fields = row.split('\t')
        val kind = fields[1]
        val chunk = fields[2].toInt()
        val speed = fields[3].toFloat()
        val input = parityInput(kind)
        val output = ByteArrayOutputStream()
        val skip = SilenceSkipper().apply { configure(PARITY_RATE, PARITY_CHANNELS) }
        val sonic = Sonic(PARITY_RATE, PARITY_CHANNELS, speed, 1f, 48_000)
        fun drain() {
            if (stage == "skip") {
                while (skip.hasOutput()) {
                    val buffer = ByteBuffer.allocate(input.size).order(ByteOrder.nativeOrder())
                    skip.getOutput(buffer)
                    output.write(buffer.array(), 0, buffer.position())
                }
            } else {
                while (sonic.outputSize > 0) {
                    val buffer = ByteBuffer.allocate(sonic.outputSize).order(ByteOrder.nativeOrder())
                    sonic.getOutput(buffer)
                    output.write(buffer.array(), 0, buffer.position())
                }
            }
        }
        var offset = 0
        while (offset < input.size) {
            val size = minOf(chunk * PARITY_CHANNELS * 2, input.size - offset)
            val buffer = ByteBuffer.wrap(input, offset, size).slice().order(ByteOrder.nativeOrder())
            if (stage == "skip") skip.queueInput(buffer) else sonic.queueInput(buffer)
            assertTrue(!buffer.hasRemaining(), "the port did not consume input")
            drain()
            offset += size
        }
        if (stage == "skip") skip.queueEndOfStream() else sonic.queueEndOfStream()
        drain()
        val pcm = output.toByteArray()
        val count = pcm.size / (PARITY_CHANNELS * 2)
        val hash = MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) }
        if (count != fields[4].toInt() || hash != fields[5]) {
            failures += "$stage/$kind chunk=$chunk speed=$speed: count=$count hash=$hash; reference=${fields[4]}/${fields[5]}"
        }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
}

private fun parityInput(kind: String): ByteArray {
    val buffer = ByteBuffer.allocate(PARITY_RATE * PARITY_CHANNELS * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(PARITY_RATE) { frame ->
        val silent = kind == "silence" || frame in 12_000 until 31_000
        val value = when {
            silent -> 0
            kind == "speech" -> ((frame * 73 % 20_003) - 10_001) * (frame / 300 % 3 + 1) / 3
            else -> (frame * 131 % 16_001) - 8_000 + (frame * 37 % 4_001) - 2_000
        }
        buffer.putShort(value.toShort())
        buffer.putShort((-value).toShort())
    }
    return buffer.array()
}

private const val PARITY_RATE = 44_100
private const val PARITY_CHANNELS = 2
