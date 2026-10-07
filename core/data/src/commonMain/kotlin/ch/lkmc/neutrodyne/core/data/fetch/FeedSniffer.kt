// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.fetch

/**
 * The byte-level first-element sniff of 03 "Body, hashing and sniffing": BOM-skip, `<?xml …?>`
 * processing instruction, comments and doctype skipped, then the root element name decides. Runs
 * on the first ≤ 1 KiB of the body — a probe read and a full-feed read share the same check.
 */
internal object FeedSniffer {
    const val PROBE_BYTES = 1024

    fun sniff(bytes: ByteArray, length: Int = bytes.size): Sniff {
        var i = 0
        val n = minOf(length, PROBE_BYTES)

        // UTF-8 / UTF-16LE / UTF-16BE BOMs.
        if (n >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            i = 3
        } else if (n >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            i = 2
        } else if (n >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            i = 2
        }

        // Whitespace, then any number of `<?xml …?>`, `<!-- -->` or `<!DOCTYPE …>` preambles.
        while (true) {
            while (i < n && isSpace(bytes[i])) i++
            val rest = remaining(bytes, i, n)
            when {
                rest.startsWith("<?xml") -> i = skipTo(bytes, i + 2, n, "?>") ?: return Sniff.OTHER
                rest.startsWith("<!--") -> i = skipTo(bytes, i + 4, n, "-->") ?: return Sniff.OTHER
                rest.lowercase().startsWith("<!doctype html") -> return Sniff.HTML
                rest.lowercase().startsWith("<!doctype") -> i = skipTo(bytes, i + 2, n, ">") ?: return Sniff.OTHER
                else -> {
                    return when {
                        rest.lowercase().startsWith("<rss") -> Sniff.RSS
                        rest.lowercase().startsWith("<rdf") -> Sniff.RDF
                        rest.lowercase().startsWith("<feed") -> Sniff.ATOM
                        rest.lowercase().startsWith("<opml") -> Sniff.OPML
                        rest.lowercase().startsWith("<html") -> Sniff.HTML
                        rest.startsWith("{") || rest.startsWith("[") -> Sniff.JSON
                        else -> Sniff.OTHER
                    }
                }
            }
        }
    }

    private fun remaining(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        if (offset >= length) return ""
        val end = minOf(offset + 32, length)
        return bytes.decodeToString(offset, end)
    }

    /** Index just past the first [marker], or null when the probe ends first (truncated doc). */
    private fun skipTo(
        bytes: ByteArray,
        from: Int,
        length: Int,
        marker: String,
    ): Int? {
        var i = from
        while (i + marker.length <= length) {
            if (remaining(bytes, i, i + marker.length) == marker) return i + marker.length
            i++
        }
        return null
    }

    private fun isSpace(b: Byte): Boolean = b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte()
}
