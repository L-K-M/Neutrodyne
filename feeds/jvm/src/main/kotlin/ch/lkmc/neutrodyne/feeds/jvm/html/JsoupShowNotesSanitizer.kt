// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.html

import ch.lkmc.neutrodyne.feeds.html.NoteBlock
import ch.lkmc.neutrodyne.feeds.html.NoteSpan
import ch.lkmc.neutrodyne.feeds.html.ShowNotesDocument
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.BOLD
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.CODE
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.ITALIC
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.NONE
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.UNDERLINE
import ch.lkmc.neutrodyne.feeds.html.TimestampLinkifier
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * The jsoup show-notes sanitiser (03 Sanitiser and block model) behind the common [ShowNotesSanitizer]
 * interface. `toDocument` runs at display time; `snippet` runs at ingest. The cleaned `Document` is
 * walked directly, without re-serialisation and re-parse.
 */
public class JsoupShowNotesSanitizer : ShowNotesSanitizer {
    override fun toDocument(
        raw: String,
        isHtml: Boolean,
        baseUri: String,
    ): ShowNotesDocument {
        if (!isHtml) return plainTextDocument(raw)

        // Step 1: no tags but escaped markup (double-escaped feeds) → unescape once. `unescape`
        // decodes entities without normalising whitespace, so `pre` line breaks survive; if the
        // description still has no tags it is plain text and takes the plain-text path.
        val html = if (raw.contains('<')) raw else Entities.unescape(raw)
        if (!html.contains('<')) return plainTextDocument(html)

        // Step 2: clean. jsoup's default preserveRelativeLinks(false) makes relative href/src absolute
        // against baseUri; unresolvable ones are dropped.
        val safelist =
            Safelist
                .basicWithImages()
                .addTags("h1", "h2", "h3", "h4", "h5", "h6", "div", "hr", "figure", "figcaption")
                .addAttributes("img", "alt", "width", "height", "src")
                .removeProtocols("a", "href", "ftp")
                .removeEnforcedAttribute("a", "rel")
        val cleaned = Cleaner(safelist).clean(Jsoup.parseBodyFragment(html, baseUri))

        // Step 3: drop tracking images, 1×1 spacers and empty paragraphs.
        for (img in cleaned.select("img").toList()) {
            val width = img.attr("width").trim().toIntOrNull()
            val height = img.attr("height").trim().toIntOrNull()
            val tracks = isTrackingImage(img.attr("src"))
            if (tracks || (width != null && width <= 2) || (height != null && height <= 2)) img.remove()
        }
        for (paragraph in cleaned.select("p").toList()) {
            if (paragraph.text().isBlank() && paragraph.select("img").isEmpty()) paragraph.remove()
        }

        // Step 4: walk the cleaned DOM into blocks, with the caps of the mapping table.
        return Walker().document(cleaned.body())
    }

    override fun snippet(
        text: String,
        isHtml: Boolean,
    ): String {
        val plain = if (isHtml) Jsoup.parse(text).text() else text
        val collapsed = plain.replace(WHITESPACE, " ").trim()
        if (collapsed.length <= SNIPPET_MAX_CHARS) return collapsed

        // Cut at the last word boundary ≤ 199 chars, plus the ellipsis (≤ 200 in total, 02). A space
        // right after the cut means the prefix already ends on a word boundary, so keep it whole.
        val cut = collapsed.substring(0, SNIPPET_MAX_CHARS)
        val lastSpace =
            if (collapsed[SNIPPET_MAX_CHARS] == ' ') {
                SNIPPET_MAX_CHARS
            } else {
                cut.lastIndexOf(' ').takeIf { it > 0 } ?: SNIPPET_MAX_CHARS
            }
        return cut.substring(0, lastSpace).trimEnd() + "…"
    }

