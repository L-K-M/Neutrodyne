// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.opml.OpmlFailure
import ch.lkmc.neutrodyne.feeds.opml.OpmlLimits
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import ch.lkmc.neutrodyne.feeds.opml.ParseMode
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * The XML leg of the hostile-input table (05 Testing): generated documents trip every cap and the
 * entity guard, each failing with its reason without `OutOfMemoryError` inside this task's 128 MB
 * heap (09 `mutationTest`). The ZIP legs live with the archive reader (M6 row); the 50 MiB payload
 * cap is the import layer's, not the reader's — a reader keeps streaming whatever it is given.
 */
class OpmlHostileInputTest {
    private val reader: OpmlReader = XmlPullOpmlReader.discovered()

    private fun read(bytes: ByteArray): OpmlReadResult = reader.read({ Buffer().write(bytes) })

    private fun failure(bytes: ByteArray): OpmlFailure {
        val result = read(bytes)
        assertIs<OpmlReadResult.Failed>(result)
        return result.failure
    }

    /** A billion-laughs DOCTYPE: the prolog guard rejects before any entity expands. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun internalEntityDeclarationIsRejected() {
        val doc =
            """<?xml version="1.0"?>
<!DOCTYPE opml [
  <!ENTITY lol "lollollollol">
  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
]>
<opml version="1.0"><body><outline type="rss" xmlUrl="https://x.example.com/&lol2;"/></body></opml>
""".encodeToByteArray()
        assertEquals(OpmlFailure.ENTITY_DECLARED, failure(doc))
    }

    /** An external entity declaration is the same failure; the file: URL is never fetched. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun externalEntityDeclarationIsRejected() {
        val doc =
            """<?xml version="1.0"?>
<!DOCTYPE opml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<opml version="1.0"><body><outline type="rss" xmlUrl="https://x.example.com/&xxe;"/></body></opml>
""".encodeToByteArray()
        assertEquals(OpmlFailure.ENTITY_DECLARED, failure(doc))
    }

    /** Nesting far past the depth cap: the walk trips at the cap, not on the parser stack. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun deepNestingIsRejected() {
        val sb = StringBuilder("<opml version=\"1.0\"><body>")
        repeat(DEEP_NESTING) { sb.append("<outline text=\"f\">") }
        repeat(DEEP_NESTING) { sb.append("</outline>") }
        sb.append("</body></opml>")
        assertEquals(OpmlFailure.TOO_DEEP, failure(sb.toString().encodeToByteArray()))
    }

    /** Past the feed cap: 100,001 feed outlines never materialise entries. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun feedFloodIsRejected() {
        val sb = StringBuilder("<opml version=\"1.0\"><body>")
        repeat(FEED_FLOOD) { sb.append("<outline type=\"rss\" xmlUrl=\"https://f.example.com/x\"/>") }
        sb.append("</body></opml>")
        assertEquals(OpmlFailure.TOO_MANY_FEEDS, failure(sb.toString().encodeToByteArray()))
    }

    /** Past the outline cap: 250,000 folder outlines, ignored or not, count the same. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun outlineFloodIsRejected() {
        val sb = StringBuilder("<opml version=\"1.0\"><body>")
        repeat(OUTLINE_FLOOD) { sb.append("<outline text=\"f\"/>") }
        sb.append("</body></opml>")
        assertEquals(OpmlFailure.TOO_MANY_OUTLINES, failure(sb.toString().encodeToByteArray()))
    }

    /**
     * A 10 MB attribute value: the markup-gap bound trips at 1 MiB of `<`-less bytes, long before
     * the value fills a parser buffer — and the rest of the source is never pulled.
     */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun hugeAttributeIsRejected() {
        val doc =
            "<opml version=\"1.0\"><body><outline type=\"rss\" xmlUrl=\"https://x.example.com/\" text=\"" +
                "a".repeat(HUGE_ATTR) + "\"/></body></opml>"
        val bytes = doc.encodeToByteArray()
        val buffer = Buffer().write(bytes)
        val result = reader.read({ buffer })
        assertEquals(OpmlFailure.TAG_TOO_LONG, assertIs<OpmlReadResult.Failed>(result).failure)
        assertTrue(
            buffer.size > bytes.size - EARLY_CUTOFF_BYTES,
            "guard tripped late: ${buffer.size} of ${bytes.size} bytes unread",
        )
    }

    /**
     * The same 10 MB attribute in UTF-16LE interleaved with CJK chars: no `<` byte pattern appears
     * inside the multi-byte units, so the gap bound is what trips — the aligned-`003C` check cannot
     * be spoofed by a `3C` byte inside another unit. The BOM is what lets kxml2 detect UTF-16 at
     * all (a bare `3C 00` head is ambiguous without the `?` of an XML declaration).
     */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun hugeAttributeUtf16IsRejected() {
        val value = "中a一丁㰀".repeat(HUGE_ATTR / UTF16_UNIT_CHARS)
        val doc =
            "<opml version=\"1.0\"><body><outline type=\"rss\" xmlUrl=\"https://x.example.com/\" text=\"" +
                value + "\"/></body></opml>"
        val bytes = BOM_UTF16LE + doc.toByteArray(Charsets.UTF_16LE)
        assertEquals(OpmlFailure.TAG_TOO_LONG, failure(bytes))
    }

