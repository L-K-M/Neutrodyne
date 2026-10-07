// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.fetch

/**
 * The first-element sniff of 03 "Body, hashing and sniffing": the leading bytes are decoded with
 * the document's own encoding (BOMs and kxml2's BOM-less signatures for UTF-16/32), whitespace,
 * `<?xml …?>` declarations, comments and DOCTYPEs are skipped, then the root element name
 * decides. Runs on the first ≤ 64 KiB of the body — the prolog-guard window, so long comments or
 * DOCTYPEs before the root still classify — for a probe read and a full-feed read alike.
 */
internal object FeedSniffer {
    /** The 64 KiB prolog-guard window (03 Body, hashing and sniffing). */
    const val PROBE_BYTES = 64 * 1024

    fun sniff(
        bytes: ByteArray,
        length: Int = bytes.size,
    ): Sniff {
        val n = minOf(length, PROBE_BYTES)
        val (encoding, bomLength) = detectEncoding(bytes, n)
        val text = decode(bytes, bomLength, n, encoding)
        var i = 0

        // Whitespace, then any number of `<?xml …?>`, `<!-- -->` or `<!DOCTYPE …>` preambles.
        while (true) {
            while (i < text.length && text[i].isXmlSpace()) i++
            val rest = text.substring(i)
            when {
                rest.startsWith("<?xml") -> {
                    i = text.indexOf("?>", i + 2).takeIf { it >= 0 }?.plus(2) ?: return Sniff.OTHER
                }

                rest.startsWith("<!--") -> {
                    i = text.indexOf("-->", i + 4).takeIf { it >= 0 }?.plus(3) ?: return Sniff.OTHER
                }

                rest.startsWith("<!doctype html", ignoreCase = true) -> {
                    return Sniff.HTML
                }

                rest.startsWith("<!doctype", ignoreCase = true) -> {
                    i = text.indexOf('>', i + 2).takeIf { it >= 0 }?.plus(1) ?: return Sniff.OTHER
                }

                else -> {
                    return when {
                        rest.startsWith("<rss", ignoreCase = true) -> Sniff.RSS
                        rest.startsWith("<rdf", ignoreCase = true) -> Sniff.RDF
                        rest.startsWith("<feed", ignoreCase = true) -> Sniff.ATOM
                        rest.startsWith("<opml", ignoreCase = true) -> Sniff.OPML
                        rest.startsWith("<html", ignoreCase = true) -> Sniff.HTML
                        rest.startsWith("{") || rest.startsWith("[") -> Sniff.JSON
                        else -> Sniff.OTHER
                    }
                }
            }
        }
    }

    /**
     * The document encoding from the leading bytes — kxml2's `setInput(stream, null)` signature
     * table (03 Prolog guard): BOMs, BOM-less UTF-32 (`<` with NUL padding), and BOM-less UTF-16
     * only when the document opens with `<?xml`. Everything else is UTF-8.
     * Returns the encoding and the BOM length to skip (0 for signatures).
     */
    private fun detectEncoding(
        bytes: ByteArray,
        n: Int,
    ): Pair<SniffEncoding, Int> {
        if (n >= 4) {
            if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
                bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
            ) {
                return SniffEncoding.UTF32BE to 4
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
                bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
            ) {
                return SniffEncoding.UTF32LE to 4
            }
            // BOM-less UTF-32: `<` with NUL padding.
            if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
                bytes[2] == 0x00.toByte() && bytes[3] == '<'.code.toByte()
            ) {
                return SniffEncoding.UTF32BE to 0
            }
            if (bytes[0] == '<'.code.toByte() && bytes[1] == 0x00.toByte() &&
                bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
            ) {
                return SniffEncoding.UTF32LE to 0
            }
        }
        if (n >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return SniffEncoding.UTF8 to 3
        }
        if (n >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return SniffEncoding.UTF16BE to 2
        }
        if (n >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return SniffEncoding.UTF16LE to 2
        }
        // BOM-less UTF-16 only when the document opens with `<?xml` (03 EncodingSniff).
        if (n >= 10 && bytes[0] == '<'.code.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == '?'.code.toByte() && bytes[3] == 0x00.toByte() &&
            bytes[4] == 'x'.code.toByte() && bytes[5] == 0x00.toByte() &&
            bytes[6] == 'm'.code.toByte() && bytes[7] == 0x00.toByte() &&
            bytes[8] == 'l'.code.toByte() && bytes[9] == 0x00.toByte()
        ) {
            return SniffEncoding.UTF16LE to 0
        }
        if (n >= 10 && bytes[0] == 0x00.toByte() && bytes[1] == '<'.code.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == '?'.code.toByte() &&
            bytes[4] == 0x00.toByte() && bytes[5] == 'x'.code.toByte() &&
            bytes[6] == 0x00.toByte() && bytes[7] == 'm'.code.toByte() &&
            bytes[8] == 0x00.toByte() && bytes[9] == 'l'.code.toByte()
        ) {
            return SniffEncoding.UTF16BE to 0
        }
        return SniffEncoding.UTF8 to 0
    }

    private fun decode(
        bytes: ByteArray,
        start: Int,
        end: Int,
        encoding: SniffEncoding,
    ): String =
        when (encoding) {
            SniffEncoding.UTF8 -> bytes.decodeToString(start, end)
            SniffEncoding.UTF16LE -> decodeUtf16(bytes, start, end, littleEndian = true)
            SniffEncoding.UTF16BE -> decodeUtf16(bytes, start, end, littleEndian = false)
            SniffEncoding.UTF32LE -> decodeUtf32(bytes, start, end, littleEndian = true)
            SniffEncoding.UTF32BE -> decodeUtf32(bytes, start, end, littleEndian = false)
        }

    private fun decodeUtf16(
        bytes: ByteArray,
        start: Int,
        end: Int,
        littleEndian: Boolean,
    ): String {
        val chars = CharArray((end - start) / 2)
        for (i in chars.indices) {
            val a = bytes[start + i * 2].toInt() and 0xFF
            val b = bytes[start + i * 2 + 1].toInt() and 0xFF
            chars[i] = (if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar()
        }
        return chars.concatToString()
    }

    private fun decodeUtf32(
        bytes: ByteArray,
        start: Int,
        end: Int,
        littleEndian: Boolean,
    ): String {
        val out = StringBuilder((end - start) / 4)
        var i = start
        while (i + 4 <= end) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            val c = bytes[i + 2].toInt() and 0xFF
            val d = bytes[i + 3].toInt() and 0xFF
            val codePoint =
                if (littleEndian) {
                    (d shl 24) or (c shl 16) or (b shl 8) or a
                } else {
                    (a shl 24) or (b shl 16) or (c shl 8) or d
                }
            when {
                codePoint in 0..0xFFFF -> {
                    out.append(codePoint.toChar())
                }

                codePoint in 0x10000..0x10FFFF -> {
                    val v = codePoint - 0x10000
                    out.append((0xD800 + (v shr 10)).toChar())
                    out.append((0xDC00 + (v and 0x3FF)).toChar())
                }
            }
            i += 4
        }
        return out.toString()
    }

    private fun Char.isXmlSpace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

    private enum class SniffEncoding { UTF8, UTF16LE, UTF16BE, UTF32LE, UTF32BE }
}
