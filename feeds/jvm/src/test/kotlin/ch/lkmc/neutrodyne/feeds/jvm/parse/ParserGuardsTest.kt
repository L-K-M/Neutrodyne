// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.ParseFailure
import ch.lkmc.neutrodyne.feeds.parse.ParseLimits
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The pre-parse guards decide the document encoding exactly as the pull parser does — a BOM or a
 * byte signature the parser's `setInput` supports, or the leading XML declaration, never text inside
 * a comment (03 Parser setup and charset step 1). A disagreement lets an attribute flood or an
 * `ENTITY` declaration slip past `TagBounds`/`PrologGuard`.
 */
class ParserGuardsTest {
    private val baseUrl = "https://example.com/feed.xml"

    private companion object {
        /** Okio reads through one 8 KiB segment at a time: the bounded read may overshoot the cap by one. */
        const val SEGMENT = 8 * 1024L
    }

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

    /**
     * kxml2 keeps an ASCII declaration's raw bytes and decodes only the remainder with the declared
     * encoding. Decoding the whole stream that way instead pairs the body's first byte with the
     * declaration's last when the declaration is odd, phase-shifting every tag out of the guards'
     * view while the pull parser still sees a genuine UTF-16 document.
     */
    @Test
    fun declaredEncodingCannotPhaseShiftAnEntityPastTheGuard() {
        // 41 ASCII bytes: odd, so a whole-stream UTF-16LE decode misaligns the body.
        val declaration = "<?xml version=\"1.0\" encoding=\"utf-16le\"?>"
        assertEquals(41, declaration.length)
        val body = "<!DOCTYPE rss [<!ENTITY x \"boom\">]><rss version=\"2.0\"><channel/></rss>"
        val bytes = declaration.toByteArray(Charsets.US_ASCII) + body.toByteArray(Charsets.UTF_16LE)
        assertEquals(ParseFailure.HOSTILE, assertIs<ParseResult.Failed>(parse(bytes)).reason)
    }

    /** Same phase shift against the attribute bound: the flood must not reach the pull parser. */
    @Test
    fun declaredEncodingCannotPhaseShiftAnAttributeFlood() {
        val declaration = "<?xml version=\"1.0\" encoding=\"utf-16le\"?>"
        val flood = (1..1_001).joinToString(" ") { "a$it=\"v\"" }
        val body = "<rss version=\"2.0\" $flood><channel/></rss>"
        val bytes = declaration.toByteArray(Charsets.US_ASCII) + body.toByteArray(Charsets.UTF_16LE)
        assertEquals(ParseFailure.MALFORMED, assertIs<ParseResult.Failed>(parse(bytes)).reason)
    }

    /** The boundary also works forward: a declaration naming UTF-16 with a real UTF-16 body parses. */
    @Test
    fun declaredUtf16BodyStillParses() {
        val declaration = "<?xml version=\"1.0\" encoding=\"utf-16le\"?>"
        val body = "<rss version=\"2.0\"><channel><title>Enc</title></channel></rss>"
        val bytes = declaration.toByteArray(Charsets.US_ASCII) + body.toByteArray(Charsets.UTF_16LE)
        assertEquals("Enc", assertIs<ParseResult.Ok>(parse(bytes)).feed.title)
    }

    /**
     * `maxDocumentBytes` is enforced incrementally during the read: a source that never ends must
     * fail `HOSTILE` after delivering only about the cap — without the bound, `readByteArray()`
     * drains it until the heap dies.
     */
    @Test
    fun endlessSourceFailsBoundedWithoutDraining() {
        val cap = 64L
        val parser = XmlPullFeedParser.discovered(ParseLimits(maxDocumentBytes = cap.toInt()))
        val delivered = AtomicLong()
        val endless =
            object : okio.Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    delivered.addAndGet(byteCount)
                    sink.write(ByteArray(byteCount.toInt()) { 'x'.code.toByte() })
                    return byteCount
                }

                override fun timeout(): okio.Timeout = okio.Timeout.NONE

