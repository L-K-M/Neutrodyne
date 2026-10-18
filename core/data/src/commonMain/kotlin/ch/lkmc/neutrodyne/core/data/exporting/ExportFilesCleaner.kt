// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.StoragePaths
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import okio.FileSystem
import okio.Path

/**
 * The export-cache sweep of 05 Destinations, a start-up `AppInitializer` at band 300 on both
 * platforms: deletes files in `<cache>/export/` older than 24 h — leftovers of cancelled
 * shares and crashed prepares. Errors are ignored like `FeedTempFiles.sweep`'s; the next start
 * retries. A same-day re-export overwrites its own file, so no same-name stale check is needed.
 */
@ContributesIntoSet(AppScope::class)
internal class ExportFilesCleaner
    @Inject
    constructor(
        storagePaths: StoragePaths,
        private val fileSystem: FileSystem,
        private val clock: Clock,
    ) : AppInitializer {
        override val order: Int = ORDER

        private val exportDir: Path = exportDirOf(storagePaths)

        override suspend fun run() {
            val children =
                try {
                    fileSystem.listOrNull(exportDir) ?: return
                } catch (_: Exception) {
                    return
                }
            val cutoff = clock.now() - MAX_AGE_MS
            for (child in children) {
                val modified =
                    try {
                        fileSystem.metadataOrNull(child)?.lastModifiedAtMillis
                    } catch (_: Exception) {
                        null
                    } ?: continue
                if (modified < cutoff) deleteQuietly(child)
            }
        }

        private fun deleteQuietly(path: Path) {
            try {
                fileSystem.delete(path)
            } catch (_: Exception) {
                // A vanished or unwritable leftover is not an error; the next sweep retries.
            }
        }

        private companion object {
            const val ORDER = 300
            const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        }
    }
