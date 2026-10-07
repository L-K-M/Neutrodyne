// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import org.xmlpull.v1.XmlPullParser

/**
 * Re-serialises the child markup of an HTML-bearing element (03 Parser setup and charset step 6):
 * `description`, `content:encoded`, `itunes:summary` and Atom `content`/`summary` often contain
 * unescaped markup that arrives as real child elements; those are rebuilt as `<tag attr="…">…</tag>`
 * text, never dropped. CDATA sections carry literal HTML payloads and pass through unescaped.
 */
internal object InnerXml {
    /**
     * kxml2's relaxed mode prefixes the text inside an element whose prefix was never declared with
     * `"ERR: undefined prefix: " + prefix`; only the registry's fallback prefixes can be affected, so
     * exactly those markers are stripped.
     */
    private val relaxedErrorPrefix =
        Regex("""^ERR: undefined prefix: (?:itunes|podcast|media|content|atom|psc)""")

    internal fun stripRelaxedError(text: String): String =
        if (text.startsWith(RELAXED_ERROR_MARK)) relaxedErrorPrefix.replace(text, "") else text

    private const val RELAXED_ERROR_MARK = "ERR: undefined prefix: "
    private const val NO_DEPTH_LIMIT = Int.MAX_VALUE

    /** The bounded text of a collected element and the length it would have had unbounded. */
    internal class Collected(
        val text: String,
        val totalChars: Int,
    )

    /** Accumulates up to [maxChars] while still counting every character seen. */
    private class Sink(
        private val maxChars: Int,
    ) {
        val out = StringBuilder()
        var total = 0
            private set

        fun append(text: CharSequence): Sink {
            total += text.length
            val room = maxChars - out.length
            if (room > 0) out.append(text, 0, minOf(text.length, room))
            return this
        }

        fun append(c: Char): Sink {
            total++
            if (out.length < maxChars) out.append(c)
            return this
        }
    }

    /**
     * Collects everything up to (not including) the END_TAG of the element the parser sits on, leaving
     * the parser on that END_TAG. [onText] observes every text run (for the U+FFFD heuristic),
     * [maxDepth] is the caller's element-depth limit, and output stops growing at [maxChars] while
     * structural checks continue (the limit is a bound, not a termination point). Walks with
     * `nextToken`, not `next`: only tokens distinguish CDATA from ordinary text.
     */
    fun collect(
        parser: XmlPullParser,
        onText: (String) -> Unit = {},
        maxDepth: Int = NO_DEPTH_LIMIT,
        maxChars: Int = Int.MAX_VALUE,
    ): Collected {
        val sink = Sink(maxChars)
        // The element's own depth: its END_TAG arrives at the same depth; a child's arrives deeper.
        val containerDepth = parser.depth
        parser.nextToken()
        collectInto(parser, containerDepth, sink, onText, maxDepth)
        return Collected(sink.out.toString(), sink.total)
    }

    /** Collects until the END_TAG at [containerDepth]; returns with the parser on that END_TAG. */
    private fun collectInto(
        parser: XmlPullParser,
        containerDepth: Int,
        sink: Sink,
        onText: (String) -> Unit,
        maxDepth: Int,
    ) {
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.depth > maxDepth) throw DepthLimitExceeded(maxDepth)
                    sink.append('<').append(rawName(parser))
                    for (i in 0 until parser.attributeCount) {
                        sink
                            .append(' ')
                            .append(rawAttributeName(parser, i))
                            .append("=\"")
                            .append(escapeAttribute(parser.getAttributeValue(i)))
                            .append('"')
                    }
                    sink.append('>')
                    if (!parser.isEmptyElementTag) {
                        val childDepth = parser.depth
                        parser.nextToken()
                        collectInto(parser, childDepth, sink, onText, maxDepth)
                        sink.append("</").append(rawName(parser)).append('>')
                    }
                    parser.nextToken()
                }

                XmlPullParser.TEXT -> {
                    val text = stripRelaxedError(parser.text)
                    onText(text)
                    sink.append(escapeText(text))
                    parser.nextToken()
                }

                XmlPullParser.CDSECT -> {
                    // CDATA carries the literal HTML payload; it must not be re-escaped.
                    val text = parser.text
                    onText(text)
                    sink.append(stripRelaxedError(text))
                    parser.nextToken()
                }

                XmlPullParser.ENTITY_REF -> {
                    // Relaxed mode: an unknown entity stays as literal "&name;" text.
                    val text = parser.text?.let(::stripRelaxedError)
                    if (text != null) {
                        onText(text)
                        sink.append(escapeText(text))
                    } else {
                        sink.append('&').append(parser.name).append(';')
                    }
                    parser.nextToken()
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth <= containerDepth) {
                        return
                    } else {
                        parser.nextToken()
                    }
                }

                else -> {
                    parser.nextToken()
                }
            }
        }
    }

    /** The tag name as written, prefix included (namespace-aware parsing reports only the local name). */
    private fun rawName(parser: XmlPullParser): String {
        val prefix = parser.prefix
        return if (prefix.isNullOrEmpty()) parser.name else "$prefix:${parser.name}"
    }

    private fun rawAttributeName(
        parser: XmlPullParser,
        index: Int,
    ): String {
        val prefix = parser.getAttributePrefix(index)
        val name = parser.getAttributeName(index)
        return if (prefix.isNullOrEmpty()) name else "$prefix:$name"
    }

    private fun escapeText(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttribute(value: String?): String = escapeText(value.orEmpty()).replace("\"", "&quot;")
}
