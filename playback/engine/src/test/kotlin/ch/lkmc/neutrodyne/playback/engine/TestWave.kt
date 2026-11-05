// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Known PCM in a WAV container, generated independently of the decoder under test. */
internal fun writeTestWave(
    pcm: ByteArray,
    sampleRate: Int = 44_100,
): Path {
    val buffer = ByteBuffer.allocate(WAVE_HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer
        .put("RIFF".toByteArray())
        .putInt(36 + pcm.size)
        .put("WAVEfmt ".toByteArray())
        .putInt(16)
        .putShort(1)
        .putShort(2)
        .putInt(sampleRate)
        .putInt(sampleRate * 4)
        .putShort(4)
        .putShort(16)
        .put("data".toByteArray())
        .putInt(pcm.size)
        .put(pcm)
    return Files.createTempFile("engine-clock-", ".wav").also { Files.write(it, buffer.array()) }
}

private const val WAVE_HEADER_BYTES = 44
