// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.platform

import android.os.Build
import android.os.Process
import ch.lkmc.neutrodyne.BuildConfig
import ch.lkmc.neutrodyne.core.model.BuildInfo
import kotlinx.collections.immutable.toImmutableList

/**
 * Builds [BuildInfo] from `BuildConfig` and `Build` (01 Build variants and ABIs). `apkAbi` is the ABI of
 * the installed APK: the first 64-bit ABI in a 64-bit process, else the first 32-bit one.
 */
internal object AndroidBuildInfo {
    private const val UPDATE_MANIFEST_PATH = "/releases/latest/download/neutrodyne-update.json"

    fun create(): BuildInfo {
        val is64Bit = Process.is64Bit()
        val abi = if (is64Bit) Build.SUPPORTED_64_BIT_ABIS.firstOrNull() else Build.SUPPORTED_32_BIT_ABIS.firstOrNull()

        return BuildInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            debug = BuildConfig.DEBUG,
            platform = BuildInfo.Platform.ANDROID,
            repoUrl = BuildConfig.REPO_URL,
            updateManifestUrl = BuildConfig.REPO_URL + UPDATE_MANIFEST_PATH,
            engineManifestUrl = BuildConfig.ENGINE_MANIFEST_URL,
            youTubeEngineBundled = BuildConfig.YOUTUBE_ENGINE && is64Bit,
            apkAbi = abi,
            shippedLocales =
                BuildConfig.SHIPPED_LOCALES
                    .split(',')
                    .filter { it.isNotBlank() }
                    .toImmutableList(),
            podcastIndexKey = BuildConfig.PODCASTINDEX_KEY,
            podcastIndexSecret = BuildConfig.PODCASTINDEX_SECRET,
        )
    }
}
