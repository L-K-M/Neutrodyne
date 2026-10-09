// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

actual object EpisodeDescriptionCodec {
    actual fun encode(text: String): ByteArray {
        val utf8 = text.encodeToByteArray()
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
            RAW_TAG -> bytes.copyOfRange(1, bytes.size).decodeToString()
            DEFLATED_TAG -> inflate(bytes.copyOfRange(1, bytes.size))
            else -> bytes.decodeToString()
        }
    }

    private fun inflate(deflated: ByteArray): String {
        val inflater = Inflater(true)
        try {
            inflater.setInput(deflated)
            val out = ByteArrayOutputStream(deflated.size * 3)
            val chunk = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                // A corrupt deflate body throws DataFormatException; the common contract is
                // "never throws", so a bad body (-1) returns whatever inflated so far.
                val n =
                    try {
                        inflater.inflate(chunk)
                    } catch (_: DataFormatException) {
                        -1
                    }
                val stalled = n == 0 && (inflater.needsInput() || inflater.needsDictionary())
                if (n < 0 || stalled) break
                out.write(chunk, 0, n)
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
