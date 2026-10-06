// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.buildinfo

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import java.util.Properties
import kotlinx.collections.immutable.toImmutableList

/**
 * Builds the desktop [BuildInfo] (11 DesktopAppGraph) from the `build-info.properties` resource
 * the build generates from `gradle.properties` (`desktopApp/build.gradle.kts`) and the packaging
 * pipeline overrides for published images.
 *
 * Which value comes from where:
 * - **Always from the resource:** `versionName`, `versionCode`, `repoUrl`, `engineManifestUrl`,
 *   `youtubeEngine`, `installKind` (`dev` in every local build; `debug` is true exactly then).
 * - **Resource when present, else the running JVM:** `os`, `arch`, `runtime` — a packaged image
 *   carries its target's values, while `:desktopApp:run` and tests report the host JVM, which is
 *   the only truthful answer for a `DEV` build.
 * - **Empty in local builds:** the Podcast Index secrets (never written into the dev resource;
 *   release packaging adds them). The committed `acraMailto` is written to the resource for the
 *   crash email dialog (11 Crash files and the email dialog, pending with the window), which does
 *   not read it through [BuildInfo].
 *
 * A missing resource (or a missing single key where the JVM can answer) degrades to the `DEV`
 * defaults instead of failing the start-up.
 */
internal object BuildInfoLoader {
    private const val RESOURCE_PATH = "build-info.properties"

    private const val KEY_VERSION_NAME = "versionName"
    private const val KEY_VERSION_CODE = "versionCode"
    private const val KEY_REPO_URL = "repoUrl"
    private const val KEY_ENGINE_MANIFEST_URL = "engineManifestUrl"
    private const val KEY_YOUTUBE_ENGINE = "youtubeEngine"
    private const val KEY_INSTALL_KIND = "installKind"
    private const val KEY_OS = "os"
    private const val KEY_ARCH = "arch"
    private const val KEY_RUNTIME = "runtime"
    private const val KEY_SHIPPED_LOCALES = "shippedLocales"
    private const val KEY_PODCASTINDEX_KEY = "podcastIndexKey"
    private const val KEY_PODCASTINDEX_SECRET = "podcastIndexSecret"

    private const val DEFAULT_VERSION_NAME = "0.0.0-dev"
    private const val DEFAULT_VERSION_CODE = 0
    private const val DEFAULT_LOCALE = "en"

    private const val UPDATE_MANIFEST_PATH = "/releases/latest/download/neutrodyne-update.json"

    /** Where host-derived facts come from; the JVM in production, fixed values in tests. */
    fun interface Host {
        fun property(name: String): String?
    }

    private val JVM_HOST = Host { name -> System.getProperty(name) }

    /** Loads the real classpath resource and derives the rest from this JVM. */
    fun load(): BuildInfo = load(resourceContent(), JVM_HOST)

    /** Pure form for tests: [resource] is the properties file's text, `null` when absent. */
    fun load(resource: String?, host: Host = JVM_HOST): BuildInfo {
        val properties = Properties()
        if (resource != null) {
            properties.load(java.io.StringReader(resource))
        }

        val installKind = properties.getProperty(KEY_INSTALL_KIND)?.let(::installKindOf) ?: InstallKind.DEV
        return BuildInfo(
            versionName = properties.getProperty(KEY_VERSION_NAME) ?: DEFAULT_VERSION_NAME,
            versionCode = properties.getProperty(KEY_VERSION_CODE)?.toIntOrNull() ?: DEFAULT_VERSION_CODE,
            debug = installKind == InstallKind.DEV,
            platform = BuildInfo.Platform.DESKTOP,
            repoUrl = properties.getProperty(KEY_REPO_URL).orEmpty(),
            updateManifestUrl = (properties.getProperty(KEY_REPO_URL).orEmpty()) + UPDATE_MANIFEST_PATH,
            engineManifestUrl = properties.getProperty(KEY_ENGINE_MANIFEST_URL).orEmpty(),
            youTubeEngineBundled = properties.getProperty(KEY_YOUTUBE_ENGINE)?.toBooleanStrictOrNull() ?: false,
            apkAbi = null,
            desktop = BuildInfo.Desktop(
                os = properties.getProperty(KEY_OS)?.let(::osOf) ?: hostOs(host),
                arch = properties.getProperty(KEY_ARCH)?.let(::archOf) ?: hostArch(host),
                installKind = installKind,
                runtime = properties.getProperty(KEY_RUNTIME) ?: hostRuntime(host),
            ),
            shippedLocales = (properties.getProperty(KEY_SHIPPED_LOCALES) ?: DEFAULT_LOCALE)
                .split(',')
                .filter(String::isNotBlank)
                .toImmutableList(),
            podcastIndexKey = properties.getProperty(KEY_PODCASTINDEX_KEY).orEmpty(),
            podcastIndexSecret = properties.getProperty(KEY_PODCASTINDEX_SECRET).orEmpty(),
        )
    }

    private fun resourceContent(): String? =
        BuildInfoLoader::class.java.classLoader?.getResourceAsStream(RESOURCE_PATH)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun installKindOf(wire: String): InstallKind =
        InstallKind.entries.firstOrNull { it.wire == wire }
            ?: error("build-info.properties carries an unknown installKind '$wire'")

    private fun osOf(wire: String): DesktopOs =
        DesktopOs.entries.firstOrNull { it.wire == wire }
            ?: error("build-info.properties carries an unknown os '$wire'")

    private fun archOf(wire: String): DesktopArch =
        DesktopArch.entries.firstOrNull { it.wire == wire }
            ?: error("build-info.properties carries an unknown arch '$wire'")

    private fun hostOs(host: Host): DesktopOs = when (AppDirs.DesktopOs.current(osName(host))) {
        AppDirs.DesktopOs.WINDOWS -> DesktopOs.WINDOWS
        AppDirs.DesktopOs.MACOS -> DesktopOs.MACOS
        AppDirs.DesktopOs.LINUX -> DesktopOs.LINUX
    }

    private fun hostArch(host: Host): DesktopArch = when (host.property("os.arch")?.lowercase()) {
        "amd64", "x86_64" -> DesktopArch.X64
        "aarch64", "arm64" -> DesktopArch.ARM64
        else -> error("this JVM runs on an architecture Neutrodyne does not ship: ${host.property("os.arch")}")
    }

    /** e.g. "Eclipse Adoptium 25+36" — the smoke line's `java.vendor`/`java.runtime.version` pair. */
    private fun hostRuntime(host: Host): String =
        "${host.property("java.vendor") ?: "unknown"} ${host.property("java.runtime.version") ?: "unknown"}"

    private fun osName(host: Host): String = host.property("os.name") ?: "Linux"
}
