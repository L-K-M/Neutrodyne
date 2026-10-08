// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import java.nio.charset.Charset

/**
 * Byte-level encoding detection shared by the prolog guard, the tag bound and the charset re-parse
 * heuristic (03 Parser setup and charset). The guards must see the document exactly as the pull
 * parser does, so [view] mirrors kxml2's `setInput(InputStream, null)` byte signatures one-to-one:
 * BOMs (UTF-32 before the UTF-16 prefixes they share), BOM-less UTF-32 `<` padding, BOM-less UTF-16
 * only behind `<?`, the `<?xm` declaration's `encoding` name, else UTF-8.
 *
 * The boundary matters: kxml2 buffers the sniffed and declaration bytes as raw chars and decodes
 * only the remainder with the chosen charset, so a whole-stream decode can shift or merge the body
 * out of the guards' view while the parser still reads it. Text inside a comment or body is never
 * consulted — the parser cannot see it either.
 */
internal object EncodingSniff {
    private const val LT = 0x3C
    private const val QM = 0x3F
    private const val GT = 0x3E
    private const val XC = 0x78
    private const val MC = 0x6D
    private const val ENCODING_TOKEN = "encoding"

    /** kxml2 decides the encoding only once four bytes are buffered. */
    private const val SNIFF_BYTES = 4

    /** A UTF-16 BOM consumes two bytes; a UTF-8 BOM three. */
    private const val UTF16_BOM_BYTES = 2
    private const val UTF8_BOM_BYTES = 3

    /** kxml2's store buffer capacity: a declaration whose `>` lies beyond it fails `setInput`. */
    private const val DECL_SCAN_CAPACITY = 8192

    /**
     * The document exactly as the pull parser will read it: [Decoded.prefix] is the text kxml2 keeps
     * buffered as raw bytes-as-chars, [Decoded.dataStart] the byte offset [Decoded.charset] applies
     * from. [Rejected] mirrors the declarations that make `setInput` itself throw — a `>` out of
     * reach, a name that is not quoted or closed, an encoding the runtime does not support.
     */
    sealed interface View {
        class Decoded(
            val prefix: String,
            val dataStart: Int,
            val charset: Charset,
        ) : View

        class Rejected(
            val detail: String,
        ) : View
    }

    /** The charset [bytes] decodes with after the kept bytes; UTF-8 when nothing matches. */
    fun sniff(bytes: ByteArray): Charset = (view(bytes) as? View.Decoded)?.charset ?: Charsets.UTF_8

    /**
     * [bytes] as the pull parser will read them — the kept bytes as raw chars, then the remainder in
     * the detected charset — or null when the parser's own `setInput` would fail the document.
     */
    fun decode(bytes: ByteArray): String? =
        when (val view = view(bytes)) {
            is View.Rejected -> {
                null
            }

            is View.Decoded -> {
                view.prefix + String(bytes, view.dataStart, bytes.size - view.dataStart, view.charset)
            }
        }

    /** The decoding boundary of `KXmlParser.setInput(InputStream, null)`, reproduced one-to-one. */
    fun view(bytes: ByteArray): View {
        // kxml2 buffers what it sniffed as raw chars; short input stays buffered, decoded UTF-8.
        if (bytes.size < SNIFF_BYTES) {
            return View.Decoded(rawChars(bytes, 0, bytes.size), bytes.size, Charsets.UTF_8)
        }
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return when {
            b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF -> {
                charsetOrReject("UTF-32BE", "", SNIFF_BYTES)
            }

            b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00 -> {
                charsetOrReject("UTF-32LE", "", SNIFF_BYTES)
            }

            b0 == 0x00 && b1 == 0x00 && b2 == 0x00 && b3 == LT -> {
                charsetOrReject("UTF-32BE", "<", SNIFF_BYTES)
            }

            b0 == LT && b1 == 0x00 && b2 == 0x00 && b3 == 0x00 -> {
                charsetOrReject("UTF-32LE", "<", SNIFF_BYTES)
            }

            b0 == 0x00 && b1 == LT && b2 == 0x00 && b3 == QM -> {
                View.Decoded("<?", SNIFF_BYTES, Charsets.UTF_16BE)
            }

            b0 == LT && b1 == 0x00 && b2 == QM && b3 == 0x00 -> {
                View.Decoded("<?", SNIFF_BYTES, Charsets.UTF_16LE)
            }

            b0 == LT && b1 == QM && b2 == XC && b3 == MC -> {
                declaredView(bytes)
            }

            b0 == 0xFE && b1 == 0xFF -> {
                View.Decoded("", UTF16_BOM_BYTES, Charsets.UTF_16BE)
            }

            b0 == 0xFF && b1 == 0xFE -> {
                View.Decoded("", UTF16_BOM_BYTES, Charsets.UTF_16LE)
            }

            b0 == 0xEF && b1 == 0xBB && b2 == 0xBF -> {
                View.Decoded(rawChars(bytes, UTF8_BOM_BYTES, SNIFF_BYTES), SNIFF_BYTES, Charsets.UTF_8)
            }

            else -> {
                View.Decoded(rawChars(bytes, 0, SNIFF_BYTES), SNIFF_BYTES, Charsets.UTF_8)
            }
        }
    }

    /**
     * `<?xm`: the declaration bytes up to `>` stay buffered as raw chars; the `encoding` name — the
     * first `encoding` occurrence, scanned to its closing quote — decodes only the remainder. Any
     * shape that makes kxml2's own `setInput` throw is [View.Rejected].
     */
    private fun declaredView(bytes: ByteArray): View {
        val declEnd = bytes.indexOf(GT.toByte())
        if (declEnd < 0) return View.Rejected("XML declaration has no >")
        if (declEnd >= DECL_SCAN_CAPACITY) {
            return View.Rejected("XML declaration over $DECL_SCAN_CAPACITY bytes")
        }
        val prefix = rawChars(bytes, 0, declEnd + 1)
        var nameStart = prefix.indexOf(ENCODING_TOKEN)
        if (nameStart < 0) return View.Decoded(prefix, declEnd + 1, Charsets.UTF_8)
        while (nameStart < prefix.length && prefix[nameStart] != '"' && prefix[nameStart] != '\'') nameStart++
        if (nameStart >= prefix.length) return View.Rejected("encoding name is not quoted")
        val quote = prefix[nameStart++]
        val nameEnd = prefix.indexOf(quote, nameStart)
        if (nameEnd < 0) return View.Rejected("encoding name is not closed")
        val name = prefix.substring(nameStart, nameEnd)
        val charset = canonicalOrNull(name) ?: return View.Rejected("unsupported encoding $name")
        return View.Decoded(prefix, declEnd + 1, charset)
    }

    private fun charsetOrReject(
        name: String,
        prefix: String,
        dataStart: Int,
    ): View =
        canonicalOrNull(name)?.let { View.Decoded(prefix, dataStart, it) }
            ?: View.Rejected("unsupported encoding $name")

    /** Bytes widened to chars — the representation kxml2 keeps in its source buffer. */
    private fun rawChars(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): String =
        buildString(end - start) {
            for (i in start until end) append((bytes[i].toInt() and 0xFF).toChar())
        }

    private fun canonicalOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()
}
