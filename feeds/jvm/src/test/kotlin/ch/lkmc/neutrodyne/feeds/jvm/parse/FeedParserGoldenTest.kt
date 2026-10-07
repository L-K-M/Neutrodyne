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

/**
 * The golden corpus (03 Golden corpus): every fixture under `feeds/src/test/resources/feeds/` parses on
 * kxml2 — the desktop's run-time parser, through `PullParserFactory.Discovered` — and matches its
 * `<name>.golden.json`. `./gradlew :feeds:jvm:test -PupdateGoldens` rewrites the goldens locally
 * (refused on CI).
 */
class FeedParserGoldenTest {
    private val parser: FeedParser = XmlPullFeedParser.discovered()

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
        val fixtures =
            Goldens.fixturesDir
                .listFiles { file -> file.name.endsWith(".xml") }
                .orEmpty()
                .sortedBy { it.name }

        check(fixtures.isNotEmpty()) { "golden corpus not found under ${Goldens.fixturesDir}" }
        for (fixture in fixtures) {
            parseAndCompare(fixture)
        }
    }

    private fun parseAndCompare(fixture: File) {
        val name = fixture.name.removeSuffix(".xml")
        val bytes = fixture.readBytes()
        val result = parser.parse({ Buffer().write(bytes) }, null, "https://example.com/feed.xml")
        Goldens.assertMatches("$name.golden.json", result, serializable)
    }
}
