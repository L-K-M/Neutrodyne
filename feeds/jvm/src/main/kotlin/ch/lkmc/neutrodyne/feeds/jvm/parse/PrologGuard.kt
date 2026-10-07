// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

/**
 * The prolog guard (03 Parser setup and charset step 1): the first 64 KiB are decoded with the
 * detected encoding, then walked through the real prolog constructs — whitespace, comments,
 * processing instructions and markup declarations — until the root element. `<!ENTITY` inside a
 * declaration means the document is hostile. `FEATURE_PROCESS_DOCDECL` stays off and no external
 * DTD is ever fetched (N9).
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
        val text = EncodingSniff.decode(bytes.copyOf(minOf(bytes.size, scanLimit)))
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

                text[i] == '<' && i + 1 < text.length && isNameStart(text[i + 1]) -> {
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
     * Scans one markup declaration starting after `<!` and returns the index just past its `>`, or
     * a negative value when an `<!ENTITY` declaration is found inside it. Quoted values (an entity
     * value may itself contain `<` or the marker as text) and the `[…]` internal subset are honoured.
     */
    private fun markupDeclEnd(
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
                c == '"' || c == '\'' -> quote = c
                text.startsWith(ENTITY_MARKER, i, ignoreCase = true) -> return -1
                c == '[' -> inSubset = true
                c == ']' && inSubset && nextNonWhitespaceIsClose(text, i + 1) -> return closeIndex(text, i + 1)
                c == '>' && !inSubset -> return i + 1
            }
            i++
        }
        return text.length
    }

    /** Whether the next non-whitespace char after [from] is `>` (the internal subset is done). */
    private fun nextNonWhitespaceIsClose(
        text: String,
        from: Int,
    ): Boolean = closeIndex(text, from) > 0

    /** The index just past the next `>` after whitespace, or -1 when something else comes first. */
    private fun closeIndex(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length && text[i] == '>') i + 1 else -1
    }

    private fun isNameStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == ':' || c.code > 127
}
