// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File

/**
 * Golden-file comparison for `:feeds:jvm`'s tests, with the semantics of 09's `Goldens` helper
 * (`assertMatches`, canonical JSON with sorted keys and no explicit nulls, `-PupdateGoldens` rewriting
 * that CI refuses). Deviation 2026-10-06: `:core:testing` does not carry 09's helper yet, so this local
 * copy stands in until it lands; switch `FeedParserGoldenTest` to it then.
 */
object Goldens {
    private val canonicalJson =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            explicitNulls = false
        }

    /** The corpus tree: a plain data directory of `:feeds`, consumed through a test-resources srcDir. */
    val fixturesDir: File
        get() = File(System.getProperty("neutrodyne.rootDir")!!, "feeds/src/test/resources/feeds")

    fun fixture(name: String): File = File(fixturesDir, name)

    fun updateRequested(): Boolean =
        System.getProperty("neutrodyne.updateGoldens") == "true" &&
            System.getenv("CI") == null

    /** Pretty-printed JSON with object keys sorted recursively and explicit nulls dropped. */
    fun canonicalJson(element: JsonElement): String = canonicalJson.encodeToString(sortKeys(dropNulls(element)))

    /** Serialises [value] with the encoder the corpus uses and compares it with [name]. */
    fun <T> assertMatches(
        name: String,
        value: T,
        serialize: (T) -> JsonElement,
    ) {
        val goldenFile = fixture(name)
        val actual = canonicalJson(serialize(value)) + "\n"
        if (updateRequested()) {
            goldenFile.writeText(actual)
            return
        }
        check(goldenFile.isFile) { "missing golden file ${goldenFile.path}; run with -PupdateGoldens" }
        val expected = goldenFile.readText()
        check(actual == expected) {
            "golden mismatch for $name\n--- expected ---\n$expected\n--- actual ---\n$actual"
        }
    }

    private fun dropNulls(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(
                    element.entries
                        .filterNot { (_, v) -> v is JsonPrimitive && v.isString == false && v.content == "null" }
                        .associate { (k, v) -> k to dropNulls(v) },
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

            else -> {
                element
            }
        }
}
