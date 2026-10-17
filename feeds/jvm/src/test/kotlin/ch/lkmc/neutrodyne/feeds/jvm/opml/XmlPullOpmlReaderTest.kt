// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.opml.OpmlFailure
import ch.lkmc.neutrodyne.feeds.opml.OpmlLimits
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import ch.lkmc.neutrodyne.feeds.opml.OpmlWarningCode
import ch.lkmc.neutrodyne.feeds.opml.ParseMode
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cascade behaviors the golden corpus does not pin (05 Testing): failure reasons, which pass
 * answered, the `open`-once-per-pass contract and that caps never fall through to a later leg.
 */
class XmlPullOpmlReaderTest {
    private val reader: OpmlReader = XmlPullOpmlReader.discovered()

    private fun read(xml: String): OpmlReadResult = reader.read({ Buffer().writeString(xml, Charsets.UTF_8) })

    private fun failure(xml: String): OpmlFailure = (read(xml) as OpmlReadResult.Failed).failure

    private fun failureBytes(bytes: ByteArray): OpmlFailure =
        (reader.read({ Buffer().write(bytes) }) as OpmlReadResult.Failed).failure

    @Test
    fun emptyDocumentHasNoOutlines() {
        assertEquals(OpmlFailure.NO_OUTLINES, failure("<opml version=\"1.0\"/>"))
    }

    @Test
    fun nonOpmlRootHasNoOutlines() {
        assertEquals(OpmlFailure.NO_OUTLINES, failure("<rss version=\"2.0\"><channel/></rss>"))
    }

    /**
     * A document that is not XML at all: both pull legs throw and the salvage scanner finds no
     * outline, so the read ends in NO_OUTLINES — the cascade's terminal failure for unusable input.
     */
    @Test
    fun undecodableGarbageHasNoOutlines() {
        assertEquals(OpmlFailure.NO_OUTLINES, failure("this is not xml at all"))
    }

    @Test
    fun entityDeclarationFails() {
        assertEquals(
            OpmlFailure.ENTITY_DECLARED,
            failure("<!DOCTYPE opml [<!ENTITY x \"y\">]><opml version=\"1.0\"/>"),
        )
    }

    /** A bare DOCTYPE without entity declarations is not hostile; it just holds no outlines. */
    @Test
    fun plainDoctypeParses() {
        assertEquals(OpmlFailure.NO_OUTLINES, failure("<!DOCTYPE opml><opml version=\"1.0\"/>"))
    }

    /**
     * An attribute over `maxAttrChars` drops its outline with a warning (05 walk rules), leaving
     * the rest of the document readable; a dropped folder's children re-parent upwards.
     */
    @Test
    fun oversizedAttributeDropsOnlyItsOutline() {
        val big = "b".repeat(9_000)
        val xml =
            "<opml version=\"1.0\"><body>" +
                "<outline text=\"folder\">" +
                "<outline type=\"rss\" text=\"heavy\" xmlUrl=\"https://heavy.example.com/rss\" note=\"$big\"/>" +
                "<outline type=\"rss\" text=\"light\" xmlUrl=\"https://light.example.com/rss\"/>" +
                "</outline>" +
                "</body></opml>"
        val result = read(xml)
        assertIs<OpmlReadResult.Ok>(result)
        assertEquals(1, result.document.entries.size)
        assertEquals("https://light.example.com/rss", result.document.entries[0].url)
        assertEquals(
            listOf(OpmlWarningCode.ATTRIBUTE_TOO_LONG),
            result.document.warnings.map { it.code },
        )
    }

    /**
     * 05 walk rules: `url` is the feed fallback only when the URL does not end in `.opml` — a
     * `type="rss"` outline pointing at another list is a folder, like any untyped outline.
     */
    @Test
    fun rssOutlineWithOpmlUrlIsAFolder() {
        val result =
            read(
                "<opml version=\"1.0\"><body>" +
                    "<outline type=\"rss\" url=\"https://x.example.com/list.opml\" text=\"shared\"/>" +
                    "</body></opml>",
            )
        assertIs<OpmlReadResult.Ok>(result)
        assertEquals(emptyList(), result.document.entries)
        assertEquals(listOf("shared"), result.document.folders.map { it.name })
    }

    /**
     * `encoding="UTF-16"` shifts the parser's view of the body where the byte-level guard keeps
     * its ASCII view — the `<!ENTITY` is invisible to the stream scan and is caught by the
     * DOCDECL-token text check instead. The fallback is terminal: `ENTITY_DECLARED`.
     */
    @Test
    fun entityAfterDeclaredCharsetShiftIsRejected() {
        val head = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
        val body = "<!DOCTYPE opml [<!ENTITY x \"y\">]><opml version=\"1.0\"/>"
        val bytes =
            head.toByteArray(Charsets.US_ASCII) + body.toByteArray(Charsets.UTF_16)
        assertEquals(OpmlFailure.ENTITY_DECLARED, failureBytes(bytes))
    }

    /** A declaration that lies about the body encoding fails fast — never a hang or a crash. */
    @Test
    fun misdeclaredEncodingFails() {
        val head = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
        val bytes =
            head.toByteArray(Charsets.US_ASCII) +
                "<opml version=\"1.0\"/>".toByteArray(Charsets.US_ASCII)
        assertIs<OpmlReadResult.Failed>(reader.read({ Buffer().write(bytes) }))
    }

