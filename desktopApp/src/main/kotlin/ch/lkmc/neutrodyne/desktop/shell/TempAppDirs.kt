// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import ch.lkmc.neutrodyne.core.common.AppDirs
import java.nio.file.Files
import java.nio.file.Path

/**
 * An [AppDirs] layout under a temporary [root] — how tests and smoke mode get directories that
 * never touch the user's data (11 AppDirs "Tests"; 11 Smoke mode step 1). The layout mirrors the
 * Linux table: `logs` inside `state`, `Downloads` inside `data`.
 */
internal fun appDirsUnder(root: Path): AppDirs {
    val state = root.resolve("state")
    return AppDirs(
        data = root.resolve("data"),
        config = root.resolve("config"),
        cache = root.resolve("cache"),
        state = state,
        logs = state.resolve("logs"),
        downloadsDefault = root.resolve("data").resolve("Downloads"),
    )
}

/** Creates a fresh temporary root and returns its [AppDirs]; the caller owns the root's lifetime. */
internal fun tempAppDirs(prefix: String = "neutrodyne-test"): AppDirs = appDirsUnder(Files.createTempDirectory(prefix))
