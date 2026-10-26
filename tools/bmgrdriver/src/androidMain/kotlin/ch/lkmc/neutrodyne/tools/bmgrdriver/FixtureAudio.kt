// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import kotlin.math.sin

/**
 * Deterministic PCM WAV the bgstart driver serves over loopback. WAV extraction is
 * frame-exact, so seeking to the seeded position (750 s) is deterministic; a sine tone
 * keeps the frame's silence skipper (it skips only near-silence) from dropping the audio.
 * 8 kHz 16-bit mono keeps the generated body small (~14 MB for 900 s).
 */
internal object FixtureAudio {
    const val SAMPLE_RATE = 8_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val STREAM_SECONDS = 900

    fun pcm(seconds: Int): ByteArray {
        val frames = seconds * SAMPLE_RATE
        val dataSize = frames * 2
        val body = ByteArray(44 + dataSize)
        writeHeader(body, dataSize)
        var i = 44
        for (frame in 0 until frames) {
            val sample = (sin(frame * 440.0 * 2.0 * Math.PI / SAMPLE_RATE) * 8192).toInt()
            body[i] = (sample and 0xFF).toByte()
            body[i + 1] = (sample shr 8).toByte()
            i += 2
        }
        return body
    }

    private fun writeHeader(
        body: ByteArray,
        dataSize: Int,
    ) {
        fun ascii(
            offset: Int,
            value: String,
        ) = value.toByteArray().copyInto(body, offset)

        fun le32(
            offset: Int,
            value: Int,
        ) {
            for (shift in 0..24 step 8) body[offset + shift / 8] = (value shr shift).toByte()
        }

        fun le16(
            offset: Int,
            value: Int,
        ) {
            body[offset] = (value and 0xFF).toByte()
            body[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        le32(4, 36 + dataSize)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1)
        le16(22, 1)
        le32(24, SAMPLE_RATE)
        le32(28, BYTES_PER_SECOND)
        le16(32, 2)
        le16(34, 16)
        ascii(36, "data")
        le32(40, dataSize)
    }
}
