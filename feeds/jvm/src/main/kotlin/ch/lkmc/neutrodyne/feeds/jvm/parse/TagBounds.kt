// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.parse.ParseLimits
import org.xmlpull.v1.XmlPullParserException

/**
 * A single encoding-aware pass over the raw document that bounds start-tag work *before* the pull
 * parser sees any tag (03 Limits and version policy). kxml2 grows its attribute and namespace arrays
 * quadratically while processing a start tag, so checking `attributeCount` after `next()` returns is
 * already too late: a million `xmlns:pN` attributes would copy trillions of entries. Over the limit
 * the document is reported as `MALFORMED`.
 */
internal object TagBounds {
    private const val COMMENT_OPEN = "<!--"
    private const val COMMENT_CLOSE = "-->"
    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"
    private const val PI_OPEN = "<?"
    private const val PI_CLOSE = "?>"

    /** Throws [XmlPullParserException] when any start tag carries more attributes than the limit. */
    fun check(
        bytes: ByteArray,
        limits: ParseLimits,
    ) {
        val text = EncodingSniff.decode(bytes)
        var i = 0
        while (i < text.length) {
            when {
                text[i] != '<' -> i++
                text.startsWith(COMMENT_OPEN, i) -> i = skipTo(text, COMMENT_CLOSE, i + COMMENT_OPEN.length)
                text.startsWith(CDATA_OPEN, i) -> i = skipTo(text, CDATA_CLOSE, i + CDATA_OPEN.length)
                text.startsWith(PI_OPEN, i) -> i = skipTo(text, PI_CLOSE, i + PI_OPEN.length)
                text.startsWith("<!", i) -> i = skipMarkupDecl(text, i + 2)
                i + 1 < text.length && text[i + 1] == '/' -> i = skipToEndTag(text, i + 2)
                i + 1 < text.length && isNameStart(text[i + 1]) -> i = scanStartTag(text, i + 1, limits)
                else -> i++
            }
        }
    }

    private fun skipTo(
        text: String,
        marker: String,
        from: Int,
    ): Int {
        val close = text.indexOf(marker, from)
        return if (close < 0) text.length else close + marker.length
    }

    /**
     * Past one `<!…>` markup declaration, honouring quotes and the `[…]` internal subset. Inside the
     * subset, comments and processing instructions keep their own delimiters — a quote inside
     * `<!-- " -->` or `<?p " ?>` is not a quoted literal and must not swallow the `]>` that closes
     * the declaration.
     */
    private fun skipMarkupDecl(
        text: String,
        start: Int,
    ): Int {
        var i = start
        var inSubset = false
        var quote = 0.toChar()
        while (i < text.length) {
            val c = text[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
                i++
                continue
            }
            when {
                c == '"' || c == '\'' -> {
                    quote = c
                    i++
                }

                inSubset && text.startsWith(COMMENT_OPEN, i) -> {
                    i = skipTo(text, COMMENT_CLOSE, i + COMMENT_OPEN.length)
                }

                inSubset && text.startsWith(PI_OPEN, i) -> {
                    i = skipTo(text, PI_CLOSE, i + PI_OPEN.length)
                }

                c == '[' -> {
                    inSubset = true
                    i++
                }

                c == ']' && inSubset -> {
                    var j = i + 1
                    while (j < text.length && text[j].isWhitespace()) j++
                    if (j < text.length && text[j] == '>') return j + 1
                    i++
                }

                c == '>' && !inSubset -> {
                    return i + 1
                }

                else -> {
                    i++
                }
            }
        }
        return text.length
    }

    private fun skipToEndTag(
        text: String,
        start: Int,
    ): Int {
        val close = text.indexOf('>', start)
        return if (close < 0) text.length else close + 1
    }

    /** Counts `name="value"` pairs of one start tag; throws past [ParseLimits.maxTagAttributes]. */
    private fun scanStartTag(
        text: String,
        start: Int,
        limits: ParseLimits,
    ): Int {
        var i = start
        // The tag name itself is not an attribute.
        while (i < text.length && !text[i].isWhitespace() && text[i] != '>' && text[i] != '/') i++
        var attributes = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() || c == '/' -> {
                    i++
                }

                c == '>' -> {
                    return i + 1
                }

                else -> {
                    // Attribute name, then an optional =value.
                    while (i < text.length && !text[i].isWhitespace() &&
                        text[i] != '=' && text[i] != '>' && text[i] != '/'
                    ) {
                        i++
                    }
                    while (i < text.length && text[i].isWhitespace()) i++
                    if (i < text.length && text[i] == '=') {
                        i++
                        while (i < text.length && text[i].isWhitespace()) i++
                        if (i < text.length && (text[i] == '"' || text[i] == '\'')) {
                            val q = text[i]
                            i++
                            while (i < text.length && text[i] != q) i++
                            i++
                        } else {
                            while (i < text.length && !text[i].isWhitespace() && text[i] != '>') i++
                        }
                    }
                    attributes++
                    if (attributes > limits.maxTagAttributes) {
                        throw XmlPullParserException("start tag over ${limits.maxTagAttributes} attributes")
                    }
                }
            }
        }
        return text.length
    }

    private fun isNameStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == ':' || c.code > 127
}