    /**
     * 03 step 3's tracking-pixel pattern `(?i)(/pixel|/track|/beacon|1x1|spacer)[^/]*\.(gif|png)` as a
     * linear scan: a marker, then no `/` until a `.gif` or `.png`. The regex is quadratic on
     * adversarial URLs because every marker restarts a suffix scan that backtracks.
     */
    private fun isTrackingImage(src: String): Boolean {
        var markerFound = false
        for (i in src.indices) {
            if (src[i] == '/') {
                markerFound = false // markers never span a path separator
                continue
            }
            if (markerEndsAt(src, i)) markerFound = true
            if (markerFound && src[i] == '.' && extensionAt(src, i)) return true
        }
        return false
    }

    /** True when a tracking marker occupies the characters ending just before index [end]. */
    private fun markerEndsAt(
        src: String,
        end: Int,
    ): Boolean =
        src.regionMatches(end - 3, "1x1", 0, 3, ignoreCase = true) ||
            src.regionMatches(end - 6, "spacer", 0, 6, ignoreCase = true) ||
            src.regionMatches(end - 6, "/pixel", 0, 6, ignoreCase = true) ||
            src.regionMatches(end - 6, "/track", 0, 6, ignoreCase = true) ||
            src.regionMatches(end - 7, "/beacon", 0, 7, ignoreCase = true)

    /** True when `gif` or `png` follows the `.` at index [dot]. */
    private fun extensionAt(
        src: String,
        dot: Int,
    ): Boolean =
        src.regionMatches(dot + 1, "gif", 0, 3, ignoreCase = true) ||
            src.regionMatches(dot + 1, "png", 0, 3, ignoreCase = true)

    // -------------------------------------------------------------------------
    // Plain text (03 step 1): blank lines split paragraphs, newlines break, URLs linkified,
    // timestamps linkified outside URLs
    // -------------------------------------------------------------------------

    private fun plainTextDocument(raw: String): ShowNotesDocument {
        val blocks = mutableListOf<NoteBlock>()
        var blocksLeft = MAX_BLOCKS
        for (paragraph in raw.split(Regex("\n\\s*\n"))) {
            if (blocksLeft == 0) break
            val spans = mutableListOf<NoteSpan>()
            for ((i, line) in paragraph.split('\n').withIndex()) {
                if (i > 0 && spans.isNotEmpty()) spans.add(NoteSpan.LineBreak)
                spans.addAll(spansOfPlainLine(line.trimEnd()))
            }
            val merged = mergeTextSpans(spans)
            if (merged.isNotEmpty()) {
                blocks.add(NoteBlock.Paragraph(merged))
                blocksLeft--
            }
        }
        return ShowNotesDocument(blocks)
    }

    private fun spansOfPlainLine(line: String): List<NoteSpan> {
        if (line.isBlank()) return emptyList()
        val spans = mutableListOf<NoteSpan>()
        var cursor = 0
        for (match in URL_TOKEN.findAll(line)) {
            if (match.range.first > cursor) {
                spans.addAll(TimestampLinkifier.linkify(line.substring(cursor, match.range.first)))
            }
            spans.add(NoteSpan.Link(match.value, match.value))
            cursor = match.range.last + 1
        }
        if (cursor < line.length) spans.addAll(TimestampLinkifier.linkify(line.substring(cursor)))
        return spans
    }

    // -------------------------------------------------------------------------
    // Step 4: the walk (03 mapping of every tag the safelist lets through)
    // -------------------------------------------------------------------------

