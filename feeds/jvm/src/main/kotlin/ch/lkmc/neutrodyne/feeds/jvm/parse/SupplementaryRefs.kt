// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

/**
 * kxml2 pushes a numeric character reference through a single `char` cast, so a supplementary code
 * point like `&#x1F600;` arrives truncated to U+F600 — in text and in attribute values alike (it
 * also means the literal `😀` and the reference form compare differently downstream). AOSP's parser
 * forks the same code, so the Android binding shares the defect.
 *
 * [rewrite] repairs the decoded document before the parser sees it: supplementary numeric
 * references are replaced by their literal characters wherever the parser's own entity decoding
 * applies — element text and attribute values — while regions it leaves alone (comments, CDATA
 * sections, processing instructions, markup declarations) pass through verbatim. The scan mirrors
 * the relaxed kxml2 lexer, so a reference the parser would never decode is never rewritten: an
 * unterminated `&name<` swallows its terminator as text exactly as `pushEntity` does, and `&#` in a
 * tag name position stays a name. Returns `null` when nothing needs rewriting, so the common feed
 * costs one index probe and no copy.
 */
internal object SupplementaryRefs {
    private const val COMMENT_OPEN = "<!--"
    private const val COMMENT_CLOSE = "-->"
    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"
    private const val PI_OPEN = "<?"
    private const val PI_CLOSE = "?>"
    private const val XML_DECL_NAME = "xml"
    private const val REPLACEMENT_PREFIX = "&#"
    private const val MAX_CODE_POINT = 0x10FFFF
    private const val SUPPLEMENTARY_FLOOR = 0xFFFF
    private const val HEX_RADIX = 16

