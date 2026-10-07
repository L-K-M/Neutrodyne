// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.fetch

import ch.lkmc.neutrodyne.core.common.StoragePaths
import dev.zacsweers.metro.Inject
import kotlin.random.Random
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The fetch pipeline's temp-file owner (03 Body, hashing and sniffing):
 * `<StoragePaths.cache>/feeds/tmp-<rand>.part`. 01's `TempFileSweeper` removes anything left over
 * for more than a day (crashes, cancellations).
 */
@Inject
internal class FeedTempFiles(
    storagePaths: StoragePaths,
    private val fileSystem: FileSystem,
) {
    private val dir: Path = storagePaths.cacheDir.toPath() / "feeds"

    fun create(): Path {
        fileSystem.createDirectories(dir)
        return dir / "tmp-${Random.nextBits(32).toULong().toString(16)}.part"
    }

    fun delete(path: Path) {
        try {
            fileSystem.delete(path)
        } catch (_: Exception) {
            // A vanished temp file is not an error.
        }
    }
}
