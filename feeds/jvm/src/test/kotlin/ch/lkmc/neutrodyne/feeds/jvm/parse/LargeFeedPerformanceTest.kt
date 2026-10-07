// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.jvm.Goldens
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
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
        assertTrue(best < BUDGET_MS, "parsing took $best ms (budget $BUDGET_MS ms, target 1 s)")
        if (System.getProperty("neutrodyne.tightPerf") == "true") {
            assertTrue(best < TIGHT_BUDGET_MS, "parsing took $best ms (strict budget $TIGHT_BUDGET_MS ms)")
        }
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
    }
}
