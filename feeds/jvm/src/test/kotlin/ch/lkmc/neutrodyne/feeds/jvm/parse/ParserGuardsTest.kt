// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.parse.ParseFailure
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The pre-parse guards decide the document encoding exactly as the pull parser does — a BOM or a
 * byte signature the parser's `setInput` supports, or the leading XML declaration, never text inside
 * a comment (03 Parser setup and charset step 1). A disagreement lets an attribute flood or an
 * `ENTITY` declaration slip past `TagBounds`/`PrologGuard`.
 */
class ParserGuardsTest {
    private val baseUrl = "https://example.com/feed.xml"

    private fun parse(bytes: ByteArray): ParseResult =
        XmlPullFeedParser.discovered().parse({ Buffer().write(bytes) }, null, baseUrl)

    private fun failedOf(xml: String): ParseResult.Failed = assertIs(resultOf(xml))

    private fun resultOf(xml: String): ParseResult = parse(xml.encodeToByteArray())

    /** `encoding=` inside a comment must not steer detection: the parser never sees it. */
    @Test
    fun encodingNamedInACommentIsNotHonoured() {
        // Decoded as UTF-16 the guards see garbage and the flood tag reaches the pull parser.
        val flood = (1..1_001).joinToString(" ") { "a$it=\"v\"" }
        val xml = "<!-- encoding=\"UTF-16LE\" --><rss version=\"2.0\" $flood><channel/></rss>"
        assertEquals(ParseFailure.MALFORMED, failedOf(xml).reason)
    }

    @Test
    fun commentDoesNotHideAnEntityDeclaration() {
        val xml =
            "<!-- encoding=\"UTF-16LE\" --><!DOCTYPE rss [<!ENTITY x \"boom\">]>" +
                "<rss version=\"2.0\"><channel/></rss>"
        assertEquals(ParseFailure.HOSTILE, failedOf(xml).reason)
    }

    /** kxml2 detects BOM-less UTF-32 from `<` padding; the guards must scan it in the same encoding. */
    @Test
    fun bomlessUtf32LeUsesTheParserSignature() {
        val xml = "<!DOCTYPE rss [<!ENTITY x \"boom\">]><rss version=\"2.0\"><channel/></rss>"
        val bytes = xml.toByteArray(Charsets.UTF_32LE)
        assertEquals(Charsets.UTF_32LE, EncodingSniff.sniff(bytes))
        assertEquals(ParseFailure.HOSTILE, assertIs<ParseResult.Failed>(parse(bytes)).reason)
    }

    @Test
    fun bomlessUtf32LeDocumentParses() {
        val xml = "<rss version=\"2.0\"><channel><title>U32</title></channel></rss>"
        val bytes = xml.toByteArray(Charsets.UTF_32LE)
        val feed = assertIs<ParseResult.Ok>(parse(bytes)).feed
        assertEquals("U32", feed.title)
    }

    /** kxml2 only sniffs BOM-less UTF-16 when the document starts with `<?`: `<r` is UTF-8 to it. */
    @Test
    fun bomlessUtf16WithoutDeclarationIsNotDetected() {
        val bytes = "<rss/>".toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_8, EncodingSniff.sniff(bytes))
    }

    /** A comment or PI inside the DOCTYPE internal subset keeps its own delimiters. */
    @Test
    fun commentInsideSubsetDoesNotHideTheRootTag() {
        val flood = (1..1_001).joinToString(" ") { "a$it=\"v\"" }
        val xml = "<!DOCTYPE rss [<!-- \" -->]><rss version=\"2.0\" $flood><channel/></rss>"
        assertEquals(ParseFailure.MALFORMED, failedOf(xml).reason)
    }

    @Test
    fun entityAfterCommentInsideSubsetIsHostile() {
        val xml =
            "<!DOCTYPE rss [<!-- \" --><!ENTITY x \"boom\">]>" +
                "<rss version=\"2.0\"><channel/></rss>"
        assertEquals(ParseFailure.HOSTILE, failedOf(xml).reason)
    }

    @Test
    fun entityAfterPiInsideSubsetIsHostile() {
        // The `"` inside the PI would open a quoted literal if PIs were not skipped wholesale.
        val xml =
            "<!DOCTYPE rss [<?p \" ?><!ENTITY y \"boom\">]>" +
                "<rss version=\"2.0\"><channel/></rss>"
        assertEquals(ParseFailure.HOSTILE, failedOf(xml).reason)
    }
}
