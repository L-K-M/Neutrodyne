// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    private val rootDir: File
        get() =
            File(
                checkNotNull(System.getProperty("neutrodyne.rootDir")) {
                    "neutrodyne.rootDir is unset; run tests through Gradle (TestConventions sets it)"
                },
            )

    /** The corpus tree: a plain data directory of `:feeds`, consumed through a test-resources srcDir. */
    val fixturesDir: File
        get() = File(rootDir, "feeds/src/test/resources/feeds")

    /** The OPML writer goldens under `feeds/src/test/resources/opml/writer/`. */
    val opmlWriterDir: File
        get() = File(rootDir, "feeds/src/test/resources/opml/writer")

    fun fixture(name: String): File = File(fixturesDir, name)

    /** Every corpus fixture, sorted by name; fails when the corpus tree is missing or empty. */
    fun fixtures(): List<File> {
        val files = fixturesDir.listFiles { file -> file.name.endsWith(".xml") }
        checkNotNull(files) { "golden corpus directory not found: ${fixturesDir.path}" }
        check(files.isNotEmpty()) { "golden corpus is empty under ${fixturesDir.path}" }
        return files.sortedBy { it.name }
    }

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
        // `actual` is generated LF-only; read back LF too (.gitattributes pins eol=lf, but a
        // checkout that overrode attributes locally must still compare equal).
        val expected = goldenFile.readText().replace("\r\n", "\n")
        check(actual == expected) {
            "golden mismatch for $name\n--- expected ---\n$expected\n--- actual ---\n$actual"
        }
    }

    /**
     * Text (non-JSON) golden comparison for the OPML writer corpus, with [assertMatches]'s update and
     * line-ending semantics. Reads back LF-only, like [assertMatches].
     */
    fun assertTextMatches(
        name: String,
        actual: String,
    ) {
        val goldenFile = File(opmlWriterDir, name)
        if (updateRequested()) {
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(actual)
            return
        }
        check(goldenFile.isFile) { "missing golden file ${goldenFile.path}; run with -PupdateGoldens" }
        val expected = goldenFile.readText().replace("\r\n", "\n")
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
