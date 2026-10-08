// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 01 Licence screen + F3/F10 regression: the offline AboutLibraries pipeline must put the full
 * licence text on every library entry in the generated metadata (an SPDX-keyed `hash` in a manual
 * licence definition supplies it), and the bundled engine components must carry their version and
 * the `bundled` tag the Licences screen reads. Bundled components also carry their *own* upstream
 * notices — the licence texts of the engine entries are component-specific originals with the real
 * copyright holders, not the shared SPDX templates, which exist only to give discovered Gradle
 * dependencies licence content.
 */
@RunWith(RobolectricTestRunner::class)
class AboutLibrariesMetadataTest {
    @Test
    fun everyLibraryEntryResolvesToLicenceContent() {
        val libs = Libs.Builder().withJson(generatedJson().readText()).build()
        val byHash = libs.licenses.associateBy { it.hash }

        val missing =
            libs.libraries.flatMap { lib ->
                lib.licenses.mapNotNull { license ->
                    val content = byHash[license.hash]?.licenseContent ?: license.licenseContent
                    if (content.isNullOrBlank()) "${lib.uniqueId} -> ${license.hash}" else null
                }
            }
        assertTrue(
            "libraries without licence content in aboutlibraries.json: ${missing.joinToString()}",
            missing.isEmpty(),
        )
    }

    @Test
    fun bundledEngineEntriesCarryVersionAndTag() {
        val libs = Libs.Builder().withJson(generatedJson().readText()).build()
        val bundled = libs.libraries.filter { it.tag == "bundled" }

        val noVersion = bundled.filter { it.artifactVersion.isNullOrBlank() }
        assertTrue(
            "bundled entries without artifactVersion: ${noVersion.joinToString { it.name }}",
            noVersion.isEmpty(),
        )
        // The Public Suffix List entry ships in every build, engine or emergency.
        assertTrue(
            "expected the Public Suffix List entry among: ${bundled.joinToString { it.name }}",
            bundled.any { it.uniqueId == PSL_UNIQUE_ID },
        )
        if (!BuildConfig.YOUTUBE_ENGINE) return // emergency build: no engine entries
        assertTrue(
            "expected the CPython bundled entry among: ${bundled.joinToString { it.name }}",
            bundled.any { it.name.contains("CPython") },
        )
    }

    /**
     * M0 AC5/N8: OkHttp's compiled Public Suffix List (MPL-2.0 data) must show on the Licences
     * screen of the normal build *and* the emergency build — so the manual definition must sit in
     * both AboutLibraries roots, `config/libraries` and `config/engine/libraries`, identically.
     */
    @Test
    fun publicSuffixListDeclaredInBothConfigRoots() {
        val generated = Libs.Builder().withJson(generatedJson().readText()).build()
        val psl = generated.libraries.find { it.uniqueId == PSL_UNIQUE_ID }
        assertNotNull("no $PSL_UNIQUE_ID entry in generated aboutlibraries.json", psl)
        val licences = generated.licensesOf(psl!!)
        assertTrue(
            "PSL entry must carry the MPL-2.0 licence, got $licences",
            licences.any { it.spdxId == "MPL-2.0" || it.hash == "MPL-2.0" },
        )
        val mpl = licences.first { it.spdxId == "MPL-2.0" || it.hash == "MPL-2.0" }
        assertTrue(
            "MPL-2.0 licence text must be the full Mozilla text",
            mpl.licenseContent.orEmpty().contains("Mozilla Public License"),
        )
        assertTrue(
            "PSL entry must record the source data URL",
            psl.description.orEmpty().contains("publicsuffix.org"),
        )

        val moduleDir = if (File("config").isDirectory) File(".") else File("app")
        val bodies =
            listOf("config/libraries", "config/engine/libraries").map { dir ->
                val f = File(moduleDir, "$dir/$PSL_DEFINITION_FILE")
                assertTrue("missing $f", f.isFile)
                f.readText()
            }
        assertEquals(
            "the PSL definition must be identical in both AboutLibraries roots",
            bodies[0],
            bodies[1],
        )
    }

