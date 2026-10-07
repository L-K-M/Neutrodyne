// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import kotlinx.collections.immutable.ImmutableList

/**
 * Build-time identity baked into the binary (01 Build variants and ABIs). The shells construct it
 * in `:app` / `:desktopApp` and bind it `@SingleIn(AppScope::class)`; features and workers inject it.
 *
 * The secrets are omitted from [toString] so log output never leaks them.
 */
data class BuildInfo(
    val versionName: String,
    val versionCode: Int,
    val debug: Boolean,
    /** Which product this binary is: `ANDROID` for APKs, `DESKTOP` for the desktop app. */
    val platform: Platform,
    /** Always the GitHub repo URL; the only update, install and support endpoint. */
    val repoUrl: String,
    val updateManifestUrl: String,
    /** Approved yt-dlp "engine manifest" URL on GitHub Pages (D76). */
    val engineManifestUrl: String,
    /** Whether the approved yt-dlp runtime ships in this binary (01; false = emergency build, L1). */
    val youTubeEngineBundled: Boolean,
    /** Android only: the APK's ABI (`arm64-v8a` / `armeabi-v7a`); `null` on desktop. */
    val apkAbi: String? = null,
    /** Desktop only: OS, arch, install kind and runtime (D91); `null` on Android. */
    val desktop: Desktop? = null,
    /** Localisation codes shipped in this build, e.g. `en`, `de` (11). */
    val shippedLocales: ImmutableList<String>,
    /** Written into the binary only by release builds with written Podcast Index permission (PO-3). */
    val podcastIndexKey: String,
    val podcastIndexSecret: String,
) {
    enum class Platform { ANDROID, DESKTOP }

    /** Desktop identity block (D91). `runtime` is `bundled` or `system`. */
    data class Desktop(
        val os: DesktopOs,
        val arch: DesktopArch,
        val installKind: InstallKind,
        val runtime: String,
    )

    override fun toString(): String =
        "BuildInfo(versionName=$versionName, versionCode=$versionCode, debug=$debug, " +
            "platform=$platform, repoUrl=$repoUrl, updateManifestUrl=$updateManifestUrl, " +
            "engineManifestUrl=$engineManifestUrl, youTubeEngineBundled=$youTubeEngineBundled, " +
            "apkAbi=$apkAbi, desktop=$desktop, shippedLocales=$shippedLocales)"
}
