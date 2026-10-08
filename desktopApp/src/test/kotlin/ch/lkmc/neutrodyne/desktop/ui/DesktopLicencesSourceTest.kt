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
}
