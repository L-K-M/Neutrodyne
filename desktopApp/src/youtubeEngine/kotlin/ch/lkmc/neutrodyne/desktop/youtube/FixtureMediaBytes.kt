// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.youtube

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Arrays

/**
 * The deterministic media body behind [LoopbackMediaFixture] (MD3 AC2's media slice): a
 * caller-provided complete, valid small M4A — preserved byte for byte — followed by exactly
 * one spec-valid MP4 `free` box that pads the stream to [size]. Nothing is materialised:
 * every read composes its bytes on demand, so the proof's ~21 MiB body is never a committed
 * blob and never a heap object.
 *
 * The head contract is what the download lane's `MediaSniffer` checks (`ftyp` at bytes 4–7)
 * plus a plausible first-box length, so random junk cannot fake a valid stream. Callers pass
 * a real file — `playback/engine`'s synthetic `m4a_lc_2s.m4a` is the documented one; the
 * appended top-level `free` box keeps the composition box-valid MP4.
 */
internal class FixtureMediaBytes(
    head: ByteArray,
    val size: Long,
) {
    private val head = head.copyOf()
    private val freeBoxHeader: ByteArray

    init {
        require(head.size >= BOX_HEADER_BYTES) { "the media head must carry at least one MP4 box" }
        require(head.copyOfRange(FTYP_TYPE_OFFSET, FTYP_TYPE_OFFSET + BOX_TYPE_BYTES).decodeToString() == FTYP) {
            "the media head must start with an ftyp box"
        }
        val firstBox = unsignedInt(head, 0)
        require(firstBox in BOX_HEADER_BYTES.toLong()..head.size.toLong()) {
            "the head's first box length is not plausible"
        }
        val padding = size - head.size
        require(padding >= BOX_HEADER_BYTES) {
            "size must leave room for the trailing free box ($padding B left)"
        }
        freeBoxHeader =
            if (padding <= UINT32_MAX) {
                ByteBuffer
                    .allocate(BOX_HEADER_BYTES)
                    .putInt(padding.toInt())
                    .put(FREE_TYPE)
                    .array()
            } else {
                // The 64-bit largesize form needs the 16-byte extended header.
                require(padding >= EXTENDED_BOX_HEADER_BYTES)
                ByteBuffer
                    .allocate(EXTENDED_BOX_HEADER_BYTES)
                    .putInt(EXTENDED_SIZE_MARKER)
                    .put(FREE_TYPE)
                    .putLong(padding)
                    .array()
            }
    }

    /** The byte at [index] in the composition: head, free-box header or padding zero. */
    fun byteAt(index: Long): Byte {
        require(index in 0 until size) { "index outside the media body" }
        val one = ByteArray(1)
        fill(index, one, 0, 1)
        return one[0]
    }

    /** [length] bytes starting at [offset], for proof-side expected-byte comparisons. */
    fun readRange(
        offset: Long,
        length: Int,
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "range outside the media body" }
        return ByteArray(length).also { fill(offset, it, 0, length) }
    }

    /**
     * Streams the inclusive range to [out], updating [digest] with every byte passed. The
     * count returned is what `out` accepted; a write failure propagates with the completed
     * prefix counted.
     */
    fun copyRangeTo(
        startInclusive: Long,
        endInclusive: Long,
        out: OutputStream,
        digest: MessageDigest? = null,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var written = 0L
        var position = startInclusive
        while (position <= endInclusive) {
            val chunk = minOf(buffer.size.toLong(), endInclusive - position + 1).toInt()
            fill(position, buffer, 0, chunk)
            digest?.update(buffer, 0, chunk)
            out.write(buffer, 0, chunk)
            position += chunk
            written += chunk
        }
        return written
    }

    /** A stream over the whole composition; reads compose bytes, nothing is buffered. */
    fun openStream(): InputStream =
        object : InputStream() {
            private var position = 0L

            override fun read(): Int =
                if (position >= size) {
                    -1
                } else {
                    val one = ByteArray(1)
                    fill(position, one, 0, 1)
                    position++
                    one[0].toInt() and 0xFF
                }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (length == 0) return 0
                if (position >= size) return -1
                val count = minOf(length.toLong(), size - position).toInt()
                fill(position, buffer, offset, count)
                position += count
                return count
            }

            override fun skip(count: Long): Long {
                val skipped = minOf(count, size - position).coerceAtLeast(0L)
                position += skipped
                return skipped
            }
        }

    /** The whole-file digest of the deterministic composition, streamed once on first use. */
    val sha256Hex: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var position = 0L
        while (position < size) {
            val chunk = minOf(buffer.size.toLong(), size - position).toInt()
            fill(position, buffer, 0, chunk)
            digest.update(buffer, 0, chunk)
            position += chunk
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fill(
        filePos: Long,
        dst: ByteArray,
        dstOffset: Int,
        length: Int,
    ) {
        var position = filePos
        var offset = dstOffset
        var remaining = length
        if (position < head.size) {
            val take = minOf(remaining.toLong(), head.size - position).toInt()
            System.arraycopy(head, position.toInt(), dst, offset, take)
            position += take
            offset += take
            remaining -= take
        }
        val boxOffset = position - head.size
        if (remaining > 0 && boxOffset < freeBoxHeader.size) {
            val take = minOf(remaining, freeBoxHeader.size - boxOffset.toInt())
            System.arraycopy(freeBoxHeader, boxOffset.toInt(), dst, offset, take)
            offset += take
            remaining -= take
        }
        if (remaining > 0) Arrays.fill(dst, offset, offset + remaining, 0.toByte())
    }

    private fun unsignedInt(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var value = 0L
        for (index in offset until offset + 4) value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        return value
    }

    private companion object {
        private const val FTYP = "ftyp"
        private const val FTYP_TYPE_OFFSET = 4
        private const val BOX_TYPE_BYTES = 4
        private const val BOX_HEADER_BYTES = 8
        private const val EXTENDED_BOX_HEADER_BYTES = 16
        private const val EXTENDED_SIZE_MARKER = 1
        private const val UINT32_MAX = 0xFFFFFFFFL
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val FREE_TYPE = "free".toByteArray(Charsets.US_ASCII)
    }
}
