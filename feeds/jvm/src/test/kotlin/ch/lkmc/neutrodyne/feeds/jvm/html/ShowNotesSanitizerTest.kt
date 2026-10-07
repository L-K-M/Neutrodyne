// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.html

import ch.lkmc.neutrodyne.feeds.html.NoteBlock
import ch.lkmc.neutrodyne.feeds.html.NoteSpan
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.BOLD
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.CODE
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.ITALIC
import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.UNDERLINE
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The allow-list, block model and snippet rules of 03 Show notes. */
class ShowNotesSanitizerTest {
    private val sanitizer = JsoupShowNotesSanitizer()
    private val base = "https://example.com/episodes/42"

    private fun blocksOf(html: String) = sanitizer.toDocument(html, isHtml = true, baseUri = base).blocks

    private fun spansOf(block: NoteBlock): List<NoteSpan> = (block as? NoteBlock.Paragraph)?.spans ?: emptyList()

    @Test
    fun scriptStyleAndIframeAreRemoved() {
        val blocks =
            blocksOf(
                """<p>kept</p><script>alert(1)</script><style>p{color:red}</style>
            <iframe src="https://evil.example"></iframe><p>also kept</p>""",
            )
        assertThat(blocks.map { (it as NoteBlock.Paragraph).spans.single().let { s -> (s as NoteSpan.Text).text } })
            .containsExactly("kept", "also kept")
            .inOrder()
    }

    @Test
    fun javascriptAndDataLinksAreDropped() {
        val blocks =
            blocksOf(
                """<p><a href="javascript:alert(1)">bad</a> <a href="data:text/html,x">worse</a>
            <a href="https://example.com/ok">good</a></p>""",
            )
        val links = spansOf(blocks.single()).filterIsInstance<NoteSpan.Link>()
        assertThat(links).hasSize(1)
        assertThat(links.single().url).isEqualTo("https://example.com/ok")
        // The dropped protocols leave their text behind as plain text.
        val text = spansOf(blocks.single()).filterIsInstance<NoteSpan.Text>().joinToString("") { it.text }
        assertThat(text).contains("bad")
    }

    @Test
    fun ftpLinksAreDropped() {
        val blocks = blocksOf("""<p><a href="ftp://files.example.com/x">file</a></p>""")
        assertThat(spansOf(blocks.single()).filterIsInstance<NoteSpan.Link>()).isEmpty()
    }

    @Test
    fun mailtoLinksSurvive() {
        val blocks = blocksOf("""<p><a href="mailto:host@example.com">write</a></p>""")
        assertThat(spansOf(blocks.single()).single() as NoteSpan.Link).isNotNull()
    }

    @Test
    fun relativeLinksAreAbsolutised() {
        val blocks = blocksOf("""<p><a href="/about">about</a> <img src="pic.jpg" alt="a pic"></p>""")
        val link = spansOf(blocks[0]).filterIsInstance<NoteSpan.Link>().single()
        assertThat(link.url).isEqualTo("https://example.com/about")
        val image = blocks[1] as NoteBlock.Image
        assertThat(image.url).isEqualTo("https://example.com/episodes/pic.jpg")
    }

    @Test
    fun trackingPixelsAreRemoved() {
        val blocks =
            blocksOf(
                """<p>text</p><img src="https://metrics.example/pixel.gif" width="1" height="1">
            <img src="https://metrics.example/track.png"><img src="https://cdn.example/real.jpg">""",
            )
        val images = blocks.filterIsInstance<NoteBlock.Image>()
        assertThat(images).hasSize(1)
        assertThat(images.single().url).isEqualTo("https://cdn.example/real.jpg")
    }

    @Test
    fun tinyImagesAreRemoved() {
        val blocks = blocksOf("""<img src="https://cdn.example/spacer.png" width="2" height="2">""")
        assertThat(blocks.filterIsInstance<NoteBlock.Image>()).isEmpty()
    }

    @Test
    fun emptyParagraphsAreRemoved() {
        val blocks = blocksOf("<p></p><p>  </p><p>real</p>")
        assertThat(blocks).hasSize(1)
    }