    /** [text] with supplementary numeric references rewritten to literals, or null when unchanged. */
    fun rewrite(text: String): String? {
        if (text.indexOf(REPLACEMENT_PREFIX) < 0) return null
        var edits: ArrayList<Triple<Int, Int, Int>>? = null
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '&' -> {
                    val end = referenceEnd(text, i)
                    val codePoint = supplementaryOf(text, i, end)
                    if (codePoint != null) {
                        (edits ?: ArrayList<Triple<Int, Int, Int>>().also { edits = it })
                            .add(Triple(i, end, codePoint))
                    }
                    i = end
                }

                '<' -> {
                    i = skipMarkup(text, i, edits ?: ArrayList<Triple<Int, Int, Int>>().also { edits = it })
                }

                else -> {
                    i++
                }
            }
        }
        val found = edits ?: return null
        if (found.isEmpty()) return null
        return buildString(text.length) {
            var pos = 0
            for ((start, end, codePoint) in found) {
                append(text, pos, start)
                appendCodePoint(codePoint)
                pos = end
            }
            append(text, pos, text.length)
        }
    }

    /**
     * The index just past an `&…` reference at [amp]: the name chars `[0-9a-zA-Z_#-]` and anything
     * `>= 0x80`, then one terminator char — `;` for a well-formed reference, or whatever char ended
     * the name otherwise (kxml2's `pushEntity` consumes it into the value either way). This is the
     * same boundary [TagBounds] counts with, so both scans agree on where values end.
     */
    fun referenceEnd(
        text: String,
        amp: Int,
    ): Int {
        var i = amp + 1
        while (i < text.length && isRefChar(text[i])) i++
        return if (i < text.length) i + 1 else i
    }

    /**
     * The code point of a `;`-terminated numeric reference spanning `&#…;`, when it is a supplementary
     * character. Decimal digits or lowercase-`x` hex — kxml2 accepts only `#x`, a `#X` fails its
     * `parseInt` the same way it fails ours, and the verbatim bytes then crash the parser identically.
     */
    private fun supplementaryOf(
        text: String,
        amp: Int,
        end: Int,
    ): Int? {
        if (end - amp < 4 || text[end - 1] != ';' || text[amp + 1] != '#') return null
        val codePoint =
            runCatching {
                if (text[amp + 2] == 'x') {
                    text.substring(amp + 3, end - 1).toInt(HEX_RADIX)
                } else {
                    text.substring(amp + 2, end - 1).toInt()
                }
            }.getOrNull() ?: return null
        return codePoint.takeIf { it > SUPPLEMENTARY_FLOOR && it <= MAX_CODE_POINT }
    }

    /** Past the markup construct at `&lt;`-position [lt]; attribute values inside still get scanned. */
    private fun skipMarkup(
        text: String,
        lt: Int,
        edits: MutableList<Triple<Int, Int, Int>>,
    ): Int =
        when {
            text.startsWith(COMMENT_OPEN, lt) -> {
                endAt(text, COMMENT_CLOSE, lt + COMMENT_OPEN.length)
            }

            text.startsWith(CDATA_OPEN, lt) -> {
                endAt(text, CDATA_CLOSE, lt + CDATA_OPEN.length)
            }

            text.startsWith(PI_OPEN, lt) -> {
                if (isXmlDecl(text, lt)) {
                    scanTag(text, lt + PI_OPEN.length, edits, xmlDecl = true)
                } else {
                    endAt(text, PI_CLOSE, lt + PI_OPEN.length)
                }
            }

            lt + 1 < text.length && text[lt + 1] == '/' -> {
                endTagEnd(text, lt + 2)
            }

            text.startsWith("<!", lt) -> {
                markupDeclEnd(text, lt + 2)
            }

            else -> {
                scanTag(text, lt + 1, edits, xmlDecl = false)
            }
        }

    /** `<?xml` followed by whitespace (or EOF) takes kxml2's xmldecl attribute loop, not the PI one. */
    private fun isXmlDecl(
        text: String,
        lt: Int,
    ): Boolean {
        val nameStart = lt + PI_OPEN.length
        if (!text.regionMatches(nameStart, XML_DECL_NAME, 0, XML_DECL_NAME.length, ignoreCase = true)) {
            return false
        }
        val afterName = nameStart + XML_DECL_NAME.length
        return afterName >= text.length || text[afterName] <= ' '
    }

    private fun endAt(
        text: String,
        marker: String,
        from: Int,
    ): Int {
        val close = text.indexOf(marker, from)
        return if (close < 0) text.length else close + marker.length
    }

    /**
     * An end tag is `</` + name + whitespace + one char in kxml2 (`read('>')` consumes whatever it
     * finds); nothing inside is entity-decoded.
     */
    private fun endTagEnd(
        text: String,
        start: Int,
    ): Int = minOf(skipWhitespace(text, nameEnd(text, start)) + 1, text.length)

    /** `<!…>` as kxml2's `parseDoctype` sees it: `'` quotes, unquoted `<` nests, unquoted `>` ends. */
    private fun markupDeclEnd(
        text: String,
        start: Int,
    ): Int {
        var i = start
        var depth = 1
        var quoted = false
        while (i < text.length) {
            when (text[i]) {
                '\'' -> {
                    quoted = !quoted
                }

                '<' -> {
                    if (!quoted) depth++
                }

                '>' -> {
                    if (!quoted) {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return text.length
    }

    /**
     * The tag starting at [nameStart], entity-decoding inside attribute values like the parser does.
     * In [xmlDecl] form the tag ends on `?` (kxml2's decl path), otherwise on `>` or a degenerate `/`.
     */
    private fun scanTag(
        text: String,
        nameStart: Int,
        edits: MutableList<Triple<Int, Int, Int>>,
        xmlDecl: Boolean,
    ): Int {
        var i = nameEnd(text, nameStart)
        while (i < text.length) {
            i = skipWhitespace(text, i)
            if (i >= text.length) return text.length
            val c = text[i]
            when {
                xmlDecl && c == '?' -> {
                    return minOf(i + 2, text.length)
                }

                !xmlDecl && c == '>' -> {
                    return i + 1
                }

                !xmlDecl && c == '/' -> {
                    return minOf(skipWhitespace(text, i + 1) + 1, text.length)
                }

                else -> {
                    i = skipWhitespace(text, nameEnd(text, i))
                    if (i < text.length && text[i] == '=') {
                        i = skipWhitespace(text, i + 1)
                        if (i < text.length) {
                            val q = text[i]
                            i =
                                if (q == '"' || q == '\'') {
                                    valueEnd(text, i + 1, quote = q, edits)
                                } else {
                                    valueEnd(text, i, quote = ' ', edits)
                                }
                        }
                    }
                }
            }
        }
        return text.length
    }

    /** An attribute value ends on its quote — or on whitespace / `>` when unquoted. */
    private fun valueEnd(
        text: String,
        start: Int,
        quote: Char,
        edits: MutableList<Triple<Int, Int, Int>>,
    ): Int {
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> {
                    val end = referenceEnd(text, i)
                    supplementaryOf(text, i, end)?.let { edits.add(Triple(i, end, it)) }
                    i = end
                }

                quote == ' ' && (c <= ' ' || c == '>') -> {
                    return i
                }

                c == quote -> {
                    return i + 1
                }

                else -> {
                    i++
                }
            }
        }
        return i
    }

    private fun skipWhitespace(
        text: String,
        start: Int,
    ): Int {
        var i = start
        while (i < text.length && text[i] <= ' ') i++
        return i
    }

    /** Relaxed `readName`: any first char, then letters, digits, `_`, `-`, `:`, `.`, `>= 0xB7`. */
    private fun nameEnd(
        text: String,
        start: Int,
    ): Int {
        var i = start
        if (i < text.length) i++
        while (i < text.length && isNameChar(text[i])) i++
        return i
    }

    private fun isNameChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c == '_' || c == '-' || c == ':' || c == '.' || c.code >= 0xB7

    /** The char set `pushEntity` collects before its terminator: letters, digits, `_`, `#`, `-`, `>= 0x80`. */
    private fun isRefChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '#' || c == '-' || c.code >= 0x80
}
