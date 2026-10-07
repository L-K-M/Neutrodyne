// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.html

import ch.lkmc.neutrodyne.feeds.html.NoteBlock
import ch.lkmc.neutrodyne.feeds.html.NoteSpan
import ch.lkmc.neutrodyne.feeds.html.ShowNotesDocument
import ch.lkmc.neutrodyne.feeds.html.ShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.BOLD
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.CODE
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.ITALIC
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.UNDERLINE
import ch.lkmc.neutrodyne.feeds.html.TimestampLinkifier
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.util.regex.Pattern

/**
 * The jsoup show-notes sanitiser (03 Sanitiser and block model) behind the common [ShowNotesSanitizer]
 * interface. `toDocument` runs at display time; `snippet` runs at ingest. The cleaned `Document` is
 * walked directly, without re-serialisation and re-parse.
 */
public class JsoupShowNotesSanitizer : ShowNotesSanitizer {
    /** 03 step 3: tracking pixels and 1×1 spacers. */
    private val trackingImage = Pattern.compile("""(?i)(/pixel|/track|/beacon|1x1|spacer)[^/]*\.(gif|png)""")

    override fun toDocument(
        raw: String,
        isHtml: Boolean,
        baseUri: String,
    ): ShowNotesDocument {
        if (!isHtml) return plainTextDocument(raw)

        // Step 1: no tags but escaped markup (double-escaped feeds) → unescape once (text() decodes).
        val html =
            if (raw.contains('<')) {
                raw
            } else {
                Jsoup.parse(raw).text().takeIf { it.contains('<') } ?: raw
            }

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
            val tracks = trackingImage.matcher(img.attr("src")).find()
            if (tracks || (width != null && width <= 2) || (height != null && height <= 2)) img.remove()
        }
        for (paragraph in cleaned.select("p").toList()) {
            if (paragraph.text().isBlank() && paragraph.select("img").isEmpty()) paragraph.remove()
        }

