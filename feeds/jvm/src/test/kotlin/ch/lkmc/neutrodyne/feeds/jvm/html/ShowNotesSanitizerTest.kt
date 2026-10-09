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
        val link = spansOf(blocks.single()).single() as NoteSpan.Link
        assertThat(link.url).isEqualTo("mailto:host@example.com")
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

    /** S5: a pathological `src` must not make the tracking-image check quadratic. */
    @Test(timeout = 10_000)
    fun trackingImageCheckStaysLinear() {
        // W3 bounds URL attributes at 4,096 chars before the clean, so this adversarial name
        // fills the attr right up to the cap — the marker scan must stay linear across it.
        val html = """<img src="https://example.com/""" + "1x1".repeat(1_300) + ".jpg\">"
        val blocks = blocksOf(html)
        // Not a gif/png name and no size attributes: the image is kept.
        assertThat(blocks.filterIsInstance<NoteBlock.Image>()).hasSize(1)
    }

    /** S6: merging 30,000 adjacent equal-style texts must not be quadratic. */
    @Test(timeout = 10_000)
    fun equalStyleTextMergingStaysLinear() {
        val blocks = blocksOf("<p>" + "<span>x</span>".repeat(30_000) + "</p>")
        val text = spansOf(blocks.single()).filterIsInstance<NoteSpan.Text>().single()
        assertThat(text.text).isEqualTo("x".repeat(30_000))
    }

    @Test
    fun blockCapCoversPlainTextParagraphs() {
        val document =
            sanitizer.toDocument("x\n\n".repeat(2_001), isHtml = false, baseUri = base)
        assertThat(document.blocks).hasSize(2_000)
    }

    @Test
    fun blockCapCoversTopLevelHtml() {
        val blocks = blocksOf("<p>x</p>".repeat(2_001))
        assertThat(blocks).hasSize(2_000)
    }

    @Test
    fun blockCapCoversListItems() {
        val blocks = blocksOf("<ul>" + "<li>x</li>".repeat(2_001) + "</ul>")
        val list = blocks.single() as NoteBlock.ListBlock
        // 1 list + 1,999 item paragraphs = the 2,000-block budget; the last items are dropped.
        assertThat(list.items).hasSize(1_999)
    }

    @Test
    fun blockCapCoversNestedQuoteBlocks() {
        val blocks = blocksOf("<blockquote>" + "<p>x</p>".repeat(30_000) + "</blockquote>")
        val quote = blocks.single() as NoteBlock.Quote
        assertThat(quote.blocks).hasSize(1_999)
    }

    /** C11: HTML-flagged text without tags takes the plain-text path after normalisation. */
    @Test
    fun htmlFlaggedTaglessInputGetsPlainTextHandling() {
        val document =
            sanitizer.toDocument("a\nb\n\nhttps://example.com/x", isHtml = true, baseUri = base)
        assertThat(document.blocks).hasSize(2)
        val first = (document.blocks[0] as NoteBlock.Paragraph).spans
        assertThat(first).contains(NoteSpan.LineBreak)
        val link =
            (document.blocks[1] as NoteBlock.Paragraph).spans.filterIsInstance<NoteSpan.Link>().single()
        assertThat(link.url).isEqualTo("https://example.com/x")
    }

    /** C12: `div` contents flatten into one paragraph that keeps inline mappings. */
    @Test
    fun containerInlineContentKeepsLinks() {
        val blocks = blocksOf("""<div>Hello <a href="/about">there</a>!</div>""")
        val spans = spansOf(blocks.single())
        val link = spans.filterIsInstance<NoteSpan.Link>().single()
        assertThat(link.url).isEqualTo("https://example.com/about")
        assertThat(link.text).isEqualTo("there")
        val text = spans.filterIsInstance<NoteSpan.Text>().joinToString("") { it.text }
        assertThat(text).isEqualTo("Hello !")
    }

    /** C12: the same accumulation applies inside list items. */
    @Test
    fun listItemInlineContentKeepsLinks() {
        val blocks = blocksOf("""<ul><li>Hello <a href="/about">there</a>!</li></ul>""")
        val item = (blocks.single() as NoteBlock.ListBlock).items.single().single()
        val link = (item as NoteBlock.Paragraph).spans.filterIsInstance<NoteSpan.Link>().single()
        assertThat(link.url).isEqualTo("https://example.com/about")
    }

    /** C26: escaped markup is decoded without whitespace normalisation. */
    @Test
    fun escapedPreKeepsItsLineBreaks() {
        val document =
            sanitizer.toDocument("&lt;pre&gt;a\nb&lt;/pre&gt;", isHtml = true, baseUri = base)
        val spans = (document.blocks.single() as NoteBlock.Paragraph).spans
        assertThat(spans)
            .containsExactly(NoteSpan.Text("a", CODE), NoteSpan.LineBreak, NoteSpan.Text("b", CODE))
            .inOrder()
    }

    /** C27: an `img` wrapped in inline elements still becomes an Image block. */
    @Test
    fun imageInsideInlineElementIsEmitted() {
        val blocks = blocksOf("""<p><b><img src="https://cdn.example/image.jpg"></b></p>""")
        val image = blocks.filterIsInstance<NoteBlock.Image>().single()
        assertThat(image.url).isEqualTo("https://cdn.example/image.jpg")
    }

    /** C28: a `src` the cleaner rejects leaves no Image block behind. */
    @Test
    fun imageWithRejectedSourceIsOmitted() {
        val blocks = blocksOf("""<p><img src="data:image/png;base64,AAAA">text</p>""")
        assertThat(blocks.filterIsInstance<NoteBlock.Image>()).isEmpty()
    }

    /** C29: `pre` keeps LineBreak and Link mappings. */
    @Test
    fun preKeepsBreaksAndLinks() {
        val spans = spansOf(blocksOf("<pre>a<br>b</pre>").single())
        assertThat(spans).contains(NoteSpan.LineBreak)
        assertThat(spans.filterIsInstance<NoteSpan.Text>().map { it.text }).containsExactly("a", "b")

        val withLink = spansOf(blocksOf("""<pre><a href="https://example.com/x">go</a></pre>""").single())
        val link = withLink.filterIsInstance<NoteSpan.Link>().single()
        assertThat(link.url).isEqualTo("https://example.com/x")
        assertThat(link.text).isEqualTo("go")
    }

    /** C30: timestamps linkify over the whole run, so a trailing `h` still excludes them. */
    @Test
    fun timestampLooksPastInlineBoundaries() {
        val spans = spansOf(blocksOf("<p>10:30<b>h</b></p>").single())
        assertThat(spans.filterIsInstance<NoteSpan.Timestamp>()).isEmpty()
        assertThat(spans.filterIsInstance<NoteSpan.Text>().map { it.text })
            .containsExactly("10:30", "h")
            .inOrder()
    }

    /** C30: a timestamp inside a link stays link text and keeps the child style. */
    @Test
    fun linkKeepsChildStyles() {
        val spans =
            spansOf(blocksOf("""<p><a href="https://example.com/x"><b>1:00</b></a></p>""").single())
        val link = spans.filterIsInstance<NoteSpan.Link>().single()
        assertThat(link.text).isEqualTo("1:00")
        assertThat(link.style and BOLD).isEqualTo(BOLD)
        assertThat(spans.filterIsInstance<NoteSpan.Timestamp>()).isEmpty()
    }

    /** C31: whitespace collapsing carries across inline nodes. */
    @Test
    fun whitespaceCollapsesAcrossInlineBoundaries() {
        val spans = spansOf(blocksOf("<p>a <b> b </b> c</p>").single())
        assertThat(spans.filterIsInstance<NoteSpan.Text>().joinToString("") { it.text })
            .isEqualTo("a b c")
    }

    /** C32: a word ending exactly at the cut stays in the snippet. */
    @Test
    fun snippetKeepsWordEndingAtTheLimit() {
        val text = "a".repeat(195) + " end more"
        assertThat(sanitizer.snippet(text, isHtml = false)).isEqualTo("a".repeat(195) + " end…")
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

    /** S-rev 1: rejected nodes are skipped during emission, not pruned from the DOM beforehand. */
    @Test(timeout = 10_000)
    fun emptyParagraphSkippingStaysLinear() {
        val blocks = blocksOf("<p></p>".repeat(70_000))
        assertThat(blocks).isEmpty()
    }

    /** S-rev 1: thousands of sibling tracking-pixel removals must not shift sibling lists. */
    @Test(timeout = 10_000)
    fun tinySiblingImageSkippingStaysLinear() {
        val html = "<img src=\"https://e.test/x.png\" width=\"1\">".repeat(30_000)
        assertThat(blocksOf(html)).isEmpty()
    }

    /** S-rev 2: a consumed timestamp still advances the cursor over the styled pieces. */
    @Test
    fun timestampKeepsFollowingStyles() {
        val spans = spansOf(blocksOf("<p>1:00<b>x</b></p>").single())
        assertThat(spans.filterIsInstance<NoteSpan.Timestamp>().single().positionMs).isEqualTo(60_000)
        val text = spans.filterIsInstance<NoteSpan.Text>().single()
        assertThat(text.text).isEqualTo("x")
        assertThat(text.style).isEqualTo(BOLD)
    }

    /** S-rev 3: the anchor's leading space collapses against the surrounding run, never inwards. */
    @Test
    fun anchorWhitespaceCollapsesAgainstTheRun() {
        val spans = spansOf(blocksOf("""<p>Hello<a href="/about"> world</a></p>""").single())
        assertThat(spans.filterIsInstance<NoteSpan.Text>().single().text).isEqualTo("Hello")
        val link = spans.filterIsInstance<NoteSpan.Link>().single()
        assertThat(link.text).isEqualTo(" world")
        assertThat(link.url).isEqualTo("https://example.com/about")
    }

    /** S-rev 3: a `br` inside an anchor splits the link and keeps document order. */
    @Test
    fun lineBreakInsideAnchorSplitsTheLink() {
        val spans = spansOf(blocksOf("""<a href="/x">one<br>two</a>""").single())
        assertThat(spans)
            .containsExactly(
                NoteSpan.Link("one", "https://example.com/x", 0),
                NoteSpan.LineBreak,
                NoteSpan.Link("two", "https://example.com/x", 0),
            ).inOrder()
    }

    /** S-rev 3: an `img` inside an anchor splits the link in document order, before and after. */
    @Test
    fun imageInsideAnchorSplitsTheLinkInOrder() {
        val blocks = blocksOf("""<p>A<a href="/x">B<img src="https://cdn.test/i.jpg">C</a>D</p>""")
        assertThat(blocks).hasSize(3)
        assertThat(spansOf(blocks[0]))
            .containsExactly(NoteSpan.Text("A", 0), NoteSpan.Link("B", "https://example.com/x", 0))
            .inOrder()
        assertThat((blocks[1] as NoteBlock.Image).url).isEqualTo("https://cdn.test/i.jpg")
        assertThat(spansOf(blocks[2]))
            .containsExactly(NoteSpan.Link("C", "https://example.com/x", 0), NoteSpan.Text("D", 0))
            .inOrder()
    }

    /** S-rev 4: an image inside a `pre`'s anchor is still emitted, link and whitespace intact. */
    @Test
    fun linkedImageInsidePreIsEmitted() {
        val blocks = blocksOf("""<pre><a href="https://example.com/x"><img src="https://cdn.test/i.jpg"></a></pre>""")
        assertThat(blocks.filterIsInstance<NoteBlock.Image>().single().url)
            .isEqualTo("https://cdn.test/i.jpg")
    }

    /** S-rev 5: `figcaption`'s italic context reaches the paragraph inside it. */
    @Test
    fun figcaptionNestedParagraphKeepsItalic() {
        val blocks = blocksOf("<figure><figcaption><p>caption</p></figcaption></figure>")
        val text = spansOf(blocks.single()).filterIsInstance<NoteSpan.Text>().single()
        assertThat(text.style and ITALIC).isEqualTo(ITALIC)
    }

    /** S-rev 5: `dt`'s bold context reaches the paragraph inside it. */
    @Test
    fun dtNestedParagraphKeepsBold() {
        val blocks = blocksOf("<dl><dt><p>term</p></dt><dd>def</dd></dl>")
        val term = spansOf(blocks[0]).filterIsInstance<NoteSpan.Text>().single()
        assertThat(term.style and BOLD).isEqualTo(BOLD)
        val def = spansOf(blocks[1]).filterIsInstance<NoteSpan.Text>().single()
        assertThat(def.style and BOLD).isEqualTo(0)
    }

    /** S-rev 5: a `div` inside `figcaption` keeps the inherited italic. */
    @Test
    fun divInsideFigcaptionKeepsItalic() {
        val blocks = blocksOf("<figcaption><div>cap</div></figcaption>")
        val text = spansOf(blocks.single()).filterIsInstance<NoteSpan.Text>().single()
        assertThat(text.style and ITALIC).isEqualTo(ITALIC)
    }

    /** S-rev 6: a literal `<` in text does not select the HTML path. */
    @Test
    fun literalLessThanDoesNotForceHtmlPath() {
        val document =
            sanitizer.toDocument("2 < 3\nhttps://example.com/x", isHtml = true, baseUri = base)
        val spans = spansOf(document.blocks.single())
        assertThat(spans).contains(NoteSpan.LineBreak)
        assertThat(spans.filterIsInstance<NoteSpan.Link>().single().url)
            .isEqualTo("https://example.com/x")
        val text = spans.filterIsInstance<NoteSpan.Text>().joinToString("") { it.text }
        assertThat(text).contains("2 < 3")
    }

    /** S-rev 7: the snippet cut never lands between a surrogate pair. */
    @Test
    fun snippetNeverSplitsASurrogatePair() {
        val snippet = sanitizer.snippet("a".repeat(198) + "🙂z", isHtml = false)
        assertThat(snippet).isEqualTo("a".repeat(198) + "…")
    }

    /** S-rev 8: timestamps linkify inside `pre`, whitespace preserved. */
    @Test
    fun preTextIsTimestampLinkified() {
        val spans = spansOf(blocksOf("<pre>skip to 0:30</pre>").single())
        val timestamp = spans.filterIsInstance<NoteSpan.Timestamp>().single()
        assertThat(timestamp.positionMs).isEqualTo(30_000)
        assertThat(timestamp.text).isEqualTo("0:30")
    }

    /** W3: a giant relative URL is bounded before jsoup resolves it, not after the walk. */
    @Test(timeout = 10_000)
    fun overlongUrlAttributesAreBoundedBeforeResolution() {
        val html = """<p><a href="""" + "./".repeat(200_000) + """x">x</a></p>"""
        val spans = spansOf(blocksOf(html).single())
        // Over the URL bound the attribute is dropped; the anchor falls back to plain text.
        assertThat(spans.filterIsInstance<NoteSpan.Link>()).isEmpty()
        assertThat(spans.filterIsInstance<NoteSpan.Text>().single().text).isEqualTo("x")
    }

    /** W7: a timestamp split across inline elements inside `pre` linkifies over the complete run. */
    @Test
    fun preTimestampSpansInlineBoundaries() {
        val spans = spansOf(blocksOf("<pre>skip to 1<b>2:34</b></pre>").single())
        val timestamp = spans.filterIsInstance<NoteSpan.Timestamp>().single()
        assertThat(timestamp.text).isEqualTo("12:34")
        assertThat(timestamp.positionMs).isEqualTo(754_000)
    }

    /** W7: a time-of-day suffix split across elements still excludes the timestamp. */
    @Test
    fun preTimeOfDaySpanningInlineBoundariesIsNotLinkified() {
        val spans = spansOf(blocksOf("<pre>10:30<b> am</b></pre>").single())
        assertThat(spans.filterIsInstance<NoteSpan.Timestamp>()).isEmpty()
        assertThat(spans.filterIsInstance<NoteSpan.Text>().joinToString("") { it.text })
            .isEqualTo("10:30 am")
    }

    /** W8: a block child inside an anchor keeps the link. */
    @Test
    fun anchorWrapsBlockChildContent() {
        val blocks = blocksOf("""<a href="/x">one<div>two</div>three</a>""")
        val links = blocks.flatMap { spansOf(it) }.filterIsInstance<NoteSpan.Link>()
        assertThat(links.map { it.text }).containsExactly("one", "two", "three").inOrder()
        assertThat(links.map { it.url }.distinct()).containsExactly("https://example.com/x")
    }

    /** W9: a paragraph of only breaks emits no block and spends no budget. */
    @Test
    fun breakOnlyParagraphsSpendNoBudget() {
        val blocks = blocksOf("<p><br></p>".repeat(2_000) + "<p>kept</p>")
        assertThat(blocks).hasSize(1)
        assertThat(spansOf(blocks.single()).filterIsInstance<NoteSpan.Text>().single().text)
            .isEqualTo("kept")
    }

    /**
     * X1: `<base href>` resolves while jsoup parses — a giant relative value reaches JDK URL
     * normalization (quadratic) before the attribute bound can drop it. Parsing with an empty base
     * and attaching the caller base after bounding keeps it linear.
     */
    @Test(timeout = 10_000)
    fun overlongBaseHrefIsBoundedBeforeResolution() {
        val html = """<base href="""" + "./".repeat(200_000) + """x"><p>kept</p>"""
        val blocks = blocksOf(html)
        assertThat(blocks).hasSize(1)
        assertThat(spansOf(blocks.single()).filterIsInstance<NoteSpan.Text>().single().text)
            .isEqualTo("kept")
    }

    /** X1: a bounded `<base href>` still re-roots relative links after the bound. */
    @Test
    fun boundedBaseElementStillRerootsLinks() {
        val blocks = blocksOf("""<base href="https://cdn.example/n/"><p><a href="a.html">go</a></p>""")
        val link = spansOf(blocks.single()).filterIsInstance<NoteSpan.Link>().single()
        assertThat(link.url).isEqualTo("https://cdn.example/n/a.html")
    }

    /** X8: an anchor's link reaches a block nested inside an inline wrapper. */
    @Test
    fun inheritedLinkReachesBlockInsideInlineWrapper() {
        val blocks = blocksOf("""<a href="/x"><div><b><div>12:34</div></b></div></a>""")
        val spans = blocks.flatMap { spansOf(it) }
        val link = spans.filterIsInstance<NoteSpan.Link>().single()
        assertThat(link.text).isEqualTo("12:34")
        assertThat(link.url).isEqualTo("https://example.com/x")
        assertThat(spans.filterIsInstance<NoteSpan.Timestamp>()).isEmpty()
    }

    /** X9: a `pre` of only whitespace emits no block and spends no budget. */
    @Test
    fun whitespaceOnlyPreSpendsNoBudget() {
        val blocks = blocksOf("<pre> </pre>".repeat(2_000) + "<p>kept</p>")
        assertThat(blocks).hasSize(1)
        assertThat(spansOf(blocks.single()).filterIsInstance<NoteSpan.Text>().single().text)
            .isEqualTo("kept")
    }

    /** X9: meaningful preformatted whitespace is still preserved verbatim. */
    @Test
    fun meaningfulPreWhitespaceIsPreserved() {
        val spans = spansOf(blocksOf("<pre>  ind\nnext</pre>").single())
        assertThat(spans.filterIsInstance<NoteSpan.Text>().map { it.text })
            .containsExactly("  ind", "next")
            .inOrder()
        assertThat(spans).contains(NoteSpan.LineBreak)
    }

    private fun stylesAround(
        spans: List<NoteSpan>,
        text: String,
    ): Int = spans.filterIsInstance<NoteSpan.Text>().first { it.text == text }.style
}
