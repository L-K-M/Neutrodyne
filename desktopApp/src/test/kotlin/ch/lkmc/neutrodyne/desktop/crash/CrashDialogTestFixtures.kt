// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DesktopArch
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.InstallKind
import kotlinx.collections.immutable.persistentListOf
import java.nio.file.Files
import java.nio.file.Path

/** A configured (non-DEV) desktop [BuildInfo] for the crash dialog tests; [mailto] "" means no mailbox. */
internal fun crashDialogBuildInfo(
    mailto: String = "neutrodyne@example.com",
    installKind: InstallKind = InstallKind.MSI,
): BuildInfo =
    BuildInfo(
        versionName = "1.0.0",
        versionCode = 1,
        debug = installKind == InstallKind.DEV,
        platform = BuildInfo.Platform.DESKTOP,
        repoUrl = "https://github.com/L-K-M/Neutrodyne",
        updateManifestUrl = "https://github.com/L-K-M/Neutrodyne/releases/latest/download/neutrodyne-update.json",
        engineManifestUrl = "https://example.com/engine.json",
        youTubeEngineBundled = true,
        apkAbi = null,
        desktop =
            BuildInfo.Desktop(
                os = DesktopOs.LINUX,
                arch = DesktopArch.X64,
                installKind = installKind,
                runtime = "Temurin 25",
            ),
        shippedLocales = persistentListOf("en", "de"),
        acraMailto = mailto,
        podcastIndexKey = "",
        podcastIndexSecret = "",
    )

/** Writes an unsent `crash-*.txt` file into the state dir and returns its path. */
internal fun AppDirs.writeCrashFile(
    name: String,
    body: String,
): Path {
    val file = state.resolve(name)
    Files.writeString(file, body)
    return file
}
