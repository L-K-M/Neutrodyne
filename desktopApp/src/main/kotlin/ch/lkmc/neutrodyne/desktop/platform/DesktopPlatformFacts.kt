// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.platform

import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

/**
 * [PlatformInfo] and the interim [BuildInfo] for the desktop shell, from `System.getProperty` and
 * [DesktopBuildInfo] (the `install-kind` and `build-info.properties` classpath resources). The
 * desktop [BuildInfo] is interim until M0b's packaging facts land (11): version stamps, the
 * `runtime` flag and packaged update URLs are placeholders of a dev run.
 */
internal object DesktopPlatformFacts : PlatformInfo {
    private const val UPDATE_MANIFEST_PATH = "/releases/latest/download/neutrodyne-update.json"

    override val kind: PlatformKind = PlatformKind.DESKTOP

    override val userAgentPlatform: String = "${osName()}; ${archSegment()}"

    override val androidSdkInt: Int? = null

    override val regionCode: String
        get() = Locale.getDefault().country.uppercase(Locale.ROOT)

    val os: DesktopOs =
        when {
            osName().startsWith("Windows") -> DesktopOs.WINDOWS
            osName().startsWith("Mac") || osName().startsWith("Darwin") -> DesktopOs.MACOS
            else -> DesktopOs.LINUX
        }

    val arch: DesktopArch =
        when (System.getProperty("os.arch")?.lowercase()) {
            "aarch64", "arm64" -> DesktopArch.ARM64
            else -> DesktopArch.X64
        }

    fun buildInfo(): BuildInfo =
        BuildInfo(
            versionName = DesktopBuildInfo.versionName,
            versionCode = 0,
            debug = DesktopBuildInfo.installKind == InstallKind.DEV,
            platform = BuildInfo.Platform.DESKTOP,
            repoUrl = DesktopBuildInfo.repoUrl,
            updateManifestUrl = DesktopBuildInfo.repoUrl + UPDATE_MANIFEST_PATH,
            engineManifestUrl = DesktopBuildInfo.engineManifestUrl,
            youTubeEngineBundled = false,
            desktop =
                BuildInfo.Desktop(
                    os = os,
                    arch = arch,
                    installKind = DesktopBuildInfo.installKind,
                    // Dev runs use the system JVM; packaged images carry the jlinked runtime (M0b).
                    runtime = if (DesktopBuildInfo.installKind == InstallKind.DEV) "system" else "bundled",
                ),
            shippedLocales = DesktopBuildInfo.shippedLocales.toImmutableList(),
            podcastIndexKey = DesktopBuildInfo.podcastIndexKey,
            podcastIndexSecret = DesktopBuildInfo.podcastIndexSecret,
        )

    private fun osName(): String = System.getProperty("os.name") ?: "Linux"

    private fun archSegment(): String = if (arch == DesktopArch.ARM64) "arm64" else "x64"
}