    /**
     * The state of one `toDocument` walk. Every [NoteBlock] — top-level, inside a quote or inside a
     * list item — spends from the same 2,000-block budget before it is allocated, and images spend
     * from the 50-image cap. Each container runs an inline accumulator whose spans flush into a
     * block at every block-level boundary, so a `div` or `li` of text and links is one paragraph,
     * not one per node, and an `img` wrapped in inline elements still becomes an Image block.
     */
    private inner class Walker {
        private var blocksLeft = MAX_BLOCKS
        private var images = 0

        private fun takeBlock(): Boolean {
            if (blocksLeft == 0) return false
            blocksLeft--
            return true
        }

        fun document(body: Element): ShowNotesDocument {
            val blocks = mutableListOf<NoteBlock>()
            val sink = Sink(blocks, ::paragraph, listDepth = 0)
            for (child in body.childNodes()) appendNode(child, sink)
            sink.flush()
            return ShowNotesDocument(blocks)
        }

        /**
         * One container's inline run: spans accumulate in [spans] and [flush] merges, linkifies and
         * wraps them into a block on [target]. A block-level child flushes the run first.
         */
        private inner class Sink(
            val target: MutableList<NoteBlock>,
            val wrap: (List<NoteSpan>) -> NoteBlock,
            val listDepth: Int,
        ) {
            val spans = mutableListOf<NoteSpan>()

            fun flush() {
                if (spans.isEmpty()) return
                val run = trimEdges(linkifyTimestamps(mergeTextSpans(spans)))
                spans.clear()
                if (run.isNotEmpty() && takeBlock()) target.add(wrap(run))
            }

            fun addBlock(block: NoteBlock) {
                if (takeBlock()) target.add(block)
            }
        }

        /** A container's children walk as one run on [target], flushed into [wrap]-shaped blocks. */
        private fun runContainer(
            element: Element,
            target: MutableList<NoteBlock>,
            wrap: (List<NoteSpan>) -> NoteBlock,
            listDepth: Int,
        ) {
            val sink = Sink(target, wrap, listDepth)
            for (child in element.childNodes()) appendNode(child, sink)
            sink.flush()
        }

        /** Appends [node] in run context [sink]; block-level elements flush it and emit block(s). */
        private fun appendNode(
            node: Node,
            sink: Sink,
        ) {
            if (node is TextNode) {
                appendText(sink.spans, node.wholeText, NONE)
                return
            }
            if (node !is Element) return
            if (node.tagName() !in BLOCK_TAGS) {
                appendInline(node, NONE, sink.spans, sink)
                return
            }

            when (node.tagName()) {
                "p" -> {
                    sink.flush()
                    runContainer(node, sink.target, ::paragraph, sink.listDepth)
                }

                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    sink.flush()
                    val level = node.tagName().substring(1).toInt()
                    runContainer(node, sink.target, { NoteBlock.Heading(level, it) }, sink.listDepth)
                }

                "ul", "ol" -> {
                    sink.flush()
                    walkList(node, sink.target, sink.listDepth)
                }

                "blockquote" -> {
                    sink.flush()
                    // Reserve the Quote before its children spend the rest of the block budget.
                    if (takeBlock()) {
                        val inner = mutableListOf<NoteBlock>()
                        runContainer(node, inner, ::paragraph, sink.listDepth)
                        sink.target.add(NoteBlock.Quote(inner))
                    }
                }

                "img" -> {
                    sink.flush()
                    addImage(node, sink.target)
                }

                "hr" -> {
                    sink.flush()
                    sink.addBlock(NoteBlock.Rule)
                }

                "pre" -> {
                    sink.flush()
                    preBlock(node, sink.target)
                }

                "dl" -> {
                    sink.flush()
                    for (child in node.children()) {
                        when (child.tagName()) {
                            "dt" -> runContainer(child, sink.target, ::definitionTerm, sink.listDepth)
                            "dd" -> runContainer(child, sink.target, ::paragraph, sink.listDepth)
                        }
                    }
                }

                "figcaption" -> {
                    sink.flush()
                    runContainer(node, sink.target, ::figcaptionCaption, sink.listDepth)
                }

                else -> {
                    // div/figure contents flatten; any stray block container the same.
                    sink.flush()
                    runContainer(node, sink.target, ::paragraph, sink.listDepth)
                }
            }
        }