        // Step 4: walk the cleaned DOM into blocks, with the caps of the mapping table.
        val blocks = mutableListOf<NoteBlock>()
        var images = 0
        for (child in cleaned.body().childNodes()) {
            images = walkNode(child, blocks, 0, images)
        }
        if (blocks.size > MAX_BLOCKS) blocks.subList(MAX_BLOCKS, blocks.size).clear()
        return ShowNotesDocument(blocks)
    }

    override fun snippet(
        text: String,
        isHtml: Boolean,
    ): String {
        val plain = if (isHtml) Jsoup.parse(text).text() else text
        val collapsed = plain.replace(WHITESPACE, " ").trim()
        if (collapsed.length <= SNIPPET_MAX_CHARS) return collapsed

        // Cut at the last word boundary ≤ 199 chars, plus the ellipsis (≤ 200 in total, 02).
        val cut = collapsed.substring(0, SNIPPET_MAX_CHARS)
        val lastSpace = cut.lastIndexOf(' ').takeIf { it > 0 } ?: SNIPPET_MAX_CHARS
        return cut.substring(0, lastSpace).trimEnd() + "…"
    }

    // -------------------------------------------------------------------------
    // Plain text (03 step 1): blank lines split paragraphs, newlines break, URLs linkified,
    // timestamps linkified outside URLs
    // -------------------------------------------------------------------------

    private fun plainTextDocument(raw: String): ShowNotesDocument {
        val blocks = mutableListOf<NoteBlock>()
        for (paragraph in raw.split(Regex("\n\\s*\n"))) {
            val spans = mutableListOf<NoteSpan>()
            for ((i, line) in paragraph.split('\n').withIndex()) {
                if (i > 0 && spans.isNotEmpty()) spans.add(NoteSpan.LineBreak)
                spans.addAll(spansOfPlainLine(line.trimEnd()))
            }
            val merged = mergeTextSpans(spans)
            if (merged.isNotEmpty()) blocks.add(NoteBlock.Paragraph(merged))
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

    /** Walks one DOM node into [blocks]; returns the updated image count (cap: 50 images). */
    private fun walkNode(
        node: Node,
        blocks: MutableList<NoteBlock>,
        listDepth: Int,
        images: Int,
    ): Int {
        var imageCount = images
        if (node is TextNode) {
            val text = collapseWhitespace(node.text())
            if (text.isNotBlank()) blocks.add(NoteBlock.Paragraph(TimestampLinkifier.linkify(text.trim())))
            return imageCount
        }
        if (node !is Element) return imageCount

        when (node.tagName()) {
            "p" -> {
                imageCount = walkInlineContainer(node, blocks, imageCount) { NoteBlock.Paragraph(it) }
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = node.tagName().substring(1).toInt()
                imageCount = walkInlineContainer(node, blocks, imageCount) { NoteBlock.Heading(level, it) }
            }

            "ul", "ol" -> {
                imageCount = walkList(node, blocks, listDepth, imageCount)
            }

            "blockquote" -> {
                val inner = mutableListOf<NoteBlock>()
                for (child in node.childNodes()) {
                    imageCount = walkNode(child, inner, listDepth, imageCount)
                }
                blocks.add(NoteBlock.Quote(inner))
            }

            "img" -> {
                imageCount = addImage(node, blocks, imageCount)
            }

            "hr" -> {
                blocks.add(NoteBlock.Rule)
            }

            "pre" -> {
                val spans = mutableListOf<NoteSpan>()
                appendPreformatted(node, spans)
                blocks.add(NoteBlock.Paragraph(spans))
            }

            "dl" -> {
                for (child in node.children()) {
                    when (child.tagName()) {
                        "dt" -> {
                            imageCount =
                                walkInlineContainer(child, blocks, imageCount, ::definitionTerm)
                        }

                        "dd" -> {
                            imageCount =
                                walkInlineContainer(child, blocks, imageCount) { NoteBlock.Paragraph(it) }
                        }
                    }
                }
            }

            "figcaption" -> {
                imageCount =
                    walkInlineContainer(node, blocks, imageCount, ::figcaptionCaption)
            }

            else -> {
                // div/figure contents flatten; any other container the same.
                for (child in node.childNodes()) {
                    imageCount = walkNode(child, blocks, listDepth, imageCount)
                }
            }
        }
        return imageCount
    }

    /** `ul`/`ol`: `li` items are lists of blocks; nesting deeper than 4 flattens into the parent. */
    private fun walkList(
        node: Element,
        blocks: MutableList<NoteBlock>,
        listDepth: Int,
        images: Int,
    ): Int {
        var imageCount = images
        val items = mutableListOf<List<NoteBlock>>()
        for (li in node.children().filter { it.tagName() == "li" }) {
            val itemBlocks = mutableListOf<NoteBlock>()
            if (listDepth >= MAX_LIST_NESTING) {
                for (child in li.childNodes()) {
                    imageCount = walkNode(child, itemBlocks, listDepth, imageCount)
                }
                blocks.addAll(itemBlocks)
            } else {
                for (child in li.childNodes()) {
                    imageCount = walkNode(child, itemBlocks, listDepth + 1, imageCount)
                }
                if (itemBlocks.isEmpty()) itemBlocks.add(NoteBlock.Paragraph(emptyList()))
                items.add(itemBlocks)
            }
        }
        if (items.isNotEmpty()) blocks.add(NoteBlock.ListBlock(node.tagName() == "ol", items))
        return imageCount
    }

    /**
     * One paragraph-shaped element: inline content accumulates into [wrap], an `img` inside becomes its
     * own Image block.
     */
    private fun walkInlineContainer(
        element: Element,
        blocks: MutableList<NoteBlock>,
        images: Int,
        wrap: (List<NoteSpan>) -> NoteBlock,
    ): Int {
        var imageCount = images
        val buffer = mutableListOf<NoteSpan>()
        for (child in element.childNodes()) {
            if (child is Element && child.tagName() == "img") {
                flush(buffer, blocks, wrap)
                imageCount = addImage(child, blocks, imageCount)
            } else {
                buffer.addAll(inlineSpansOf(child))
            }
        }
        flush(buffer, blocks, wrap)
        return imageCount
    }

    private fun flush(
        buffer: MutableList<NoteSpan>,
        blocks: MutableList<NoteBlock>,
        wrap: (List<NoteSpan>) -> NoteBlock,
    ) {
        if (buffer.isEmpty()) return
        blocks.add(wrap(trimEdges(mergeTextSpans(buffer))))
        buffer.clear()
    }

    private fun definitionTerm(spans: List<NoteSpan>): NoteBlock = NoteBlock.Paragraph(withStyle(spans, BOLD))

    private fun figcaptionCaption(spans: List<NoteSpan>): NoteBlock = NoteBlock.Paragraph(withStyle(spans, ITALIC))

    private fun addImage(
        img: Element,
        blocks: MutableList<NoteBlock>,
        images: Int,
    ): Int {
        if (images >= MAX_IMAGES) return images
        blocks.add(
            NoteBlock.Image(
                url = img.attr("src"),
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
        return images + 1
    }

    /** Inline content as spans; timestamps linkify only outside links (03 Timestamp linkifier). */
    private fun inlineSpansOf(node: Node): List<NoteSpan> =
        when (node) {
            is TextNode -> TimestampLinkifier.linkify(collapseWhitespace(node.text()))
            is Element -> elementSpansOf(node)
            else -> emptyList()
        }

    private fun elementSpansOf(element: Element): List<NoteSpan> {
        val children = element.childNodes().flatMap(::inlineSpansOf)
        return when (element.tagName()) {
            "b", "strong" -> {
                withStyle(children, BOLD)
            }

            "i", "em", "cite" -> {
                withStyle(children, ITALIC)
            }

            "u" -> {
                withStyle(children, UNDERLINE)
            }

            "code" -> {
                withStyle(children, CODE)
            }

            "a" -> {
                val href = element.attr("href")
                if (href.isNotEmpty()) {
                    listOf(NoteSpan.Link(textOf(children), href, styleOf(children)))
                } else {
                    children // an a without a surviving href becomes plain text
                }
            }

            "br" -> {
                listOf(NoteSpan.LineBreak)
            }

            else -> {
                children
            } // span, q, small, strike, sub, sup contribute their text only
        }
    }

    /** `pre` keeps its line breaks as LineBreak spans, with CODE style; whitespace is not collapsed. */
    private fun appendPreformatted(
        node: Node,
        spans: MutableList<NoteSpan>,
    ) {
        when (node) {
            is TextNode -> {
                for ((i, line) in node.wholeText.split('\n').withIndex()) {
                    if (i > 0) spans.add(NoteSpan.LineBreak)
                    if (line.isNotEmpty()) spans.add(NoteSpan.Text(line, CODE))
                }
            }

            is Element -> {
                for (child in node.childNodes()) appendPreformatted(child, spans)
            }

            else -> {
                Unit
            }
        }
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

    /** Adjacent Text spans with equal style merge (03 mapping rules). */
    private fun mergeTextSpans(spans: List<NoteSpan>): List<NoteSpan> {
        val merged = mutableListOf<NoteSpan>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (span is NoteSpan.Text && last is NoteSpan.Text && last.style == span.style) {
                merged[merged.size - 1] = last.copy(text = last.text + span.text)
            } else {
                merged.add(span)
            }
        }
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
        const val SNIPPET_MAX_CHARS = 199
        const val MAX_BLOCKS = 2_000
        const val MAX_LIST_NESTING = 4
        const val MAX_IMAGES = 50
    }
}
