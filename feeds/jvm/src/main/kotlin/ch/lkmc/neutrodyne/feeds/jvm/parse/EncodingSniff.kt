// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import java.nio.charset.Charset

/**
 * Byte-level encoding detection shared by the prolog guard, the tag bound and the charset re-parse
 * heuristic (03 Parser setup and charset). The guards must see the document exactly as the pull
 * parser does, so [sniff] mirrors kxml2's `setInput` byte signatures one-to-one: BOMs (UTF-32 before
 * the UTF-16 prefixes they share), BOM-less UTF-32 `<` padding, BOM-less UTF-16 only behind `<?`,
 * the `<?xm` declaration's `encoding` name, else UTF-8. Text inside a comment or body is never
 * consulted — the parser cannot see it either.
 */
internal object EncodingSniff {
    private const val LT = 0x3C
    private const val QM = 0x3F
    private const val GT = 0x3E
    private const val XC = 0x78
    private const val MC = 0x6D
    private const val ENCODING_TOKEN = "encoding"

    /** The charset [bytes] decodes with; UTF-8 when nothing the pull parser supports matches. */
    fun sniff(bytes: ByteArray): Charset {
        // kxml2 decides only once four bytes are buffered; shorter input is UTF-8.
        if (bytes.size < 4) return Charsets.UTF_8
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return when {
            b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF -> canonicalOrNull("UTF-32BE") ?: Charsets.UTF_8
            b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00 -> canonicalOrNull("UTF-32LE") ?: Charsets.UTF_8
            b0 == 0x00 && b1 == 0x00 && b2 == 0x00 && b3 == LT -> canonicalOrNull("UTF-32BE") ?: Charsets.UTF_8
            b0 == LT && b1 == 0x00 && b2 == 0x00 && b3 == 0x00 -> canonicalOrNull("UTF-32LE") ?: Charsets.UTF_8
            b0 == 0x00 && b1 == LT && b2 == 0x00 && b3 == QM -> Charsets.UTF_16BE
            b0 == LT && b1 == 0x00 && b2 == QM && b3 == 0x00 -> Charsets.UTF_16LE
            b0 == LT && b1 == QM && b2 == XC && b3 == MC -> declaredEncoding(bytes) ?: Charsets.UTF_8
            b0 == 0xFE && b1 == 0xFF -> Charsets.UTF_16BE
            b0 == 0xFF && b1 == 0xFE -> Charsets.UTF_16LE
            b0 == 0xEF && b1 == 0xBB && b2 == 0xBF -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }
    }

    /** Decodes [bytes] with [sniff]; a leading U+FEFF is kept out of the result. */
    fun decode(bytes: ByteArray): String {
        val text = String(bytes, sniff(bytes))
        return text.removePrefix("\uFEFF")
    }

    /**
     * The `encoding=` name of the leading XML declaration, kxml2's way: the raw declaration bytes up
     * to `>` are ASCII, the first `encoding` occurrence wins, and its name runs to the closing quote.
     * A malformed or unknown name reports nothing — the parser's own `setInput` fails the same way.
     */
    private fun declaredEncoding(bytes: ByteArray): Charset? {
        val declEnd = bytes.indexOf(GT.toByte())
        val decl = String(bytes, 0, if (declEnd < 0) bytes.size else declEnd + 1, Charsets.US_ASCII)
        var start = decl.indexOf(ENCODING_TOKEN)
        if (start < 0) return null
        while (start < decl.length && decl[start] != '"' && decl[start] != '\'') start++
        if (start >= decl.length) return null
        val quote = decl[start++]
        val end = decl.indexOf(quote, start)
        if (end < 0) return null
        return canonicalOrNull(decl.substring(start, end))
    }

    private fun canonicalOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()
}
