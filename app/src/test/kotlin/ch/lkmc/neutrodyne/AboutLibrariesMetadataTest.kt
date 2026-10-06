// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import com.mikepenz.aboutlibraries.Libs
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
 * the `bundled` tag the Licences screen reads.
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

        if (bundled.isEmpty()) return // emergency build (-Pneutrodyne.youtubeEngine=false)

        val noVersion = bundled.filter { it.artifactVersion.isNullOrBlank() }
        assertTrue(
            "bundled entries without artifactVersion: ${noVersion.joinToString { it.name }}",
            noVersion.isEmpty(),
        )
        assertTrue(
            "expected the CPython bundled entry among: ${bundled.joinToString { it.name }}",
            bundled.any { it.name.contains("CPython") },
        )
    }

    /** The unit-test task runs after resource generation, so a variant's JSON exists. */
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
}