    @Test
    fun everyTagOfTheMappingTable() {
        val blocks =
            blocksOf(
                """
            <h2>Heading</h2>
            <p>b <b>bold</b> <strong>strong</strong> <i>it</i> <em>em</em> <cite>cite</cite> <u>u</u>
            <code>code</code> and a<br>break</p>
            <ul><li>one</li><li>two <ul><li>nested</li></ul></li></ul>
            <ol><li>first</li></ol>
            <blockquote><p>quoted</p></blockquote>
            <figure><img src="https://cdn.example/fig.jpg" alt="figure"><figcaption>caption</figcaption></figure>
            <pre>line one
line two</pre>
            <dl><dt>term</dt><dd>definition</dd></dl>
            <div><p>in a div</p></div>
            <hr>
            <p>span <span>spanned</span> <q>quoted</q> <small>small</small> <strike>struck</strike>
            h<sub>2</sub>o x<sup>2</sup></p>
            """,
            )

        val heading = blocks[0] as NoteBlock.Heading
        assertThat(heading.level).isEqualTo(2)

        val paragraphSpans = spansOf(blocks[1])
        assertThat(stylesAround(paragraphSpans, "bold")).isEqualTo(BOLD)
        assertThat(stylesAround(paragraphSpans, "strong")).isEqualTo(BOLD)
        assertThat(stylesAround(paragraphSpans, "it")).isEqualTo(ITALIC)
        assertThat(stylesAround(paragraphSpans, "em")).isEqualTo(ITALIC)
        assertThat(stylesAround(paragraphSpans, "cite")).isEqualTo(ITALIC)
        assertThat(stylesAround(paragraphSpans, "u")).isEqualTo(UNDERLINE)
        assertThat(stylesAround(paragraphSpans, "code")).isEqualTo(CODE)
        assertThat(paragraphSpans).contains(NoteSpan.LineBreak)

        val unordered = blocks[2] as NoteBlock.ListBlock
        assertThat(unordered.ordered).isFalse()
        assertThat(unordered.items).hasSize(2)
        val nested = unordered.items[1][1] as NoteBlock.ListBlock
        assertThat(nested.ordered).isFalse()

        val ordered = blocks[3] as NoteBlock.ListBlock
        assertThat(ordered.ordered).isTrue()

        val quote = blocks[4] as NoteBlock.Quote
        assertThat(quote.blocks).isNotEmpty()

        val figureImage = blocks[5] as NoteBlock.Image
        assertThat(figureImage.alt).isEqualTo("figure")
        val caption = spansOf(blocks[6])
        assertThat(caption.filterIsInstance<NoteSpan.Text>().single().style).isEqualTo(ITALIC)

        val pre = spansOf(blocks[7])
        assertThat(pre.filterIsInstance<NoteSpan.Text>().map { it.text }).containsExactly("line one", "line two")
        assertThat(pre.filterIsInstance<NoteSpan.Text>().all { it.style == CODE }).isTrue()
        assertThat(pre).contains(NoteSpan.LineBreak)

        val definitionTerm = spansOf(blocks[8]).filterIsInstance<NoteSpan.Text>().single()
        assertThat(definitionTerm.style).isEqualTo(BOLD)
        val definition = spansOf(blocks[9]).filterIsInstance<NoteSpan.Text>().single()
        assertThat(definition.style).isEqualTo(0)

        val divParagraph = (blocks[10] as NoteBlock.Paragraph).spans.single() as NoteSpan.Text
        assertThat(divParagraph.text).isEqualTo("in a div")
        assertThat(blocks[11]).isEqualTo(NoteBlock.Rule)

        // span/q/small/strike/sub/sup contribute their text only.
        val last = spansOf(blocks[12]).filterIsInstance<NoteSpan.Text>().joinToString("") { it.text }
        assertThat(last).contains("spanned")
        assertThat(last).contains("quoted")
        assertThat(last).contains("small")
        assertThat(last).contains("struck")
        assertThat(last).contains("h2o")
        assertThat(last).contains("x2")
    }

