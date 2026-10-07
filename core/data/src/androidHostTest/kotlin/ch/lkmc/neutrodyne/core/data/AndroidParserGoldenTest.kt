// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import android.util.Xml
import ch.lkmc.neutrodyne.feeds.jvm.parse.PullParserFactory
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Golden corpus leg b (03 Testing): every fixture under `feeds/src/test/resources/feeds/` parses
 * on AOSP's `XmlPullParser` — reached exactly as `AndroidDataBindings` wires it — and matches the
 * same `<name>.golden.json` the kxml2 leg uses. Robolectric runs the real framework
 * `Xml.newPullParser()` (KXmlParser), so kxml2/AOSP divergences surface here.
 *
 * Deviation 2026-10-07: 09's shared `Goldens` helper is still a `:feeds:jvm`-local copy, so the
 * canonical-JSON comparison below duplicates it; the golden files themselves stay single-sourced
 * and this leg never writes them (`updateGoldens` belongs to `:feeds:jvm:test`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidParserGoldenTest {
    private val parser = XmlPullFeedParser(PullParserFactory { Xml.newPullParser() })

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
    fun `every fixture matches its golden on the platform parser`() {
        val fixtures =
            FIXTURES_DIR
                .listFiles { file -> file.name.endsWith(".xml") }
                .orEmpty()
                .sortedBy { it.name }

        assertThat(fixtures).isNotEmpty()
        for (fixture in fixtures) {
            val name = fixture.name.removeSuffix(".xml")
            val bytes = fixture.readBytes()
            val result = parser.parse({ Buffer().write(bytes) }, null, "https://example.com/feed.xml")
            val goldenFile = File(FIXTURES_DIR, "$name.golden.json")
            assertWithMessage("missing golden for $name").that(goldenFile.isFile).isTrue()
            assertWithMessage("golden mismatch for $name")
                .that(canonicalJson(serializable(result)) + "\n")
                .isEqualTo(goldenFile.readText())
        }
    }

    private companion object {
        /** The corpus tree, via the `neutrodyne.rootDir` test system property (same as leg a). */
        val FIXTURES_DIR: File =
            File(System.getProperty("neutrodyne.rootDir")!!, "feeds/src/test/resources/feeds")

        val CANONICAL_JSON =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
                explicitNulls = false
            }

        /** Pretty-printed JSON with object keys sorted recursively and explicit nulls dropped. */
        fun canonicalJson(element: JsonElement): String =
            CANONICAL_JSON.encodeToString(sortKeys(dropNulls(element)))

        private fun dropNulls(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject -> {
                    JsonObject(
                        element.entries
                            .filterNot { (_, v) -> v is JsonPrimitive && v.isString == false && v.content == "null" }
                            .associate { (k, v) -> k to dropNulls(v) },
                    )
                }

                is JsonArray -> {
                    JsonArray(
                        element
                            .filterNot { it is JsonPrimitive && it.isString == false && it.content == "null" }
                            .map(::dropNulls),
                    )
                }

                else -> {
                    element
                }
            }

        private fun sortKeys(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject -> {
                    JsonObject(
                        element.entries.associate { (k, v) -> k to sortKeys(v) }.toSortedMap(),
                    )
                }

                is JsonArray -> {
                    JsonArray(element.map(::sortKeys))
                }

                else -> {
                    element
                }
            }
    }
}