        /**
         * Appends one inline node to [out]. Styles stack onto children; a link's text collects in a
         * scratch list so it is never linkified; a nested `img` or a stray block-level element
         * flushes [sink] and emits on its target.
         */
        private fun appendInline(
            node: Node,
            style: Int,
            out: MutableList<NoteSpan>,
            sink: Sink,
        ) {
            if (node is TextNode) {
                appendText(out, node.wholeText, style)
                return
            }
            if (node !is Element) return

            when (node.tagName()) {
                "b", "strong" -> {
                    childrenOf(node, style or BOLD, out, sink)
                }

                "i", "em", "cite" -> {
                    childrenOf(node, style or ITALIC, out, sink)
                }

                "u" -> {
                    childrenOf(node, style or UNDERLINE, out, sink)
                }

                "code" -> {
                    childrenOf(node, style or CODE, out, sink)
                }

                "br" -> {
                    out.add(NoteSpan.LineBreak)
                }

                "a" -> {
                    val href = node.attr("href")
                    if (href.isEmpty()) {
                        // An `a` without a surviving href becomes plain text.
                        childrenOf(node, style, out, sink)
                    } else {
                        val inner = mutableListOf<NoteSpan>()
                        childrenOf(node, style, inner, sink)
                        var text = textOf(inner)
                        if (endsInSpace(out)) text = text.trimStart()
                        out.add(NoteSpan.Link(text, href, style or styleOf(inner)))
                    }
                }

                "img" -> {
                    sink.flush()
                    addImage(node, sink.target)
                }

                else -> {
                    if (node.tagName() in BLOCK_TAGS) {
                        sink.flush()
                        appendNode(node, sink)
                    } else {
                        // span, q, small, strike, sub, sup contribute their text only.
                        childrenOf(node, style, out, sink)
                    }
                }
            }
        }

        private fun childrenOf(
            element: Element,
            style: Int,
            out: MutableList<NoteSpan>,
            sink: Sink,
        ) {
            for (child in element.childNodes()) appendInline(child, style, out, sink)
        }

        /** `ul`/`ol`: `li` items are lists of blocks; nesting deeper than 4 flattens into the parent. */
        private fun walkList(
            node: Element,
            target: MutableList<NoteBlock>,
            listDepth: Int,
        ) {
            val items = node.children().filter { it.tagName() == "li" }
            if (listDepth >= MAX_LIST_NESTING) {
                for (li in items) runContainer(li, target, ::paragraph, listDepth)
                return
            }

            // Reserve the ListBlock before its items spend the rest of the block budget.
            if (items.isEmpty() || !takeBlock()) return
            val lists = mutableListOf<List<NoteBlock>>()
            for (li in items) {
                val itemBlocks = mutableListOf<NoteBlock>()
                runContainer(li, itemBlocks, ::paragraph, listDepth + 1)
                if (itemBlocks.isEmpty()) {
                    // The budget is spent: the item is dropped rather than left bullet-shaped empty.
                    if (!takeBlock()) continue
                    itemBlocks.add(NoteBlock.Paragraph(emptyList()))
                }
                lists.add(itemBlocks)
            }
            if (lists.isNotEmpty()) target.add(NoteBlock.ListBlock(node.tagName() == "ol", lists))
        }

        /**
         * `pre` → one Paragraph keeping its line breaks (`LineBreak` spans) in CODE; `br` and links
         * survive, an `img` becomes an Image block after it; whitespace is not collapsed.
         */
        private fun preBlock(
            node: Element,
            target: MutableList<NoteBlock>,
        ) {
            val spans = mutableListOf<NoteSpan>()
            val wrappedImages = mutableListOf<Element>()
            appendPreformatted(node, spans, wrappedImages)
            if (spans.isNotEmpty() && takeBlock()) target.add(NoteBlock.Paragraph(mergeTextSpans(spans)))
            for (img in wrappedImages) addImage(img, target)
        }

