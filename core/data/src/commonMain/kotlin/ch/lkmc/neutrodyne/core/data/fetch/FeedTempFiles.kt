// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.fetch

import ch.lkmc.neutrodyne.core.common.StoragePaths
import dev.zacsweers.metro.Inject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random

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

    /**
     * Engine-run step 2 (03 Body, hashing and sniffing): removes leftovers older than one hour —
     * crashes and stops can strand partial bodies. Errors are ignored; the next sweep retries.
     */
    fun sweep(nowMs: Long) {
        val children =
            try {
                fileSystem.listOrNull(dir) ?: return
            } catch (_: Exception) {
                return
            }
        for (child in children) {
            val modified =
                try {
                    fileSystem.metadataOrNull(child)?.lastModifiedAtMillis
                } catch (_: Exception) {
                    null
                } ?: continue
            if (nowMs - modified > MAX_AGE_MS) delete(child)
        }
    }

    private companion object {
        const val MAX_AGE_MS = 60L * 60 * 1000
    }
}
