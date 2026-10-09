// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/**
 * Golden corpus leg c (03 Testing): every fixture of `feeds/src/test/resources/feeds/` — packaged
 * into this test APK's assets under `feeds/` — parses on the device's real AOSP `XmlPullParser`
 * (`Xml.newPullParser()`, exactly what `AndroidDataBindings` injects in production) and matches the
 * same `<name>.golden.json` the kxml2 leg (a) and the Robolectric leg (b) pin. The parser is
 * shared `:feeds:jvm` code with its guards unchanged, so a kxml2/AOSP divergence in the wild
 * surfaces here as a golden mismatch.
 *
 * The class deliberately carries no `Nightly`/`ReleaseSmoke` annotation: `-Pneutrodyne.testScope=ci`
 * excludes only those two, so an unannotated test runs in the `ci` managed-device group
 * (`ciGroupDebugAndroidTest`, API 26 + 36) — 03's "once per main run" leg.
 *
 * Deviation 2026-10-09: 09's shared `Goldens` helper still exists only as local copies
 * (`:feeds:jvm` and `:core:data` `androidHostTest`), so the canonical-JSON comparison duplicates it
 * a third time; the golden bytes stay single-sourced and this leg never writes them. A device has
 * no checkout, so `neutrodyne.rootDir` cannot work here — the corpus ships as androidTest assets
 * (`app/build.gradle.kts`, `androidTest` source set only).
 */
@RunWith(AndroidJUnit4::class)
class AndroidParserGoldenInstrumentedTest {
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
    fun everyFixtureMatchesItsGoldenOnDevice() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val packaged = assets.list(FIXTURES_DIR).orEmpty().toSet()
        val fixtures = packaged.filter { it.endsWith(".xml") }.sorted()

        // Guards the asset packaging itself: below M1 acceptance's corpus minimum the androidTest
        // assets srcDir regressed — this is not "the corpus got smaller".
        assertThat(fixtures.size).isAtLeast(MIN_FIXTURES)
        for (fixture in fixtures) {
            val name = fixture.removeSuffix(".xml")
            val bytes = assets.open("$FIXTURES_DIR/$fixture").use { it.readBytes() }
            val result = parser.parse({ Buffer().write(bytes) }, null, BASE_URL)
            val goldenAsset = "$name.golden.json"
            assertWithMessage("missing golden for $name").that(goldenAsset in packaged).isTrue()
            val expected =
                assets.open("$FIXTURES_DIR/$goldenAsset").use { it.readBytes().decodeToString() }
            assertWithMessage("golden mismatch for $name")
                .that(canonicalJson(serializable(result)) + "\n")
                .isEqualTo(expected)
        }
    }

    private companion object {
        /** The corpus tree inside the test APK's assets (see `app/build.gradle.kts`). */
        const val FIXTURES_DIR = "feeds"
        const val BASE_URL = "https://example.com/feed.xml"

        /** M1 acceptance 1's corpus minimum (03 Golden corpus); the tree currently holds 70. */
        const val MIN_FIXTURES = 40

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