    /**
     * The bundled components' notices are their upstream originals: the CPython entry carries the
     * full licence including the incorporated-software appendix, and every bundled entry resolves
     * to a licence text with real copyright holders — never a licence template placeholder.
     */
    @Test
    fun bundledNoticesCarryUpstreamAttribution() {
        val libs = Libs.Builder().withJson(generatedJson().readText()).build()
        val bundled = libs.libraries.filter { it.tag == "bundled" }
        assertTrue("no bundled entries in generated metadata", bundled.isNotEmpty())

        for (lib in bundled) {
            for (license in libs.licensesOf(lib)) {
                val content = license.licenseContent.orEmpty()
                for (placeholder in PLACEHOLDER_MARKERS) {
                    assertFalse(
                        "${lib.uniqueId} -> ${license.hash} still contains the SPDX template " +
                            "placeholder '$placeholder'",
                        content.contains(placeholder),
                    )
                }
            }
        }

        if (BuildConfig.YOUTUBE_ENGINE) {
            val cpython = bundled.firstOrNull { it.name.contains("CPython") }
            assertNotNull("no CPython entry among bundled components", cpython)
            val cpythonText =
                libs.licensesOf(cpython!!).joinToString("\n") { it.licenseContent.orEmpty() }
            for (marker in CPYTHON_APPENDIX_MARKERS) {
                assertTrue(
                    "CPython licence text lacks the incorporated-software marker '$marker'",
                    cpythonText.contains(marker),
                )
            }

            // Each bundled component's own upstream notice must name its real copyright holder(s).
            for ((uniqueId, markers) in COMPONENT_ATTRIBUTIONS) {
                val lib = bundled.firstOrNull { it.uniqueId == uniqueId }
                assertNotNull("no bundled entry $uniqueId", lib)
                val text = libs.licensesOf(lib!!).joinToString("\n") { it.licenseContent.orEmpty() }
                for (marker in markers) {
                    assertTrue(
                        "$uniqueId licence text lacks attribution marker '$marker'",
                        text.contains(marker),
                    )
                }
            }
        }
    }

    private fun Libs.licensesOf(lib: Library): List<License> {
        val byHash = licenses.associateBy { it.hash }
        return lib.licenses.map { byHash[it.hash] ?: it }
    }

    private fun generatedJson(): File {
        val variants = listOf("debug", "release", "benchmarkRelease", "nonMinifiedRelease")
        for (variant in variants) {
            for (base in listOf("build", "app/build")) {
                val file = File("$base/generated/aboutLibraries/$variant/res/raw/aboutlibraries.json")
                if (file.isFile) return file
            }
        }
        fail(
            "no generated aboutlibraries.json under build/generated/aboutLibraries " +
                "(run a compile or exportLibraryDefinitions task first)",
        )
        error("unreachable")
    }

    private companion object {
        const val PSL_UNIQUE_ID = "neutrodyne:okhttp-public-suffix-list"
        const val PSL_DEFINITION_FILE = "okhttp-public-suffix-list.json"

        /** The "Licenses and Acknowledgements for Incorporated Software" appendix markers. */
        val CPYTHON_APPENDIX_MARKERS =
            listOf(
                "Licenses and Acknowledgements for Incorporated Software",
                "Makoto Matsumoto", // Mersenne Twister (_random)
                "Marek Majkowski", // SipHash24
                "David M. Gay", // strtod and dtoa
                "Don Owens", // cfuhash
                "Ma Lin", // pyzstd-derived Zstandard bindings
                "Stefan Krah", // libmpdec
            )

        val PLACEHOLDER_MARKERS = listOf("<copyright holders>", "<year>", "<owner>")

        /** Bundled component uniqueId -> markers its licence text must contain. */
        val COMPONENT_ATTRIBUTIONS =
            mapOf(
                "neutrodyne:libffi" to listOf("Anthony Green", "Red Hat"),
                "neutrodyne:expat" to listOf("Thai Open Source Software Center", "Expat maintainers"),
                "neutrodyne:mimalloc" to listOf("Microsoft Corporation", "Daan Leijen"),
                "neutrodyne:hacl-star" to listOf("INRIA", "HACL* Contributors"),
                "neutrodyne:mpdecimal" to listOf("Stefan Krah"),
                "neutrodyne:zstd" to listOf("Meta Platforms"),
                "neutrodyne:zlib" to listOf("Jean-loup Gailly", "Mark Adler"),
                "neutrodyne:xz" to listOf("public domain"),
                "neutrodyne:chaquopy" to listOf("Chaquo Ltd"),
                "neutrodyne:bzip2" to listOf("Julian"),
                "neutrodyne:sqlite" to listOf("disclaims copyright"),
                "neutrodyne:unicode-ucd" to listOf("Unicode, Inc"),
                "neutrodyne:okhttp-public-suffix-list" to listOf("Mozilla Public License"),
                // the third-party Python packages Chaquopy's bootstrap.imy carries (01 inventory)
                "neutrodyne:pyelftools" to listOf("public domain"),
                "neutrodyne:construct" to listOf("Tomer Filiba", "Corbin Simpson"),
                "neutrodyne:setuptools" to listOf("Python Packaging Authority"),
                "neutrodyne:jaraco-context" to listOf("Jason R. Coombs"),
                "neutrodyne:jaraco-functools" to listOf("Jason R. Coombs"),
                "neutrodyne:jaraco-text" to listOf("Jason R. Coombs"),
                "neutrodyne:zipp" to listOf("Jason R. Coombs"),
                "neutrodyne:more-itertools" to listOf("Erik Rose"),
                "neutrodyne:packaging" to listOf("Donald Stufft"),
                "neutrodyne:platformdirs" to listOf("platformdirs developers"),
                "neutrodyne:typing-extensions" to listOf("Python Software Foundation"),
            )
    }
}
