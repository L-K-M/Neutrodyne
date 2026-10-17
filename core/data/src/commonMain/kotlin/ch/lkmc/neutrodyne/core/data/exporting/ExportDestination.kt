// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.domain.ExportError
import okio.Path
import okio.Path.Companion.toPath

/**
 * The platform half of `ExportRepository` (05 Destinations): how the prepared cache file leaves
 * the app and what URI the share sheet hands out. Android binds `SafExportDestination` — a SAF
 * copy opened `"wt"` plus the `content://{applicationId}.fileprovider/export/…` URI (row C1);
 * the desktop binds `FileExportDestination` — an atomic replace of a `file:` URI, no share
 * sheet (row C3). Both live in `:core:data` platform source sets beside this interface; nothing
 * binds it in common code yet, so `ExportRepositoryImpl` stays unbound until C1/C3 land.
 */
internal interface ExportDestination {
    /**
     * The URI the platform share sheet hands out for the prepared cache file — Android's
     * FileProvider `export/{fileName}` path; `null` where there is no share sheet (the desktop).
     */
    fun shareUri(fileName: String): String?

    /** Copies the prepared cache file at [source] to [destinationUri]. */
    suspend fun save(
        source: Path,
        destinationUri: String,
    ): Outcome<Unit, ExportError>
}

/** `<cache-path name="export">` — the private export cache of 05 Destinations. */
internal const val EXPORT_DIR_NAME = "export"

/** `<cache>/export/` — where [ch.lkmc.neutrodyne.core.domain.ExportRepository.prepare] writes and [ExportFilesCleaner] sweeps. */
internal fun exportDirOf(storagePaths: StoragePaths): Path = storagePaths.cacheDir.toPath() / EXPORT_DIR_NAME
