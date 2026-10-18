// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ImportError
import okio.Source

/**
 * The platform half of `PayloadStore` acquisition (05 OPML import §1): opens an import URI the
 * app holds a grant for. Android binds `ContentPayloadSource` — `content://` (and best-effort
 * `file://` from old senders) through ContentResolver with `OpenableColumns` metadata (row C1);
 * the desktop binds `FilePayloadSource` — regular-file check then open (row C3). `https://`
 * sources never reach this port: `PayloadStore` fetches them with the feed client itself, where
 * the failure taxonomy is `NetError`, not this port's.
 *
 * `internal` like `exporting.ExportDestination` — nothing binds it in common code until C1/C3.
 */
internal interface PayloadSource {
    /** A payload opened for one counted copy; [open] yields the stream the caller consumes. */
    class Opened(
        /** Fresh readable stream; consumed and closed once by [PayloadStore]. */
        val open: () -> Source,
        /** `OpenableColumns.DISPLAY_NAME` / file name — carried to `import_session.sourceName`. */
        val displayName: String?,
        /** Declared byte size when the platform reports one (`SIZE` column, file metadata). */
        val declaredSize: Long?,
    )

    /**
     * Opens [uri]. `Outcome.Failure` is [ImportError.Unreadable] — grant lost, provider error or
     * not a regular file; implementations never return [ImportError.Network] (network URIs do
     * not reach this port). A declared size over `PayloadStore`'s cap may fail early as
     * [ImportError.TooLarge], but the counting copy bounds the read regardless — sizes lie.
     */
    suspend fun open(uri: String): Outcome<Opened, ImportError>
}
