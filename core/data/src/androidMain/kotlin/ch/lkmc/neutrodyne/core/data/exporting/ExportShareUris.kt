// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import ch.lkmc.neutrodyne.core.common.StoragePaths
import dev.zacsweers.metro.Inject
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

/**
 * The `content://{applicationId}.fileprovider/export/{fileName}` URIs of 05 Destinations — the
 * only URIs the Android share sheet may hand out. [application.packageName] is the build's
 * application id, so release and debug builds each resolve their own provider authority.
 *
 * Only a minted leaf name resolves: a name that is not a single path segment is rejected here
 * and never reaches the provider, and `FileProvider` re-checks the file against the
 * `cache-path` roots the manifest registers — a URI can never expose a file outside
 * `<cache>/export/`.
 */
internal class ExportShareUris
    @Inject
    constructor(
        application: Application,
        storagePaths: StoragePaths,
    ) {
        private val context: Context = application
        private val exportDir: File = exportDirOf(storagePaths).toFile()

        /** `${applicationId}.fileprovider` — matches the manifest's `${applicationId}` substitution. */
        internal val authority: String = "${application.packageName}$PROVIDER_SUFFIX"

        /**
         * The share URI for [fileName], or `null` when the name is not a leaf or the provider
         * cannot resolve the file against its registered roots.
         */
        fun uriFor(fileName: String): Uri? {
            if (!isLeafFileName(fileName)) return null
            return try {
                FileProvider.getUriForFile(context, authority, File(exportDir, fileName))
            } catch (e: IllegalArgumentException) {
                // No provider under the authority, or the file escapes the registered roots.
                null
            }
        }

        internal companion object {
            /** The authority suffix every Neutrodyne FileProvider registers under. */
            const val PROVIDER_SUFFIX = ".fileprovider"

            /**
             * One path segment, unnormalised — every export name `OpmlExporter`/`BackupWriter`
             * mints satisfies this; anything else (empty, `.`, `..`, separators, or a name
             * `Path` parsing would normalise away) never reaches the provider.
             */
            fun isLeafFileName(name: String): Boolean =
                name.isNotEmpty() && name != "." && name != ".." &&
                    '/' !in name && '\\' !in name && name.toPath().name == name
        }
    }
