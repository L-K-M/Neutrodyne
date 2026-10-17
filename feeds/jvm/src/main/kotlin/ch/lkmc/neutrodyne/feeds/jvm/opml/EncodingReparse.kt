// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.jvm.parse.EncodingSniff
import okio.BufferedSource
import java.nio.charset.Charset

/**
 * The misdeclared-encoding repair shared by the cascade's relaxed and salvage legs, carried over
 * from 03's feed parser ("re-parse with the HTTP charset when it differs from the detected one,
 * else windows-1252" — there is no HTTP charset for a file): when the pass's decode of the head
 * bytes was a single-byte-default charset and the field text it produced holds more than 0.5 %
 * U+FFFD replacement characters, the declaration lied. One extra attempt decodes the raw bytes as
 * windows-1252 and the attempt with fewer replacements wins.
 *
 * A UTF-16/32 detection never re-decodes: a single-byte read of a 16-bit stream is NUL padding,
 * not repair. A charset already covering every byte value (ISO-8859-1, windows-1252 itself) cannot
 * produce replacement characters, so the threshold gate settles them before this is consulted.
 */
internal val REPARSE_CHARSET: Charset = Charset.forName(REPARSE_CHARSET_NAME)

internal const val REPARSE_CHARSET_NAME = "windows-1252"

/** Head bytes an encoding sniff needs — the declaration's `>` must be inside them. */
private const val HEAD_SNIFF_BYTES = 8 * 1024

/**
 * The charset `setInput(stream, null)` picks — [EncodingSniff] reproduces kxml2's byte signatures
 * one-to-one, so a peeked head answers which decode a pass ran without trusting a declaration the
 * stream may lie about.
 */
internal fun sniffedCharset(source: BufferedSource): Charset {
    val peeked = source.peek()
    peeked.request(HEAD_SNIFF_BYTES.toLong())
    val head = peeked.readByteArray(minOf(HEAD_SNIFF_BYTES.toLong(), peeked.buffer.size))
    return EncodingSniff.sniff(head, head.size)
}

/** Only the single-byte-default decodes can come out cleaner re-read as windows-1252. */
internal fun redecodable(detected: Charset): Boolean = detected == Charsets.UTF_8 || detected == Charsets.US_ASCII

/**
 * Counts U+FFFD replacement characters over produced field text (attribute values and element
 * text), the signal the re-parse decision and comparison run on.
 */
internal class ReplacementMeter {
    var textChars = 0L
        private set
    var replacementChars = 0L
        private set

    fun add(text: String) {
        textChars += text.length
        replacementChars += text.count { it == REPLACEMENT_CHAR }
    }

    /** More than the feed parser's 0.5 % replacement-character threshold. */
    fun mojibake(): Boolean = replacementChars * MOJIBAKE_DENOMINATOR > textChars * MOJIBAKE_NUMERATOR

    private companion object {
        const val REPLACEMENT_CHAR = '�'

        /** `replacementChars / textChars > 5/1000` without float math or a divide-by-zero. */
        const val MOJIBAKE_NUMERATOR = 5L
        const val MOJIBAKE_DENOMINATOR = 1000L
    }
}
