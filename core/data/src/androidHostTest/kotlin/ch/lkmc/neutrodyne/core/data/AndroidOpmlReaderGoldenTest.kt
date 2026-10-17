// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import android.util.Xml
import ch.lkmc.neutrodyne.feeds.jvm.opml.XmlPullOpmlReader
import ch.lkmc.neutrodyne.feeds.jvm.parse.PullParserFactory
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
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
 * Golden corpus leg b (05 Testing): every fixture under `feeds/src/test/resources/opml/` reads on
 * AOSP's `XmlPullParser` — reached exactly as `AndroidDataBindings` wires it — and matches the same
 * `<name>.golden.json` the kxml2 leg uses. Robolectric runs the real framework
 * `Xml.newPullParser()` (KXmlParser), so kxml2/AOSP divergences surface here: leading whitespace,
 * `<?xml` placement and DOCDECL handling are the ones that matter.
 *
 * As in `AndroidParserGoldenTest`, the canonical-JSON comparison duplicates the `:feeds:jvm`-local
 * `Goldens` helper; the golden files stay single-sourced and this leg never writes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidOpmlReaderGoldenTest {
    private val reader: OpmlReader = XmlPullOpmlReader(PullParserFactory { Xml.newPullParser() })

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
    fun `every fixture matches its golden on the platform parser`() {
        val fixtures =
            FIXTURES_DIR
                .listFiles { file -> file.name.endsWith(".opml") }
                .orEmpty()
                .sortedBy { it.name }

        assertThat(fixtures).isNotEmpty()
        for (fixture in fixtures) {
            val name = fixture.name.removeSuffix(".opml")
            val result = reader.read({ Buffer().write(fixture.readBytes()) })
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
            File(
                checkNotNull(System.getProperty("neutrodyne.rootDir")) {
                    "neutrodyne.rootDir is unset; run tests through Gradle (TestConventions sets it)"
                },
                "feeds/src/test/resources/opml",
            )

        val CANONICAL_JSON =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
                explicitNulls = false
            }

        /** Pretty-printed JSON with object keys sorted recursively and explicit nulls dropped. */
        fun canonicalJson(element: JsonElement): String = CANONICAL_JSON.encodeToString(sortKeys(dropNulls(element)))

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
