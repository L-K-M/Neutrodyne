// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.buildinfo

import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/**
 * [BuildInfoLoader]: the generated `dev` resource matches `gradle.properties` (the resource is
 * built from it, reproducibly), a packaged resource overrides the image identity, and a missing
 * resource degrades to the `DEV` defaults answered by the host JVM.
 */
class BuildInfoLoaderTest {
    @Test
    fun `the generated dev resource matches gradle properties`() {
        val info = BuildInfoLoader.load()
        val gradle = gradleProperties()

        assertThat(info.versionName).isEqualTo(gradle["neutrodyne.versionName"])
        assertThat(info.versionCode).isEqualTo(gradle["neutrodyne.versionCode"]!!.toInt())
        assertThat(info.repoUrl).isEqualTo(gradle["neutrodyne.repoUrl"])
        assertThat(info.engineManifestUrl).isEqualTo(gradle["neutrodyne.engineManifestUrl"])
        assertThat(info.youTubeEngineBundled).isEqualTo(gradle["neutrodyne.youtubeEngine"]!!.toBoolean())

        assertThat(info.platform).isEqualTo(BuildInfo.Platform.DESKTOP)
        assertThat(info.debug).isTrue()
        assertThat(info.desktop!!.installKind).isEqualTo(InstallKind.DEV)
        assertThat(info.shippedLocales).containsExactly("en")
        assertThat(info.podcastIndexKey).isEmpty()
    }

    @Test
    fun `a packaged resource overrides the image identity`() {
        val resource = """
            versionName=1.2.3
            versionCode=200
            repoUrl=https://github.com/L-K-M/Neutrodyne
            engineManifestUrl=https://l-k-m.github.io/Neutrodyne/engine/ytdlp-approved.json
            youtubeEngine=true
            installKind=msi
            os=windows
            arch=arm64
            runtime=Temurin-25.0.4.1+1
            shippedLocales=en,de
        """.trimIndent()

        val info = BuildInfoLoader.load(resource, host = { null })

        assertThat(info.debug).isFalse()
        assertThat(info.desktop!!.os).isEqualTo(DesktopOs.WINDOWS)
        assertThat(info.desktop!!.arch).isEqualTo(DesktopArch.ARM64)
        assertThat(info.desktop!!.installKind).isEqualTo(InstallKind.MSI)
        assertThat(info.desktop!!.runtime).isEqualTo("Temurin-25.0.4.1+1")
        assertThat(info.shippedLocales).containsExactly("en", "de").inOrder()
        assertThat(info.updateManifestUrl)
            .isEqualTo("https://github.com/L-K-M/Neutrodyne/releases/latest/download/neutrodyne-update.json")
    }

    @Test
    fun `without a resource the dev defaults come from the host JVM`() {
        val host = BuildInfoLoader.Host { name ->
            mapOf(
                "os.name" to "Linux",
                "os.arch" to "amd64",
                "java.vendor" to "Eclipse Adoptium",
                "java.runtime.version" to "25.0.1+12",
            )[name]
        }

        val info = BuildInfoLoader.load(null, host)

        assertThat(info.versionName).isEqualTo("0.0.0-dev")
        assertThat(info.versionCode).isEqualTo(0)
        assertThat(info.debug).isTrue()
        assertThat(info.desktop!!.os).isEqualTo(DesktopOs.LINUX)
        assertThat(info.desktop!!.arch).isEqualTo(DesktopArch.X64)
        assertThat(info.desktop!!.installKind).isEqualTo(InstallKind.DEV)
        assertThat(info.desktop!!.runtime).isEqualTo("Eclipse Adoptium 25.0.1+12")
    }

    private fun gradleProperties(): Map<String, String> {
        val root = System.getProperty("neutrodyne.rootDir")
            ?: error("the test tasks set neutrodyne.rootDir (build-logic TestConventions)")
        return Files.readAllLines(Path.of(root, "gradle.properties"))
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                val index = line.indexOf('=')
                line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
    }
}
