// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import org.xmlpull.v1.XmlPullParser

/**
 * Re-serialises the child markup of an HTML-bearing element (03 Parser setup and charset step 6):
 * `description`, `content:encoded`, `itunes:summary` and Atom `content`/`summary` often contain
 * unescaped markup that arrives as real child elements; those are rebuilt as `<tag attr="…">…</tag>`
 * text, never dropped.
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

    /**
     * Collects everything up to (not including) the END_TAG of the element the parser sits on, leaving
     * the parser on that END_TAG. [onText] observes every text run (for the U+FFFD heuristic) and
     * [maxDepth] is the caller's element-depth limit, guarded on every child start tag.
     */
    fun collect(
        parser: XmlPullParser,
        onText: (String) -> Unit = {},
        maxDepth: Int = NO_DEPTH_LIMIT,
    ): String {
        val out = StringBuilder()
        // Enter the element: the caller leaves the parser on its START_TAG.
        parser.next()
        collectInto(parser, parser.depth, out, onText, maxDepth)
        return out.toString()
    }

    /** Collects until the END_TAG at [containerDepth]; returns with the parser on that END_TAG. */
    private fun collectInto(
        parser: XmlPullParser,
        containerDepth: Int,
        out: StringBuilder,
        onText: (String) -> Unit,
        maxDepth: Int,
    ) {
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.depth > maxDepth) throw DepthLimitExceeded(maxDepth)
                    out.append('<').append(rawName(parser))
                    for (i in 0 until parser.attributeCount) {
                        out
                            .append(' ')
                            .append(rawAttributeName(parser, i))
                            .append("=\"")
                            .append(escapeAttribute(parser.getAttributeValue(i)))
                            .append('"')
                    }
                    out.append('>')
                    if (!parser.isEmptyElementTag) {
                        parser.next()
                        collectInto(parser, parser.depth, out, onText, maxDepth)
                        out.append("</").append(rawName(parser)).append('>')
                    }
                    parser.next()
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val text = stripRelaxedError(parser.text)
                    onText(text)
                    out.append(escapeText(text))
                    parser.next()
                }

                XmlPullParser.ENTITY_REF -> {
                    // Relaxed mode: an unknown entity stays as literal "&name;" text.
                    val text = parser.text?.let(::stripRelaxedError)
                    if (text != null) {
                        onText(text)
                        out.append(escapeText(text))
                    } else {
                        out.append('&').append(parser.name).append(';')
                    }
                    parser.next()
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth <= containerDepth) {
                        return
                    } else {
                        parser.next()
                    }
                }

                else -> {
                    parser.next()
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
