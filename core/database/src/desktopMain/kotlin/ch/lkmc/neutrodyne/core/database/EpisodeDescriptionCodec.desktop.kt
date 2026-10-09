// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

actual object EpisodeDescriptionCodec {
    actual fun encode(text: String): ByteArray {
        val utf8 = text.encodeToByteArray()
        // Aligned bound: a caller exceeding the producer cap (a custom ParseLimits) must be
        // rejected here, not after persistence — encode output must always round-trip.
        require(utf8.size <= MAX_DECODED_BYTES) {
            "episode_description input exceeds the 2 MiB codec bound"
        }
        if (utf8.size < COMPRESS_ABOVE_BYTES) return byteArrayOf(RAW_TAG) + utf8

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(utf8)
            deflater.finish()
            val out = ByteArrayOutputStream(utf8.size / 2)
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                buffer.write(chunk, 0, n)
            }
            out.write(DEFLATED_TAG.toInt())
            buffer.writeTo(out)
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    actual fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when (bytes[0]) {
            RAW_TAG -> {
                // The codec only writes a raw body below COMPRESS_ABOVE_BYTES; the producer
                // bound caps what any blob may materialise.
                check(bytes.size - 1 <= MAX_DECODED_BYTES) { TOO_BIG }
                bytes.copyOfRange(1, bytes.size).decodeToString()
            }

            DEFLATED_TAG -> {
                inflate(bytes.copyOfRange(1, bytes.size))
            }

            else -> {
                bytes.decodeToString()
            }
        }
    }

    private fun inflate(deflated: ByteArray): String {
        val inflater = Inflater(true)
        try {
            inflater.setInput(deflated)
            val out = ByteArrayOutputStream(minOf(deflated.size * 3, MAX_DECODED_BYTES))
            val chunk = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val n =
                    try {
                        inflater.inflate(chunk)
                    } catch (e: DataFormatException) {
                        throw IllegalStateException("corrupt episode_description deflate body", e)
                    }
                if (n == 0 && !inflater.finished()) {
                    // No progress without reaching the end: truncated or undecodable body.
                    throw IllegalStateException("truncated episode_description deflate body")
                }
                out.write(chunk, 0, n)
                // The bound is derived from the producer cap, not chosen: a blob that decodes
                // past it is a corruption or hostility signal, not show notes.
                check(out.size() <= MAX_DECODED_BYTES) { TOO_BIG }
            }
            return out.toByteArray().decodeToString()
        } finally {
            inflater.end()
        }
    }
}

private const val COMPRESS_ABOVE_BYTES = 512
private const val RAW_TAG: Byte = 0x00
private const val DEFLATED_TAG: Byte = 0x01

/**
 * 03 `ParseLimits.maxTextChars` (512 Ki chars per text element) bounds every producer of a
 * description; at 4 UTF-8 bytes per char that is 2 MiB of decoded output. Past it the blob is
 * not a description.
 */
private const val MAX_DECODED_BYTES = 2 * 1024 * 1024
private const val TOO_BIG = "episode_description decode exceeds the 2 MiB producer bound"