    /** 2 MiB of element text without any `<`: the gap bound applies outside attributes too. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun hugeTextGapIsRejected() {
        val doc =
            "<opml version=\"1.0\"><body>" + "x".repeat(GAP_FLOOD) + "</body></opml>"
        assertEquals(OpmlFailure.TAG_TOO_LONG, failure(doc.encodeToByteArray()))
    }

    /**
     * A `<`-dense attribute value: kxml2 accepts `<` inside a quoted value and grows its text
     * buffer per character, so the 1 MiB bound must treat those `<`s as token payload, not as
     * markup that resets the gap.
     */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun ltDenseAttributeValueIsRejected() {
        val doc =
            "<opml version=\"1.0\"><body><outline type=\"rss\" xmlUrl=\"https://x.example.com/\" text=\"" +
                "<".repeat(GAP_FLOOD) + "\"/></body></opml>"
        assertEquals(OpmlFailure.TAG_TOO_LONG, failure(doc.encodeToByteArray()))
    }

    /** The same flood as comment text — kxml2 buffers the comment body the same way. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun ltDenseCommentIsRejected() {
        val doc =
            "<opml version=\"1.0\"><body><!--" + "<".repeat(GAP_FLOOD) + "--></body></opml>"
        assertEquals(OpmlFailure.TAG_TOO_LONG, failure(doc.encodeToByteArray()))
    }

    /** And as CDATA payload — `<` inside `]]>`-terminated text is payload too. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun ltDenseCdataIsRejected() {
        val doc =
            "<opml version=\"1.0\"><body><![CDATA[" + "<".repeat(GAP_FLOOD) + "]]></body></opml>"
        assertEquals(OpmlFailure.TAG_TOO_LONG, failure(doc.encodeToByteArray()))
    }

    /**
     * `<?xml` past the attribute bound: the guard trips inside `setInput`'s encoding sniff, where
     * kxml2 wraps stream failures — the cap violation must surface as `TAG_TOO_LONG`, not degrade
     * to the `MALFORMED` a relaxed pass would fall through from. The short names keep the decl
     * under kxml2's 8 KiB `srcBuf` sniff window so the count trips before that buffer does.
     */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun xmlDeclAttributeFloodIsRejected() {
        val sb = StringBuilder("<?xml ")
        repeat(OpmlLimits().maxTagAttributes + 1) { sb.append("a=\"v\" ") }
        sb.append("?><opml version=\"1.0\"/>")
        assertEquals(OpmlFailure.TAG_TOO_LONG, failure(sb.toString().encodeToByteArray()))
    }

    /** The root start tag gets the same attribute bound as any other tag. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun rootAttributeFloodIsRejected() {
        val sb = StringBuilder("<opml version=\"1.0\" ")
        repeat(OpmlLimits().maxTagAttributes + 1) { sb.append("a$it=\"x\" ") }
        sb.append("/>")
        assertEquals(OpmlFailure.TAG_TOO_LONG, failure(sb.toString().encodeToByteArray()))
    }

    /** A non-root tag over `maxTagAttributes` is rejected too (post-delivery check). */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun tagAttributeFloodIsRejected() {
        val sb = StringBuilder("<opml version=\"1.0\"><body ")
        repeat(OpmlLimits().maxTagAttributes + 1) { sb.append("a$it=\"x\" ") }
        sb.append("/></opml>")
        assertEquals(OpmlFailure.TAG_TOO_LONG, failure(sb.toString().encodeToByteArray()))
    }

    /**
     * `<!ENTITY` inside a comment is never flagged: the parser could not attribute it to a
     * declaration either, so the read falls through to the ordinary `NO_OUTLINES` for a document
     * that holds none.
     */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun entityInCommentIsNotFlagged() {
        val doc = "<!-- <!ENTITY x \"y\"> --><opml version=\"1.0\"/>"
        assertEquals(OpmlFailure.NO_OUTLINES, failure(doc.encodeToByteArray()))
    }

