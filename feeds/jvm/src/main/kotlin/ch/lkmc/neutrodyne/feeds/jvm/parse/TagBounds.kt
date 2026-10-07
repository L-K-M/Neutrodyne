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
 *
 * The scan must cut markup exactly where relaxed kxml2 does — a construct this guard over-reads hides
 * real start tags from the bound, while one it under-reads only costs false positives. That means:
 * a DOCTYPE ends at the first `>` outside a *single* quote (`'` is the only quote kxml2 honours there)
 * and nests on unquoted `<`; a `<` followed by anything other than `!`, `?` or `/` opens a start tag
 * whose name's first character is unrestricted (`<0 …>` is a tag to kxml2); whitespace inside a tag
 * is every char `<= ' '`; and an unquoted attribute value runs until whitespace or `>` while
 * `&name;` references inside values consume a terminator that may itself be the quote or `>`.
 */
internal object TagBounds {
    private const val COMMENT_OPEN = "<!--"
    private const val COMMENT_CLOSE = "-->"
    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"
    private const val PI_OPEN = "<?"
    private const val PI_CLOSE = "?>"
    private const val XML_DECL_NAME = "xml"

    /**
     * Throws [XmlPullParserException] when any start tag carries more attributes than the limit.
     * [text] is the document decoded exactly as the pass' pull parser will read it — the sniffed
     * view for the first pass, the override charset for the re-parse.
     */
    fun checkText(
        text: String,
        limits: ParseLimits,
    ) {
        var i = 0
        while (i < text.length) {
            when {
                text[i] != '<' -> i++
                text.startsWith(COMMENT_OPEN, i) -> i = skipTo(text, COMMENT_CLOSE, i + COMMENT_OPEN.length)
                text.startsWith(CDATA_OPEN, i) -> i = skipTo(text, CDATA_CLOSE, i + CDATA_OPEN.length)
                text.startsWith(PI_OPEN, i) -> i = scanLegacyPi(text, i, limits)
                i + 1 < text.length && text[i + 1] == '/' -> i = skipToEndTag(text, i + 2)
                text.startsWith("<!", i) -> i = skipMarkupDecl(text, i + 2)
                // Relaxed kxml2 pushes any first name char: `<0 …>` is a start tag to it.
                else -> i = scanStartTag(text, i + 1, limits, xmlDecl = false)
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
     * `<?`: kxml2 runs `<?xml` followed by whitespace through its attribute loop (the xmldecl path)
     * and treats any other target as a plain processing instruction. The decl form gets the same
     * attribute bound as a start tag — a giant `<?xml a1="v" a2="v" … ?>` floods kxml2's attribute
     * array the same way a start tag does.
     */
    private fun scanLegacyPi(
        text: String,
        start: Int,
        limits: ParseLimits,
    ): Int {
        val nameStart = start + PI_OPEN.length
        if (text.regionMatches(nameStart, XML_DECL_NAME, 0, XML_DECL_NAME.length, ignoreCase = true)) {
            val afterName = nameStart + XML_DECL_NAME.length
            // kxml2 reads `x`, `m`, `l` then requires the next char to be `<= ' '` (EOF qualifies).
            if (afterName >= text.length || text[afterName] <= ' ') {
                return scanStartTag(text, nameStart, limits, xmlDecl = true)
            }
        }
        return skipTo(text, PI_CLOSE, nameStart)
    }

    /**
     * Past one `<!…>` markup declaration, exactly as kxml2's `parseDoctype` sees it: a single quote
     * `'` toggles the quoted state (double quotes mean nothing there), an unquoted `<` nests and an
     * unquoted `>` at depth zero ends the declaration. External identifiers and internal subsets are
     * therefore bounded the same way the parser consumes them — a `"` inside a DOCTYPE cannot hide
     * markup from this scan, and a `]>`/`>` inside a quoted literal does not end it early.
     */
    private fun skipMarkupDecl(
        text: String,
        start: Int,
    ): Int {
        var i = start
        // The `<` that opened the declaration counts towards the nesting depth, like kxml2's `1`.
        var depth = 1
        var quoted = false
        while (i < text.length) {
            when (text[i]) {
                '\'' -> quoted = !quoted
                '<' -> if (!quoted) depth++
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

    private fun skipToEndTag(
        text: String,
        start: Int,
    ): Int {
        val close = text.indexOf('>', start)
        return if (close < 0) text.length else close + 1
    }

    /**
     * Counts `name="value"` pairs of one start tag; throws past [ParseLimits.maxTagAttributes]. The
     * walk mirrors kxml2's relaxed `parseStartTag`: names are one unconditional first char plus
     * name chars, whitespace inside a tag is `<= ' '`, an absent `=` costs one attribute, and quoted
     * or unquoted values may contain `&…` references that swallow their terminator — an `&x>` in an
     * unquoted value eats the `>` kxml2 would have closed on, so the bound must keep counting too.
     * [xmlDecl] is the `<?xml` variant, which ends on `?` instead of `>`.
     */
    private fun scanStartTag(
        text: String,
        start: Int,
        limits: ParseLimits,
        xmlDecl: Boolean,
    ): Int {
        var i = start
        i = nameEnd(text, i)
        var attributes = 0
        while (i < text.length) {
            i = skipTagWhitespace(text, i)
            if (i >= text.length) return text.length
            val c = text[i]
            when {
                xmlDecl && c == '?' -> {
                    // xmldecl ends on `?`; kxml2 then consumes one more char expecting `>`.
                    return minOf(i + 2, text.length)
                }

                !xmlDecl && c == '>' -> return i + 1

                !xmlDecl && c == '/' -> {
                    // Degenerated tag: `/`, whitespace, then a single char read expecting `>`.
                    i = skipTagWhitespace(text, i + 1)
                    return minOf(i + 1, text.length)
                }

                else -> {
                    // Attribute name, then an optional =value.
                    i = nameEnd(text, i)
                    attributes++
                    if (attributes > limits.maxTagAttributes) {
                        throw XmlPullParserException("start tag over ${limits.maxTagAttributes} attributes")
                    }
                    i = skipTagWhitespace(text, i)
                    if (i < text.length && text[i] == '=') {
                        i = skipTagWhitespace(text, i + 1)
                        if (i < text.length) {
                            val q = text[i]
                            if (q == '"' || q == '\'') {
                                i = valueEnd(text, i + 1, quote = q)
                            } else {
                                // Unquoted value: kxml2's delimiter is `' '` — whitespace or `>` ends it.
                                i = valueEnd(text, i, quote = ' ')
                            }
                        }
                    }
                }
            }
        }
        return text.length
    }

    /**
     * kxml2's `readName`: the first char is consumed unconditionally in relaxed mode, then chars
     * from its name set — letters, digits, `_`, `-`, `:`, `.`, and anything `>= 0xB7`.
     */
    private fun nameEnd(
        text: String,
        start: Int,
    ): Int {
        var i = start
        if (i < text.length) i++
        while (i < text.length && isNameChar(text[i])) i++
        return i
    }

    /** Tag-context whitespace is every char `<= ' '` — kxml2's `skip()` rule, not Unicode's. */
    private fun skipTagWhitespace(
        text: String,
        start: Int,
    ): Int {
        var i = start
        while (i < text.length && text[i] <= ' ') i++
        return i
    }

    /**
     * Past an attribute value: a quoted one ends on its quote, an unquoted one on whitespace or `>`.
     * Inside either, `&` starts an entity reference whose terminator — even a quote or `>` — is
     * consumed by the reference scan, exactly as `pushEntity` swallows it into the value.
     */
    private fun valueEnd(
        text: String,
        start: Int,
        quote: Char,
    ): Int {
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> i = SupplementaryRefs.referenceEnd(text, i)
                quote == ' ' && (c <= ' ' || c == '>') -> return i
                c == quote -> return i + 1
                else -> i++
            }
        }
        return i
    }

    private fun isNameChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c == '_' || c == '-' || c == ':' || c == '.' || c.code >= 0xB7
}
