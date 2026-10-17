// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.jvm.Goldens
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okio.Buffer
import org.junit.Test

/**
 * The OPML golden corpus (05 Testing): every fixture under `feeds/src/test/resources/opml/` reads
 * through the strict → relaxed → salvage cascade on kxml2 — the desktop's run-time parser, through
 * `PullParserFactory.Discovered` — and matches its `<name>.golden.json`, pinning which pass
 * answered (`mode`) alongside the decoded entries. Strict is far more tolerant than the spec's
 * error list (EOF inside elements, control bytes, undeclared entities all pass — entities drop
 * silently); relaxed adds bare `&`, unquoted/single-quoted attributes, HTML entities, in-tag `<`
 * and mid-tag EOF; salvage takes what still throws (malformed numeric references among them).
 * `./gradlew :feeds:jvm:test -PupdateGoldens` rewrites the goldens locally (refused on CI).
 */
class OpmlReaderGoldenTest {
    private val reader: OpmlReader = XmlPullOpmlReader.discovered()

    private val json = Json { explicitNulls = false }

    private val serializable: (OpmlReadResult) -> JsonElement = { result ->
        when (result) {
            is OpmlReadResult.Ok -> {
                json.encodeToJsonElement(result.document)
            }

            is OpmlReadResult.Failed -> {
                // Failures pin only the reason; details carry parser messages and stay unpinned.
                JsonObject(mapOf("failed" to JsonPrimitive(result.failure.name)))
            }
        }
    }

    @Test
    fun everyFixtureMatchesItsGolden() {
        for (fixture in Goldens.fixtures(AREA, EXTENSION)) {
            val result = reader.read({ Buffer().write(fixture.readBytes()) })
            val name = fixture.name.removeSuffix(EXTENSION)
            Goldens.assertMatches("$name.golden.json", result, serializable, AREA)
        }
    }

    private companion object {
        const val AREA = "opml"
        const val EXTENSION = ".opml"
    }
}
