// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.opml.OpmlFailure
import ch.lkmc.neutrodyne.feeds.opml.OpmlLimits
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertEquals(OpmlFailure.TAG_TOO_LONG, (result as OpmlReadResult.Failed).failure)
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

    private companion object {
        /** Generous wall-clock bound; the design targets < 2 s per case, timeouts only catch hangs. */
        const val HOSTILE_TIMEOUT_MS = 60_000L

        const val DEEP_NESTING = 10_000
        const val FEED_FLOOD = 100_001
        const val OUTLINE_FLOOD = 250_000
        const val HUGE_ATTR = 10 * 1024 * 1024
        const val UTF16_UNIT_CHARS = 5
        const val GAP_FLOOD = 2 * 1024 * 1024

        /** Read-ahead slop for the early-termination assertion: bound + decoder buffers. */
        val EARLY_CUTOFF_BYTES = OpmlLimits().maxMarkupGapBytes + 256 * 1024

        val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    }
}