    /** Same for a `<!ENTITY` inside a CDATA section — payload, not a declaration. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun entityInCdataIsNotFlagged() {
        val doc = "<opml version=\"1.0\"><body><![CDATA[<!ENTITY x \"y\">]]></body></opml>"
        assertEquals(OpmlFailure.NO_OUTLINES, failure(doc.encodeToByteArray()))
    }

    /** Same for a `<!ENTITY` inside a processing instruction. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun entityInProcessingInstructionIsNotFlagged() {
        val doc = "<?pi <!ENTITY x \"y\"> ?><opml version=\"1.0\"/>"
        assertEquals(OpmlFailure.NO_OUTLINES, failure(doc.encodeToByteArray()))
    }

    /** A lowercase `<!entity` marker inside the declaration is still flagged — the match folds case. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun lowercaseEntityInDoctypeIsRejected() {
        val doc = "<!DOCTYPE opml [<!entity x \"y\">]><opml version=\"1.0\"/>"
        assertEquals(OpmlFailure.ENTITY_DECLARED, failure(doc.encodeToByteArray()))
    }

    /**
     * A lowercase `<!doctype` keyword fails the strict leg at kxml2's required `DOCTYPE` literal
     * (MALFORMED), but the relaxed leg pulls the declaration body through the guarded stream
     * before failing the same way — the hidden `<!entity` trips `ENTITY_DECLARED` there, and
     * would in salvage too. Terminal either way: the entity cannot expand.
     */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun lowercaseDoctypeEntityIsRejected() {
        val doc = "<!doctype opml [<!entity x \"y\">]><opml version=\"1.0\"/>"
        assertEquals(OpmlFailure.ENTITY_DECLARED, failure(doc.encodeToByteArray()))
    }

    /**
     * `&`-dense attribute values whose only `;` sits at the very end: the entity `;` search
     * must stay inside the `MAX_ENTITY_CHARS` window; an unbounded `indexOf` rescans the
     * value tail per `&`, quadratic CPU inside the 64 KiB tag cap (~60 ms per outline).
     * Timed against a same-size `&`-free baseline instead of a wall-clock budget, so machine
     * load slows both runs alike: bounded scans stay near 1x, an unbounded one reaches ~6x.
     * The `&#xZZZZ;` fails both pull legs unconditionally, so the salvage scanner is under test.
     */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun ampDenseSalvageValuesStayLinear() {
        val baseline = timedSalvage("a".repeat(AMP_RUN))
        val dense = timedSalvage("&".repeat(AMP_RUN) + ";")

        assertTrue(
            dense < baseline * AMP_DENSE_MAX_RATIO,
            "dense $dense vs baseline $baseline ratio=${dense / baseline}",
        )
    }

    /** Reads [AMP_DENSE_TAGS] outlines carrying [value] and returns the salvage read's duration. */
    private fun timedSalvage(value: String): Duration {
        val doc =
            buildString {
                append("<opml version=\"1.0\"><body>")
                repeat(AMP_DENSE_TAGS) {
                    append("<outline type=\"rss\" xmlUrl=\"https://x.example.com/\" text=\"")
                    append(value)
                    append("\" note=\"&#xZZZZ;\"/>")
                }
                append("</body></opml>")
            }
        val bytes = doc.encodeToByteArray()

        val (result, elapsed) = measureTimedValue { read(bytes) }
        val ok = assertIs<OpmlReadResult.Ok>(result)
        assertEquals(ParseMode.SALVAGE, ok.document.mode)
        return elapsed
    }

    /** The entity scan sees through UTF-16 units, not only single-byte text. */
    @Test(timeout = HOSTILE_TIMEOUT_MS)
    fun entityInUtf16IsRejected() {
        val doc = "<!DOCTYPE opml [<!ENTITY x \"y\">]><opml version=\"1.0\"/>"
        assertEquals(OpmlFailure.ENTITY_DECLARED, failure(BOM_UTF16LE + doc.toByteArray(Charsets.UTF_16LE)))
        assertEquals(OpmlFailure.ENTITY_DECLARED, failure(BOM_UTF16BE + doc.toByteArray(Charsets.UTF_16BE)))
    }

    private companion object {
        /** Generous wall-clock bound; the design targets < 2 s per case, timeouts only catch hangs. */
        const val HOSTILE_TIMEOUT_MS = 60_000L

        /**
         * The `&`-dense case sizes so the unbounded entity scan exceeds the ratio while the
         * bounded one stays near its baseline: ~60 KiB of `&`s per outline is the most
         * quadratic work the 64 KiB tag cap admits, a few hundred of them stay small enough
         * to build and hold in this task's 128 MB heap.
         */
        const val AMP_RUN = 60_000
        const val AMP_DENSE_TAGS = 300
        const val AMP_DENSE_MAX_RATIO = 3

        const val DEEP_NESTING = 10_000
        const val FEED_FLOOD = 100_001
        const val OUTLINE_FLOOD = 250_000
        const val HUGE_ATTR = 10 * 1024 * 1024
        const val UTF16_UNIT_CHARS = 5
        const val GAP_FLOOD = 2 * 1024 * 1024

        /** Read-ahead slop for the early-termination assertion: bound + decoder buffers. */
        val EARLY_CUTOFF_BYTES = OpmlLimits().maxMarkupGapBytes + 256 * 1024

        val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }
}