    @Test
    fun timestampsLinkifyOutsideLinks() {
        val blocks = blocksOf("""<p>Jump to <a href="https://example.com/t">12:34</a> or 1:02:03 later</p>""")
        val spans = spansOf(blocks.single())
        // Inside the link: plain text.
        assertThat(spans.filterIsInstance<NoteSpan.Link>().single().text).isEqualTo("12:34")
        // Outside: a Timestamp span.
        val timestamp = spans.filterIsInstance<NoteSpan.Timestamp>().single()
        assertThat(timestamp.positionMs).isEqualTo(3_723_000)
    }

    @Test
    fun adjacentTextSpansMerge() {
        val blocks = blocksOf("""<p>plain <b>bold</b> plain</p>""")
        val texts = spansOf(blocks.single()).filterIsInstance<NoteSpan.Text>()
        assertThat(texts).hasSize(3)
    }

    @Test
    fun plainTextBecomesParagraphsWithLinksAndBreaks() {
        val document =
            sanitizer.toDocument(
                "First line with https://example.com/a link\nsecond line\n\nNew paragraph at 2:30",
                isHtml = false,
                baseUri = base,
            )
        assertThat(document.blocks).hasSize(2)
        val first = (document.blocks[0] as NoteBlock.Paragraph).spans
        assertThat(first.filterIsInstance<NoteSpan.Link>().single().url).isEqualTo("https://example.com/a")
        assertThat(first).contains(NoteSpan.LineBreak)
        val second = (document.blocks[1] as NoteBlock.Paragraph).spans
        assertThat(second.filterIsInstance<NoteSpan.Timestamp>().single().positionMs).isEqualTo(150_000)
    }

    @Test
    fun doubleEscapedInputIsUnescapedOnce() {
        val document =
            sanitizer.toDocument(
                "&lt;p&gt;Hello &lt;b&gt;world&lt;/b&gt;&lt;/p&gt;",
                isHtml = true,
                baseUri = base,
            )
        val spans = (document.blocks.single() as NoteBlock.Paragraph).spans.filterIsInstance<NoteSpan.Text>()
        assertThat(spans.map { it.text }).containsExactly("Hello ", "world").inOrder()
        assertThat(spans.map { it.style }).containsExactly(0, BOLD).inOrder()
    }

    @Test
    fun listNestingDeeperThanFourFlattens() {
        val blocks =
            blocksOf(
                "<ul><li>a<ul><li>b<ul><li>c<ul><li>d<ul><li>too deep</li></ul></li></ul></li></ul></li></ul></li></ul>",
            )
        val outermost = blocks[0] as NoteBlock.ListBlock
        var level = outermost
        repeat(3) {
            // Each item is [Paragraph, ListBlock]; descend into the nested list.
            level = level.items.single()[1] as NoteBlock.ListBlock
        }
        // Levels 1–4 stay lists; the fifth is flattened into the fourth item's blocks.
        val fourth = level.items.single()
        assertThat(fourth.filterIsInstance<NoteBlock.ListBlock>()).isEmpty()
        assertThat(fourth[0] as NoteBlock.Paragraph).isNotNull()
    }

    @Test
    fun imageCapOfFifty() {
        val html = (1..60).joinToString(" ") { """<img src="https://cdn.example/$it.jpg">""" }
        val blocks = blocksOf(html)
        assertThat(blocks.filterIsInstance<NoteBlock.Image>()).hasSize(50)
    }

    @Test
    fun snippetCutsAtWordBoundary() {
        val long = "word ".repeat(100).trim()
        val snippet = sanitizer.snippet("<p>$long</p>", isHtml = true)
        assertThat(snippet.length).isAtMost(200)
        assertThat(snippet).endsWith("…")
        // Cut at a word boundary: the character before the ellipsis is never a space.
        assertThat(snippet.dropLast(1).last()).isNotEqualTo(' ')
        assertThat(snippet).contains(" ")
    }

    @Test
    fun snippetShortTextStaysWhole() {
        assertThat(sanitizer.snippet("short text", isHtml = false)).isEqualTo("short text")
        assertThat(sanitizer.snippet("<p>html <b>text</b></p>", isHtml = true)).isEqualTo("html text")
    }

    private fun stylesAround(
        spans: List<NoteSpan>,
        text: String,
    ): Int = spans.filterIsInstance<NoteSpan.Text>().first { it.text == text }.style
}