                override fun close() {}
            }
        val result = parser.parse({ endless }, null, baseUrl)
        assertEquals(ParseFailure.HOSTILE, assertIs<ParseResult.Failed>(result).reason)
        // One segment's worth past the cap at most — never the whole stream.
        assertTrue(delivered.get() <= cap + SEGMENT, "source delivered ${delivered.get()} bytes")
    }

    /** A finite document over the cap fails the same way; exactly the cap still reaches the parser. */
    @Test
    fun documentOverMaxBytesFailsHostile() {
        val parser = XmlPullFeedParser.discovered(ParseLimits(maxDocumentBytes = 64))
        val oversized = ByteArray(65) { 'x'.code.toByte() }
        assertEquals(
            ParseFailure.HOSTILE,
            assertIs<ParseResult.Failed>(parser.parse({ Buffer().write(oversized) }, null, baseUrl)).reason,
        )
        // Exactly at the cap the read succeeds and the document reaches the parser — here it parses.
        val exact = "<rss version=\"2.0\"><channel><title>abcde</title></channel></rss>"
        assertEquals(64, exact.encodeToByteArray().size)
        val result = parser.parse({ Buffer().write(exact.encodeToByteArray()) }, null, baseUrl)
        assertIs<ParseResult.Ok>(result)
    }

    /**
     * X2: kxml2's `parseDoctype` ignores `"` — only `'` quotes — and stops the DOCTYPE at the first
     * unquoted `>`. A `"…"` region therefore does not hide markup from the pull parser, and the tag
     * bound must see the same boundary or the embedded flood reaches it uncounted.
     */
    @Test
    fun doctypeDoubleQuoteCannotHideAnAttributeFlood() {
        val flood = (1..1_001).joinToString(" ") { "a$it=\"v\"" }
        val xml =
            "<!DOCTYPE rss SYSTEM \"><rss $flood><channel><title>Hidden</title></channel></rss>\">" +
                "<rss><channel/></rss>"
        assertEquals(ParseFailure.MALFORMED, failedOf(xml).reason)
    }

    /**
     * Review round 5: relaxed kxml2 ends an end tag after its name, whitespace and exactly one more
     * character (`</x !` closes `x`), so the guard must not skip to the next `>` past the start tag
     * that follows.
     */
    @Test
    fun malformedEndTagCannotHideAnAttributeFlood() {
        val flood = (1..1_001).joinToString(" ") { "a$it=\"1\"" }
        val xml = "<rss><channel><x></x !<item $flood/></channel></rss>"
        assertEquals(ParseFailure.MALFORMED, failedOf(xml).reason)
    }

    /** X2: relaxed kxml2 accepts a name starting with a digit; its attributes still get bounded. */
    @Test
    fun digitNamedTagIsBounded() {
        val flood = (1..1_001).joinToString(" ") { "a$it=\"v\"" }
        val xml = "<rss><channel><0 $flood/></channel></rss>"
        assertEquals(ParseFailure.MALFORMED, failedOf(xml).reason)
    }

    /** X2: an `<?xml ` declaration is processed by kxml2's attribute loop too — bound it as well. */
    @Test
    fun xmlDeclarationAttributesAreBounded() {
        val flood = (1..1_001).joinToString(" ") { "a$it=\"v\"" }
        val xml = "<?xml version=\"1.0\" $flood ?><rss><channel/></rss>"
        assertEquals(ParseFailure.MALFORMED, failedOf(xml).reason)
    }

    /**
     * X3: the charset-override pass runs the guards on the stream as that pass decodes it. Here the
     * oversized start tag exists only in the IBM037 view; before the fix the second pass ran
     * unguarded and won the heuristic (title null, CHARSET_REPARSED). Guarded, the pass fails and
     * the first pass' result stands.
     */
    @Test
    fun charsetOverridePassIsCheckedByTagBounds() {
        val flood = (1..1_001).joinToString(" ") { "a$it=\"v\"" }
        val bytes =
            "<rss><channel><title>Clean ".encodeToByteArray() +
                "<rss $flood><channel/></rss>".toByteArray(Charset.forName("IBM037")) +
                "</title></channel></rss>".encodeToByteArray()
        val result =
            XmlPullFeedParser
                .discovered()
                .parse({ Buffer().write(bytes) }, "IBM037", baseUrl)
        val feed = assertIs<ParseResult.Ok>(result).feed
        assertTrue(feed.title?.startsWith("Clean ") == true)
        assertTrue(feed.warnings.none { it.code == WarningCode.CHARSET_REPARSED })
    }

    /** X3: a `<!ENTITY` that exists only in the override decoding is still hostile there. */
    @Test
    fun charsetOverridePassIsCheckedByPrologGuard() {
        val bytes =
            "<rss><channel><title>Clean ".encodeToByteArray() +
                "<!DOCTYPE r [<!ENTITY x \"b\">]><r/>".toByteArray(Charset.forName("IBM037")) +
                "</title></channel></rss>".encodeToByteArray()
        val result =
            XmlPullFeedParser
                .discovered()
                .parse({ Buffer().write(bytes) }, "IBM037", baseUrl)
        val feed = assertIs<ParseResult.Ok>(result).feed
        assertTrue(feed.title?.startsWith("Clean ") == true)
        assertTrue(feed.warnings.none { it.code == WarningCode.CHARSET_REPARSED })
    }

    /** `setInput` fails when the declaration's `>` never comes: so does the parse. */
    @Test
    fun declarationWithoutCloseIsRejected() {
        assertIs<EncodingSniff.View.Rejected>(EncodingSniff.view("<?xml".toByteArray()))
        assertEquals(
            ParseFailure.MALFORMED,
            assertIs<ParseResult.Failed>(parse("<?xml".toByteArray())).reason,
        )
    }

    /** kxml2's 8192-char store buffer bounds the declaration scan; past it `setInput` throws. */
    @Test
    fun declarationClosingBeyondTheParserBufferIsRejected() {
        val bytes = ("<?xml " + "a".repeat(8_192) + ">").toByteArray()
        assertIs<EncodingSniff.View.Rejected>(EncodingSniff.view(bytes))
    }

    @Test
    fun unsupportedDeclaredEncodingIsRejected() {
        val bytes = "<?xml version=\"1.0\" encoding=\"utf-99\"?>".toByteArray()
        assertIs<EncodingSniff.View.Rejected>(EncodingSniff.view(bytes))
        assertEquals(ParseFailure.MALFORMED, assertIs<ParseResult.Failed>(parse(bytes)).reason)
    }

    /** The declaration bytes stay raw even when the body decodes in the declared charset. */
    @Test
    fun declaredEncodingDecodesOnlyTheRemainder() {
        val declaration = "<?xml version=\"1.0\" encoding=\"utf-16le\"?>"
        val body = "<r>x</r>"
        val bytes = declaration.toByteArray(Charsets.US_ASCII) + body.toByteArray(Charsets.UTF_16LE)
        val view = assertIs<EncodingSniff.View.Decoded>(EncodingSniff.view(bytes))
        assertEquals(declaration, view.prefix)
        assertEquals(declaration.length, view.dataStart)
        assertEquals(declaration + body, EncodingSniff.decode(bytes))
    }
}
