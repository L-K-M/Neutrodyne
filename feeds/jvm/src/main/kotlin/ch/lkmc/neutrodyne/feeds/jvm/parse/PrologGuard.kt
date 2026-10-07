// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

/**
 * The prolog guard (03 Parser setup and charset step 1): the first 64 KiB are decoded with the
 * detected encoding, then walked through the real prolog constructs — whitespace, comments,
 * processing instructions and markup declarations — until the root element. `<!ENTITY` inside a
 * declaration means the document is hostile. `FEATURE_PROCESS_DOCDECL` stays off and no external
 * DTD is ever fetched (N9).
 *
 * Declaration boundaries are cut exactly where relaxed kxml2 cuts them — `parseDoctype` honours
 * single quotes only and ends a declaration at the first unquoted `>` — so a `"`-quoted DOCTYPE
 * literal cannot hide markup from the guard, and constructs inside an internal subset are scanned
 * rather than treated as one opaque literal. The prolog ends at the first `</`-`-`/`!`-`?`-less
 * `<x` too: relaxed kxml2 accepts any name-start character there (digits included).
 */
internal object PrologGuard {
    private const val ENTITY_MARKER = "<!entity"
    private const val COMMENT_OPEN = "<!--"
    private const val COMMENT_CLOSE = "-->"
    private const val PI_OPEN = "<?"
    private const val PI_CLOSE = "?>"
    private const val DECL_OPEN = "<!"

    /** Whether the prolog of [bytes] declares an entity, which would enable entity expansion. */
    fun isHostile(
        bytes: ByteArray,
        scanLimit: Int,
    ): Boolean {
        // Decoding first makes the scan encoding-aware: a UTF-16 prolog cannot hide its DOCTYPE.
        // A declaration the parser's own setInput cannot decode fails closed.
        val text = EncodingSniff.decode(bytes.copyOf(minOf(bytes.size, scanLimit))) ?: return true
        return isHostileText(text)
    }

    /** The same scan over an already-decoded view — the charset-override pass' prolog window. */
    fun isHostileText(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> {
                    i++
                }

                text.startsWith(PI_OPEN, i) -> {
                    val close = text.indexOf(PI_CLOSE, i + PI_OPEN.length)
                    if (close < 0) return false
                    i = close + PI_CLOSE.length
                }

                text.startsWith(COMMENT_OPEN, i) -> {
                    val close = text.indexOf(COMMENT_CLOSE, i + COMMENT_OPEN.length)
                    if (close < 0) return false
                    i = close + COMMENT_CLOSE.length
                }

                text.startsWith(DECL_OPEN, i) -> {
                    val declEnd = markupDeclEnd(text, i + DECL_OPEN.length)
                    if (declEnd < 0) return true
                    i = declEnd
                }

                // The root element: relaxed kxml2 starts a tag on any char that is not `!`, `?` or
                // `/`, so the prolog ends there whatever the name looks like.
                text[i] == '<' && i + 1 < text.length &&
                    text[i + 1] != '!' && text[i + 1] != '?' && text[i + 1] != '/' -> {
                    return false
                }

                else -> {
                    i++
                }
            }
        }
        return false
    }

    /**
     * Scans one markup declaration starting after `<!` and returns the index just past its end, or a
     * negative value when an `<!ENTITY` declaration is found inside it. The boundary is kxml2's
     * `parseDoctype` verbatim: `'` toggles the quoted state, an unquoted `<` nests, and an unquoted
     * `>` at depth zero ends the declaration — a `"` region inside a DOCTYPE literal does not hide
     * what follows it from either scanner.
     */
    private fun markupDeclEnd(
        text: String,
        start: Int,
    ): Int {
        var i = start
        // The `<` that opened the declaration counts towards the nesting depth, like kxml2's `1`.
        var depth = 1
        var quoted = false
        while (i < text.length) {
            if (text.startsWith(ENTITY_MARKER, i, ignoreCase = true)) return -1
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
}
