// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import java.nio.charset.Charset

/**
 * Byte-level encoding detection shared by the prolog guard, the tag bound and the charset re-parse
 * heuristic (03 Parser setup and charset). kxml2's `getInputEncoding` reports only the explicitly
 * requested encoding, so the document encoding is sniffed here with the same precedence the parser
 * uses: BOM, then the XML declaration's `encoding`, else UTF-8.
 */
internal object EncodingSniff {
    private const val SNIFF_BYTES = 512
    private val encodingAttribute =
        Regex("""encoding\s*=\s*["']([A-Za-z0-9._\-]+)["']""")

    /** The charset [bytes] decodes with, canonically named; UTF-8 when nothing else is detectable. */
    fun sniff(bytes: ByteArray): Charset {
        bomCharset(bytes)?.let { return it }

        // BOM-less UTF-16 still betrays itself: '<' as 0x00 0x3C or 0x3C 0x00.
        if (bytes.size >= 2 && bytes[0] == 0x00.toByte() && bytes[1] == '<'.code.toByte()) {
            return Charsets.UTF_16BE
        }
        if (bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0x00.toByte()) {
            return Charsets.UTF_16LE
        }

        // The XML declaration is pure ASCII in every ASCII-superset encoding.
        val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.US_ASCII)
        val declared = encodingAttribute.find(head)?.groupValues?.get(1)
        return declared?.let { canonicalOrNull(it) } ?: Charsets.UTF_8
    }

    /** Decodes [bytes] with [sniff]; a leading U+FEFF is kept out of the result. */
    fun decode(bytes: ByteArray): String {
        val text = String(bytes, sniff(bytes))
        return text.removePrefix("\uFEFF")
    }

    private fun bomCharset(bytes: ByteArray): Charset? {
        if (bytes.size >= 4) {
            // UTF-32 BOMs must be checked before their UTF-16 prefixes.
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
                bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
            ) {
                return canonicalOrNull("UTF-32LE")
            }
            if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
                bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
            ) {
                return canonicalOrNull("UTF-32BE")
            }
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        return null
    }

    private fun canonicalOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()
}
