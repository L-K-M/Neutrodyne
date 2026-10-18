// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import android.content.ContentResolver
import android.net.Uri
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ExportError
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.sink
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * `ExportDestination` over the Storage Access Framework (05 Destinations, row C1): [save]
 * copies the already-prepared cache file to the `CreateDocument` URI, and [shareUri] is the
 * FileProvider `export/` URI the share sheet grants out. Both the write and the share URI are
 * minted-name only — nothing outside `<cache>/export/` ever leaves through this adapter.
 *
 * A failed or skipped save is never reported as success: open failures are
 * [ExportError.DestinationUnavailable], reading the prepared file is [ExportError.Io], and a
 * cancellation still propagates after both streams are closed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class SafExportDestination(
    private val resolver: ContentResolver,
    private val shareUris: ExportShareUris,
    private val fileSystem: FileSystem,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) : ExportDestination {
    override fun shareUri(fileName: String): String? = shareUris.uriFor(fileName)?.toString()

    override suspend fun save(
        source: Path,
        destinationUri: String,
    ): Outcome<Unit, ExportError> =
        withContext(io) {
            val destination =
                openDestination(Uri.parse(destinationUri))
                    ?: return@withContext Outcome.Failure(ExportError.DestinationUnavailable)
            try {
                // `use` owns the provider stream: a missing source, a cut mid-copy and a
                // cancellation all close it before their outcome leaves.
                destination.use { out -> copyPrepared(source, out) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // What is left to fail: closing the provider stream.
                Outcome.Failure(ExportError.DestinationUnavailable)
            }
        }

    /**
     * SAF open (05 Destinations): `"wt"` first — plain `"w"` "may or may not truncate" — with
     * the `"w"` retry only when the provider rejects the mode outright
     * (`IllegalArgumentException`, `FileNotFoundException`) and therefore before a single byte
     * is written; a `CreateDocument` target is a fresh document, so the fallback is safe. A
     * `SecurityException` (grant or permission lost) and a null stream have no fallback.
     */
    private fun openDestination(uri: Uri): OutputStream? {
        try {
            return resolver.openOutputStream(uri, MODE_TRUNCATE)
        } catch (e: IllegalArgumentException) {
            // Mode unknown to this provider — fall through to "w".
        } catch (e: FileNotFoundException) {
            // Ditto — the provider rejects "wt" before any bytes.
        } catch (e: Exception) {
            return null
        }
        return try {
            resolver.openOutputStream(uri, MODE_WRITE)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The counted copy's plain twin: read chunks of the prepared file into the provider
     * stream. The source close and the buffered tail's emit/flush ride `use`; cancellation
     * between chunks propagates through [ensureActive] and still closes everything.
     */
    private suspend fun copyPrepared(
        source: Path,
        destination: OutputStream,
    ): Outcome<Unit, ExportError> {
        val prepared =
            try {
                fileSystem.source(source).buffer()
            } catch (e: Exception) {
                return Outcome.Failure(ExportError.Io)
            }
        return prepared.use { src ->
            destination.sink().buffer().use { sink ->
                val chunk = Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read =
                        try {
                            src.read(chunk, CHUNK_BYTES.toLong())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            return Outcome.Failure(ExportError.Io)
                        }
                    if (read == -1L) break
                    try {
                        sink.write(chunk, read)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return Outcome.Failure(ExportError.DestinationUnavailable)
                    }
                }
                try {
                    sink.flush()
                } catch (e: Exception) {
                    return Outcome.Failure(ExportError.DestinationUnavailable)
                }
            }
            Outcome.Success(Unit)
        }
    }

    private companion object {
        /** `ContentResolver` write mode: truncate before write; the documented fallback is "w". */
        const val MODE_TRUNCATE = "wt"
        const val MODE_WRITE = "w"

        /** Same 16 KiB copy chunk `PayloadStore` uses. */
        const val CHUNK_BYTES = 16 * 1024
    }
}
