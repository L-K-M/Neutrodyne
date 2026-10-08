// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.jvm.Goldens
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PLAN M1 AC1: the 831-item, ~3.5 MB fixture parses in < 1 s on the JVM. The default gate keeps a
 * generous 5 s margin (five times the budget) because CI runners vary; the tight 1 s figure is
 * enforced by `-PtightPerf` on a developer machine or the reference laptop of PO-43, which is how
 * M1 AC1's "< 1 s" is verified (03 Golden corpus).
 */
class LargeFeedPerformanceTest {
    @Test
    fun largeFixtureParsesQuickly() {
        val parser = XmlPullFeedParser(PullParserFactory.Discovered)
        val bytes = Goldens.fixture("large-831-items.xml").readBytes()
        assertTrue(bytes.size > 3_000_000, "fixture must stay ~3.5 MB, was ${bytes.size}")

        // Warm-up: JIT and file-cache effects should not eat the budget.
        val warm = parse(parser, bytes)
        assertEquals(EXPECTED_ITEMS, warm.feed.items.size, "fixture must carry $EXPECTED_ITEMS items")

        val best = (1..3).map { timed(parser, bytes) }.min()
        assertWithinBudget(best, tight = System.getProperty("neutrodyne.tightPerf") == "true")
    }

    /** The strict 1 s gate must actually reject a slow parse — a 2 s run passes loose, fails tight. */
    @Test
    fun tightGateRejectsSlowParse() {
        assertWithinBudget(2_000, tight = false)
        assertFailsWith<AssertionError> { assertWithinBudget(2_000, tight = true) }
    }

    /**
     * Media RSS `isDefault` entries merge ahead of the element's other `media:content` entries in
     * document order (03 Field mapping) — and in linear time: inserting every default at index 0
     * shifts the enclosure list once per default, so 4× the input would cost ~16× the time.
     */
    @Test
    fun mediaIsDefaultMergeIsLinear() {
        val parser = XmlPullFeedParser(PullParserFactory.Discovered)
        // Warm-up keeps JIT effects out of both measurements.
        timed(parser, mediaDefaultDoc(1_000))
        val small = timed(parser, mediaDefaultDoc(DEFAULTS_SMALL))
        val large = timed(parser, mediaDefaultDoc(DEFAULTS_SMALL * 4))
        assertTrue(
            large < small * QUADRATIC_SLACK,
            "media defaults took $small ms for $DEFAULTS_SMALL, $large ms for 4x — quadratic would be ~16x",
        )
    }

    /**
     * X4: an unrecognized inherited namespace URI is looked up on every element — lowercasing it
     * per lookup costs Θ(uri length × elements). The comparison must be length-aware instead, so a
     * ~562 KB document stays proportional to its size.
     */
    @Test(timeout = 20_000)
    fun inheritedNamespaceUriLookupIsLinear() {
        val uri = "urn:" + "A".repeat(262_144)
        val xml =
            "<rss version=\"2.0\" xmlns:x=\"$uri\"><channel>" +
                "<x:z/>".repeat(50_000) +
                "</channel></rss>"
        parse(XmlPullFeedParser(PullParserFactory.Discovered), xml.encodeToByteArray())
    }

    /** One item carrying [count] `media:content` entries, all `isDefault`, then one non-default. */
    private fun mediaDefaultDoc(count: Int): ByteArray {
        val head =
            "<rss version=\"2.0\" xmlns:media=\"http://search.yahoo.com/mrss/\">" +
                "<channel><item><guid>m</guid>"
        val tail =
            "<media:content url=\"https://cdn.example.com/rest.mp3\" type=\"audio/mpeg\"/>" +
                "</item></channel></rss>"
        return buildString(head.length + count * 110 + tail.length) {
            append(head)
            for (i in 1..count) {
                append(
                    "<media:content url=\"https://cdn.example.com/d$i.mp3\" type=\"audio/mpeg\" isDefault=\"true\"/>",
                )
            }
            append(tail)
        }.encodeToByteArray()
    }

    private fun timed(
        parser: XmlPullFeedParser,
        bytes: ByteArray,
    ): Long {
        val start = System.nanoTime()
        parse(parser, bytes)
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun parse(
        parser: XmlPullFeedParser,
        bytes: ByteArray,
    ): ParseResult.Ok =
        parser.parse({ Buffer().write(bytes) }, null, "https://example.com/feed.xml")
            as ParseResult.Ok

    private companion object {
        const val BUDGET_MS = 5_000L
        const val TIGHT_BUDGET_MS = 1_000L
        const val EXPECTED_ITEMS = 831

        /** A 4× input may cost at most 8× the time — twice the linear ratio, half the quadratic. */
        const val QUADRATIC_SLACK = 8L
        const val DEFAULTS_SMALL = 25_000

        fun assertWithinBudget(
            bestMs: Long,
            tight: Boolean,
        ) {
            assertTrue(bestMs < BUDGET_MS, "parsing took $bestMs ms (budget $BUDGET_MS ms, target 1 s)")
            if (tight) {
                assertTrue(bestMs < TIGHT_BUDGET_MS, "parsing took $bestMs ms (strict budget $TIGHT_BUDGET_MS ms)")
            }
        }
    }
}
