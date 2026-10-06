// SPDX-License-Identifier: Unlicense
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.imageio.ImageIO

/** Serialises an ARGB image to PNG bytes with no metadata, so output is byte-for-byte reproducible (01). */
internal object PngBytes {
    fun of(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}

/**
 * Minimal ICO writer with PNG-compressed entries (01 Brand-asset generator): a 6-byte header, one 16-byte
 * directory entry per image, then the PNG payloads. A width or height of 256 is stored as 0, per the format;
 * the ICO format is little-endian, unlike ICNS.
 */
internal object IcoWriter {
    private const val ICO_TYPE_ICON = 1
    private const val DIRECTORY_ENTRY_BYTES = 16
    private const val SIZE_STORED_AS_ZERO = 256
    private const val COLOR_PLANES = 1
    private const val BITS_PER_PIXEL = 32

    fun write(images: List<BufferedImage>): ByteArray {
        val payloads = images.map { PngBytes.of(it) }
        val out = ByteArrayOutputStream()
        out.use { stream ->
            stream.writeShortLe(0)
            stream.writeShortLe(ICO_TYPE_ICON)
            stream.writeShortLe(images.size)
            var offset = 6 + DIRECTORY_ENTRY_BYTES * images.size
            for ((index, image) in images.withIndex()) {
                check(image.width == image.height) { "ICO entries must be square, got ${image.width}x${image.height}" }
                check(image.width in 1..256) { "ICO entry size ${image.width} outside 1..256" }
                stream.write(sizeByte(image.width))
                stream.write(sizeByte(image.height))
                stream.write(0)
                stream.write(0)
                stream.writeShortLe(COLOR_PLANES)
                stream.writeShortLe(BITS_PER_PIXEL)
                stream.writeIntLe(payloads[index].size)
                stream.writeIntLe(offset)
                offset += payloads[index].size
            }
            for (payload in payloads) stream.write(payload)
        }
        return out.toByteArray()
    }

    private fun sizeByte(size: Int): Int = if (size == SIZE_STORED_AS_ZERO) 0 else size

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
        write(value shr 16 and 0xFF)
        write(value shr 24 and 0xFF)
    }
}

/** One ICNS entry: a four-character type code (for example `ic07`) and its PNG payload. */
internal data class IcnsEntry(val type: String, val image: BufferedImage)

/**
 * Minimal ICNS writer with PNG-compressed entries (01 Brand-asset generator): the `icns` magic, the total
 * file length, then for every entry its type code, entry length (header included) and PNG payload.
 */
internal object IcnsWriter {
    private const val MAGIC = "icns"
    private const val ENTRY_HEADER_BYTES = 8

    fun write(entries: List<IcnsEntry>): ByteArray {
        for (entry in entries) {
            check(entry.type.length == 4) { "ICNS type '${entry.type}' must be four characters" }
        }
        val payloads = entries.map { PngBytes.of(it.image) }
        val totalLength = 8 + entries.size * ENTRY_HEADER_BYTES + payloads.sumOf { it.size }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.writeBytes(MAGIC)
            stream.writeInt(totalLength)
            for ((index, entry) in entries.withIndex()) {
                stream.writeBytes(entry.type)
                stream.writeInt(ENTRY_HEADER_BYTES + payloads[index].size)
                stream.write(payloads[index])
            }
        }
        return out.toByteArray()
    }
}
