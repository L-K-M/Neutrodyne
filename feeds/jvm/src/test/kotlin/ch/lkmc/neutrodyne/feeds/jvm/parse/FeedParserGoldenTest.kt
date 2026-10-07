// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.jvm.Goldens
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseFailure
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okio.Buffer
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The golden corpus (03 Golden corpus): every fixture under `feeds/src/test/resources/feeds/` parses on
 * kxml2 — the desktop's run-time parser, through `PullParserFactory.Discovered` — and matches its
 * `<name>.golden.json`. `./gradlew :feeds:jvm:test -PupdateGoldens` rewrites the goldens locally
 * (refused on CI).
 */
class FeedParserGoldenTest {
    private val parser: FeedParser = XmlPullFeedParser.discovered()

    private val baseUrl = "https://example.com/feed.xml"

    private val json = Json { explicitNulls = false }

    private val serializable: (ParseResult) -> JsonElement = { result ->
        when (result) {
            is ParseResult.Ok -> {
                json.encodeToJsonElement(result.feed)
            }

            is ParseResult.Failed -> {
                // Failures pin only the reason; details carry parser messages and stay unpinned.
                JsonObject(mapOf("failed" to JsonPrimitive(result.reason.name)))
            }
        }
    }

    @Test
    fun everyFixtureMatchesItsGolden() {
        val fixtures = fixtures()
        check(fixtures.isNotEmpty()) { "golden corpus not found under ${Goldens.fixturesDir}" }
        for (fixture in fixtures) {
            parseAndCompare(fixture)
        }
    }

    /**
     * 03 Threading model's two concurrent parses on ONE instance, over the whole corpus: a parse
     * with mutable state at instance scope would leak namespace bindings, counters or warnings
     * between interleaved documents and break the golden match.
     */
    @Test(timeout = 120_000)
    fun corpusParsesConcurrentlyOnOneInstance() {
        val fixtures = fixtures()
        check(fixtures.isNotEmpty()) { "golden corpus not found under ${Goldens.fixturesDir}" }

        val shared: FeedParser = XmlPullFeedParser.discovered()
        val pool = Executors.newFixedThreadPool(STRESS_THREADS)
        try {
            val jobs = mutableListOf<Pair<File, Future<ParseResult>>>()
            repeat(STRESS_ROUNDS) {
                for (fixture in fixtures) {
                    jobs +=
                        fixture to
                        pool.submit<ParseResult> {
                            shared.parse({ Buffer().write(fixture.readBytes()) }, null, baseUrl)
                        }
                }
            }
            for ((fixture, future) in jobs) {
                compareWithGolden(fixture, future.get(JOB_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fixtures(): List<File> =
        Goldens.fixturesDir
            .listFiles { file -> file.name.endsWith(".xml") }
            .orEmpty()
            .sortedBy { it.name }

    private fun parseAndCompare(fixture: File) {
        val result = parser.parse({ Buffer().write(fixture.readBytes()) }, null, baseUrl)
        compareWithGolden(fixture, result)
    }

    private fun compareWithGolden(
        fixture: File,
        result: ParseResult,
    ) {
        val name = fixture.name.removeSuffix(".xml")
        Goldens.assertMatches("$name.golden.json", result, serializable)
    }

    private companion object {
        const val STRESS_THREADS = 4
        const val STRESS_ROUNDS = 2
        const val JOB_TIMEOUT_SECONDS = 60L
    }
}
