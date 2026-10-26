// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.ui

import ch.lkmc.neutrodyne.feature.settings.LicencesSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The desktop [LicencesSource] against the build's real generated `aboutlibraries.json` (01
 * AboutLibraries): the resource is on the classpath, parses, and carries the manual OpenJDK
 * runtime entry under the `bundled` tag (PLAN M0 AC5).
 */
class DesktopLicencesSourceTest {
    @Test
    fun `load reads the generated json with the OpenJDK runtime entry`() =
        runBlocking {
            val libs = DesktopLicencesSource().load()

            assertThat(libs).isNotNull()
            assertThat(libs!!.libraries).isNotEmpty()

            val openJdk = libs.libraries.single { it.uniqueId == "neutrodyne:openjdk-runtime" }
            assertThat(openJdk.name).isEqualTo("OpenJDK runtime (Temurin 25)")
            assertThat(openJdk.tag).isEqualTo("bundled")
            assertThat(openJdk.artifactVersion).contains("Temurin")
            assertThat(openJdk.description).contains("openjdk-25.0.4.1+1-temurin-sources.tar.gz")

            // The repair of 15.2.0's dangling custom-licence reference (see the source).
            val licence = openJdk.licenses.single()
            assertThat(licence.name).contains("Classpath Exception")
            assertThat(licence.licenseContent).contains("GNU GENERAL PUBLIC LICENSE, VERSION 2")
            assertThat(licence.licenseContent).contains("CLASSPATH EXCEPTION, Version 2.0")
            assertThat(licence.licenseContent).contains("GCC RUNTIME LIBRARY EXCEPTION")
        }

    /**
     * Every desktop entry resolves to licence text, MIT/BSD-style families carry the component's
     * own text with its copyright line instead of only the SPDX template, and bundled entries (the
     * libraries Skiko and JNA link statically) carry a version (01 AboutLibraries).
     */
    @Test
    fun `every library carries its own licence texts`() =
        runBlocking<Unit> {
            val libs = DesktopLicencesSource().load()!!

            val withoutText =
                libs.libraries.flatMap { lib ->
                    if (lib.licenses.isEmpty()) {
                        listOf("${lib.uniqueId} -> no licence")
                    } else {
                        lib.licenses
                            .filter {
                                it.licenseContent.isNullOrBlank()
                            }.map { "${lib.uniqueId} -> ${it.hash}" }
                    }
                }
            assertThat(withoutText).isEmpty()

            val templateOnly =
                libs.libraries.flatMap { lib ->
                    lib.licenses
                        .groupBy { it.spdxId ?: it.hash }
                        .filter { (family, texts) ->
                            family in COPYRIGHT_FAMILIES &&
                                texts.all { text -> PLACEHOLDERS.any { text.licenseContent.orEmpty().contains(it) } }
                        }.keys
                        .map { "${lib.uniqueId} -> $it" }
                }
            assertThat(templateOnly).isEmpty()

            val bundledWithoutVersion =
                libs.libraries.filter { it.tag == "bundled" && it.artifactVersion.isNullOrBlank() }.map { it.uniqueId }
            assertThat(bundledWithoutVersion).isEmpty()
            assertThat(libs.libraries.map { it.uniqueId }).containsAtLeast("neutrodyne:skia", "neutrodyne:jna-libffi")
        }

    private companion object {
        /** Licences whose binary redistribution needs the component's own copyright notice. */
        val COPYRIGHT_FAMILIES =
            setOf(
                "MIT",
                "MIT-Modern-Variant",
                "ISC",
                "BSD-2-Clause",
                "BSD-3-Clause",
                "IJG",
                "libpng-2.0",
                "FTL",
                "Unicode-3.0",
            )
        val PLACEHOLDERS = listOf("<copyright holders>", "<year>", "<owner>")
    }
}