        private fun appendPreformatted(
            node: Node,
            spans: MutableList<NoteSpan>,
            wrappedImages: MutableList<Element>,
        ) {
            when (node) {
                is TextNode -> {
                    for ((i, line) in node.wholeText.split('\n').withIndex()) {
                        if (i > 0) spans.add(NoteSpan.LineBreak)
                        if (line.isNotEmpty()) spans.add(NoteSpan.Text(line, CODE))
                    }
                }

                is Element -> {
                    when (node.tagName()) {
                        "br" -> {
                            spans.add(NoteSpan.LineBreak)
                        }

                        "img" -> {
                            wrappedImages.add(node)
                        }

                        "a" -> {
                            val href = node.attr("href")
                            if (href.isNotEmpty()) {
                                spans.add(NoteSpan.Link(node.wholeText(), href, CODE))
                            } else {
                                for (child in node.childNodes()) {
                                    appendPreformatted(child, spans, wrappedImages)
                                }
                            }
                        }

                        else -> {
                            for (child in node.childNodes()) appendPreformatted(child, spans, wrappedImages)
                        }
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        private fun addImage(
            img: Element,
            target: MutableList<NoteBlock>,
        ) {
            if (images >= MAX_IMAGES) return
            val url = img.attr("src")
            // A source the cleaner rejected (data: URLs, unresolvable) leaves no Image block.
            if (url.isBlank() || !takeBlock()) return
            images++
            target.add(
                NoteBlock.Image(
                    url = url,
                    alt = img.attr("alt").takeIf { it.isNotEmpty() },
                    width =
                        img
                            .attr("width")
                            .trim()
                            .toIntOrNull()
                            ?.takeIf { it > 0 },
                    height =
                        img
                            .attr("height")
                            .trim()
                            .toIntOrNull()
                            ?.takeIf { it > 0 },
                ),
            )
        }

        /**
         * Collapses [text]'s whitespace to single spaces and appends it; a space where [out] already
         * ends in whitespace is dropped, so `a <b> b </b> c` collapses to "a b c".
         */
        private fun appendText(
            out: MutableList<NoteSpan>,
            text: String,
            style: Int,
        ) {
            var collapsed = collapseWhitespace(text)
            if (endsInSpace(out)) collapsed = collapsed.removePrefix(" ")
            if (collapsed.isEmpty()) return
            out.add(NoteSpan.Text(collapsed, style))
        }
    }

    private fun paragraph(spans: List<NoteSpan>): NoteBlock = NoteBlock.Paragraph(spans)

    private fun definitionTerm(spans: List<NoteSpan>): NoteBlock = NoteBlock.Paragraph(withStyle(spans, BOLD))

    private fun figcaptionCaption(spans: List<NoteSpan>): NoteBlock = NoteBlock.Paragraph(withStyle(spans, ITALIC))

    /**
     * Timestamps linkify over each complete text run (03 Timestamp linkifier): every maximal run of
     * Text spans — broken by links and line breaks — is matched as one string, so a timestamp is
     * never cut at an inline boundary ("10:30<b>h</b>" is a time of day, not a timestamp) and each
     * re-emitted Text keeps its piece's style.
     */
    private fun linkifyTimestamps(spans: List<NoteSpan>): List<NoteSpan> {
        val out = mutableListOf<NoteSpan>()
        var segment = mutableListOf<NoteSpan.Text>()

        fun emitSegment() {
            if (segment.isEmpty()) return
            out.addAll(linkifyStyled(segment))
            segment = mutableListOf()
        }

        for (span in spans) {
            if (span is NoteSpan.Text) {
                segment.add(span)
            } else {
                emitSegment()
                out.add(span)
            }
        }
        emitSegment()
        return out
    }

    /** Linkifies the concatenated text of one styled segment, keeping each piece's style. */
    private fun linkifyStyled(segment: List<NoteSpan.Text>): List<NoteSpan> {
        if (segment.size == 1) return TimestampLinkifier.linkify(segment[0].text, segment[0].style)

        val whole = buildString { for (piece in segment) append(piece.text) }
        val out = mutableListOf<NoteSpan>()
        var pieceIndex = 0
        var pieceOffset = 0
        var position = 0
        for (span in TimestampLinkifier.linkify(whole)) {
            if (span is NoteSpan.Timestamp) {
                out.add(span)
                position += span.text.length
                continue
            }
            if (span !is NoteSpan.Text) continue

            // Re-split the unstyled text at the segment's style boundaries.
            val end = position + span.text.length
            while (position < end) {
                val piece = segment[pieceIndex]
                val take = minOf(piece.text.length - pieceOffset, end - position)
                out.add(NoteSpan.Text(whole.substring(position, position + take), piece.style))
                position += take
                pieceOffset += take
                if (pieceOffset == piece.text.length) {
                    pieceIndex++
                    pieceOffset = 0
                }
            }
        }
        return out
    }

    private fun withStyle(
        spans: List<NoteSpan>,
        style: Int,
    ): List<NoteSpan> =
        spans.map { span ->
            when (span) {
                is NoteSpan.Text -> span.copy(style = span.style or style)
                is NoteSpan.Link -> span.copy(style = span.style or style)
                is NoteSpan.Timestamp, is NoteSpan.LineBreak -> span
            }
        }

    private fun styleOf(spans: List<NoteSpan>): Int =
        spans.fold(0) { acc, span ->
            when (span) {
                is NoteSpan.Text -> acc or span.style
                else -> acc
            }
        }

    private fun textOf(spans: List<NoteSpan>): String =
        spans.joinToString("") { span ->
            when (span) {
                is NoteSpan.Text -> span.text
                is NoteSpan.Link -> span.text
                is NoteSpan.Timestamp -> span.text
                is NoteSpan.LineBreak -> ""
            }
        }

    /** Whether [spans] currently ends in whitespace; an empty run counts (its lead is trimmed). */
    private fun endsInSpace(spans: List<NoteSpan>): Boolean =
        when (val last = spans.lastOrNull()) {
            null, is NoteSpan.LineBreak -> true
            is NoteSpan.Text -> last.text.endsWith(' ')
            is NoteSpan.Link -> last.text.endsWith(' ')
            is NoteSpan.Timestamp -> last.text.endsWith(' ')
        }

    /** Adjacent Text spans with equal style merge (03 mapping rules); runs append, never re-copy. */
    private fun mergeTextSpans(spans: List<NoteSpan>): List<NoteSpan> {
        val merged = mutableListOf<NoteSpan>()
        var text: StringBuilder? = null
        var textStyle = NONE

        fun emitText() {
            val builder = text ?: return
            merged.add(NoteSpan.Text(builder.toString(), textStyle))
            text = null
        }

        for (span in spans) {
            if (span is NoteSpan.Text) {
                val builder = text
                if (builder != null && textStyle == span.style) {
                    builder.append(span.text)
                } else {
                    emitText()
                    text = StringBuilder(span.text)
                    textStyle = span.style
                }
            } else {
                emitText()
                merged.add(span)
            }
        }
        emitText()
        return merged
    }

    /** Collapses whitespace runs to single spaces; edge trimming happens once per block in [trimEdges]. */
    private fun collapseWhitespace(text: String): String = text.replace(WHITESPACE, " ")

    /** Trims leading/trailing spaces of a block's first/last Text span (whitespace collapses "as in HTML"). */
    private fun trimEdges(spans: List<NoteSpan>): List<NoteSpan> {
        if (spans.isEmpty()) return spans
        val result = spans.toMutableList()
        val first = result.first()
        if (first is NoteSpan.Text) {
            val trimmed = first.text.trimStart()
            if (trimmed.isEmpty()) result.removeAt(0) else result[0] = first.copy(text = trimmed)
        }
        if (result.isEmpty()) return result
        val last = result.last()
        if (last is NoteSpan.Text) {
            val trimmed = last.text.trimEnd()
            if (trimmed.isEmpty()) {
                result.removeAt(result.size - 1)
            } else {
                result[result.size - 1] = last.copy(text = trimmed)
            }
        }
        return result
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val URL_TOKEN = Regex("""https?://[^\s<>"']+[^\s<>"'.,;:!?)]""")

        /** Every block-level tag the safelist can emit; anything else is inline content. */
        val BLOCK_TAGS =
            setOf(
                "p",
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6",
                "ul",
                "ol",
                "li",
                "blockquote",
                "img",
                "hr",
                "pre",
                "dl",
                "dt",
                "dd",
                "div",
                "figure",
                "figcaption",
            )
        const val SNIPPET_MAX_CHARS = 199
        const val MAX_BLOCKS = 2_000
        const val MAX_LIST_NESTING = 4
        const val MAX_IMAGES = 50
    }
}