    /** `open` is invoked exactly once per pass — a clean document costs the strict pass only. */
    @Test
    fun openerIsCalledOnce() {
        var opens = 0
        reader.read({
            opens++
            Buffer().writeString("<opml version=\"1.0\"/>", Charsets.UTF_8)
        })
        assertEquals(1, opens)
    }

    /**
     * A bare `&` fails strict, parses relaxed: the source is reopened once per attempted pass and
     * the document reports RELAXED.
     */
    @Test
    fun bareAmpersandFallsThroughToRelaxed() {
        var opens = 0
        val result =
            reader.read({
                opens++
                Buffer().writeString(
                    "<opml version=\"1.0\"><body>" +
                        "<outline text=\"a & b\" type=\"rss\" xmlUrl=\"https://x.example.com/rss\"/>" +
                        "</body></opml>",
                    Charsets.UTF_8,
                )
            })
        assertIs<OpmlReadResult.Ok>(result)
        assertEquals(ParseMode.RELAXED, result.document.mode)
        assertEquals(
            "a & b",
            result.document.entries
                .single()
                .text,
        )
        assertEquals(2, opens)
    }

    /**
     * A malformed numeric reference (`&#xZZZZ;`) throws inside kxml2 even under relaxed; the
     * salvage scanner lifts the outline anyway, keeps the reference literal and reports SALVAGE
     * with its warning — the source was reopened once per attempted pass.
     */
    @Test
    fun badEntityRefSalvages() {
        var opens = 0
        val result =
            reader.read({
                opens++
                Buffer().writeString(
                    "<opml version=\"1.0\"><body>" +
                        "<outline text=\"fine\" type=\"rss\" xmlUrl=\"https://f.example.com/rss\"/>" +
                        "<outline text=\"bad\" type=\"rss\" xmlUrl=\"https://p.example.com/rss\" note=\"&#xZZZZ;\"/>" +
                        "</body></opml>",
                    Charsets.UTF_8,
                )
            })
        assertIs<OpmlReadResult.Ok>(result)
        assertEquals(ParseMode.SALVAGE, result.document.mode)
        assertEquals(2, result.document.entries.size)
        assertEquals("https://p.example.com/rss", result.document.entries[1].url)
        assertTrue(
            OpmlWarningCode.SALVAGED in result.document.warnings.map { it.code },
            "SALVAGED warning expected, got ${result.document.warnings}",
        )
        assertEquals(3, opens)
    }

    /**
     * A cap violation in the strict pass is terminal: it never falls through to relaxed or
     * salvage, so `open` runs once even though the file could be made to parse.
     */
    @Test
    fun capViolationDoesNotFallThrough() {
        var opens = 0
        val xml =
            "<opml version=\"1.0\"><body>" +
                "<outline type=\"rss\" xmlUrl=\"https://a.example.com/rss\"/>" +
                "<outline type=\"rss\" xmlUrl=\"https://b.example.com/rss\"/>" +
                "</body></opml>"
        val result =
            reader.read({
                opens++
                Buffer().writeString(xml, Charsets.UTF_8)
            }, OpmlLimits(maxFeeds = 1))
        assertEquals(OpmlFailure.TOO_MANY_FEEDS, (result as OpmlReadResult.Failed).failure)
        assertEquals(1, opens)
    }

    /** NO_OUTLINES is terminal like a cap: a strict-pass empty document opens the source once. */
    @Test
    fun noOutlinesDoesNotFallThrough() {
        var opens = 0
        val result =
            reader.read({
                opens++
                Buffer().writeString("<opml version=\"1.0\"/>", Charsets.UTF_8)
            })
        assertEquals(OpmlFailure.NO_OUTLINES, (result as OpmlReadResult.Failed).failure)
        assertEquals(1, opens)
    }

    /**
     * The 64 KiB salvage-tag cap (05 Sniff and parse step 3): a document pushed past both pull
     * legs by a malformed `&#xZZZZ;` reference, whose outline tag also outgrows the salvage cap,
     * fails TAG_TOO_LONG — terminal in salvage exactly as in the pull passes.
     */
    @Test
    fun salvageTagCapIsTerminal() {
        val xml =
            "<opml version=\"1.0\"><body><outline text=\"k\" xmlUrl=\"https://x/\" note=\"" +
                "a".repeat(70 * 1024) + "&#xZZZZ;\"/></body></opml>"
        assertEquals(OpmlFailure.TAG_TOO_LONG, failure(xml))
    }

    /** The same cap fires when the scanner is driven directly (its own bound, not the gap's). */
    @Test
    fun salvageScannerEnforcesTagCap() {
        val xml =
            "<opml version=\"1.0\"><body><outline " + "a".repeat(70 * 1024)
        val result = SalvageOpmlScanner(OpmlLimits()).scan { Buffer().writeString(xml, Charsets.UTF_8) }
        assertEquals(OpmlFailure.TAG_TOO_LONG, (result as OpmlReadResult.Failed).failure)
    }
}
